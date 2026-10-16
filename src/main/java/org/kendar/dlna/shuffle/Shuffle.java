package org.kendar.dlna.shuffle;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

import org.kendar.dlna.catalog.CatalogNavigation;
import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.ClientId;

/**
 * Random N snapshots (plan section 7): one per (client, directory), a seeded stable order over the playable
 * tracks of the directory's subtree, kept alive by browse or stream activity and never expiring while a stream is
 * active. In memory only. Also the {@link ContainerNavigation} of the {@code r.<dirId>.<snapshotId>} contexts.
 */
public final class Shuffle implements ContainerNavigation {

    public static final String CONTEXT_PREFIX = "r.";
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    /** An expired snapshot that is still the client's current one is dropped after this much idle time. */
    private static final Duration RETAIN_CURRENT = Duration.ofHours(24);

    private record Key(ClientId client, String directoryId) {
    }

    private final Supplier<Snapshot> catalog;
    private final Clock clock;
    private final Duration ttl;
    private final Random seeds;

    private final Map<Key, RandomSnapshot> current = new HashMap<>();
    private final Map<String, RandomSnapshot> byContext = new HashMap<>();

    public Shuffle(Supplier<Snapshot> catalog) {
        this(catalog, Clock.systemUTC(), DEFAULT_TTL);
    }

    public Shuffle(Supplier<Snapshot> catalog, Clock clock, Duration ttl) {
        this(catalog, clock, ttl, new SecureRandom());
    }

    /** @param seeds source of snapshot seeds (injectable for tests) */
    public Shuffle(Supplier<Snapshot> catalog, Clock clock, Duration ttl, Random seeds) {
        this.catalog = catalog;
        this.clock = clock;
        this.ttl = ttl;
        this.seeds = seeds;
    }

    public static String contextId(String directoryId, String snapshotId) {
        return CONTEXT_PREFIX + directoryId + "." + snapshotId;
    }

    /** True for ids of the form {@code r.<dirId>.<snapshotId>}. */
    public static boolean isContext(String id) {
        return id != null && id.startsWith(CONTEXT_PREFIX) && id.indexOf('.', CONTEXT_PREFIX.length()) > 0;
    }

    /** Container title: "[Random N]". */
    public static String title(int n) {
        return "[Random " + n + "]";
    }

    /**
     * The client's snapshot for the directory, creating a new one when there is none, the previous one expired
     * (idle longer than the TTL with no active stream) or N changed. Counts as activity. Called when the
     * directory is browsed.
     *
     * @param folder a directory with {@code random > 0}
     */
    public synchronized RandomSnapshot open(ClientId client, Folder folder) {
        return open(client, folder.id(), folder.settings().random(),
                () -> catalog.get().subtreeTracks(folder.ref()));
    }

    /**
     * Same for any scope: {@code scopeId} names it in the context id (a navigation uses {@code <dirId>.<navKey>}),
     * {@code pool} lists the tracks to pick from (asked only when a new snapshot is made).
     */
    public synchronized RandomSnapshot open(ClientId client, String scopeId, int n,
            Supplier<List<Track>> pool) {
        Instant now = clock.instant();
        sweep(now);
        Key key = new Key(client, scopeId);
        RandomSnapshot cur = current.get(key);
        if (cur != null && !isExpired(cur, now) && cur.requestedSize() == n) {
            cur.touch(now);
            return cur;
        }
        RandomSnapshot fresh = create(client, scopeId, n, pool.get(), now);
        current.put(key, fresh);
        byContext.put(fresh.contextId(), fresh);
        sweep(now);   // the replaced snapshot may now be unreferenced
        return fresh;
    }

    /** The snapshot of a context id, counting the lookup as activity. */
    public synchronized Optional<RandomSnapshot> find(String contextId) {
        RandomSnapshot s = byContext.get(contextId);
        if (s != null) {
            s.touch(clock.instant());
        }
        return Optional.ofNullable(s);
    }

    /** Number of retained snapshots (for the admin view and tests). */
    public synchronized int snapshotCount() {
        return byContext.size();
    }

    public synchronized List<RandomSnapshot> snapshots() {
        return List.copyOf(byContext.values());
    }

    @Override
    public synchronized boolean knows(String containerId) {
        return byContext.containsKey(containerId);
    }

    @Override
    public Optional<Track> next(String containerId, String itemId) {
        RandomSnapshot s;
        synchronized (this) {
            s = byContext.get(containerId);
        }
        return s == null ? Optional.empty() : CatalogNavigation.after(s.tracks(catalog.get()), itemId);
    }

    @Override
    public Lease streamStarted(String containerId) {
        RandomSnapshot s;
        synchronized (this) {
            s = byContext.get(containerId);
            if (s == null) {
                return Lease.NONE;
            }
            s.streamOpened();
            s.touch(clock.instant());
        }
        return () -> {
            synchronized (Shuffle.this) {
                s.streamClosed();
                s.touch(clock.instant());
            }
        };
    }

    /**
     * Stateless seeded pick for the web API: every track of the given roots (or of one directory subtree) in a
     * shuffled order that only depends on the catalog content, the seed and the user. The same seed gives the same
     * order again, so a client can page through it.
     *
     * @param roots  library roots the caller may see; nothing outside them is ever picked
     * @param folder subtree to pick from, or null for all of the roots (must lie within {@code roots})
     */
    public static List<Track> pick(Snapshot snap, java.util.Set<String> roots, org.kendar.dlna.shared.RootRef folder,
            String userKey, long seed) {
        List<Track> pool = new ArrayList<>();
        if (folder != null) {
            if (roots.contains(folder.root())) {
                pool.addAll(snap.subtreeTracks(folder));
            }
        } else {
            for (Track t : snap.visibleTracks()) {
                if (roots.contains(t.ref().root())) {
                    pool.add(t);
                }
            }
        }
        pool.sort(Comparator.comparing(Track::ref));
        Collections.shuffle(pool, new Random(seed * 0x9E3779B97F4A7C15L + (userKey == null ? 0 : userKey.hashCode())));
        return pool;
    }

    private boolean isExpired(RandomSnapshot s, Instant now) {
        return s.activeStreams() == 0 && Duration.between(s.lastActivity(), now).compareTo(ttl) > 0;
    }

    private RandomSnapshot create(ClientId client, String scopeId, int n, List<Track> tracks, Instant now) {
        long seed;
        String id;
        do {
            seed = seeds.nextLong();
            id = Long.toHexString(seed);
        } while (byContext.containsKey(contextId(scopeId, id)));
        List<Track> pool = new ArrayList<>(tracks);
        pool.sort(Comparator.comparing(Track::ref));   // the seeded order must not depend on display order
        Collections.shuffle(pool, new Random(seed));
        List<Track> picked = SimilarTitles.distinct(pool, n);
        return new RandomSnapshot(id, scopeId, client, n, picked.stream().map(Track::ref).toList(), now);
    }

    /** Drops snapshots nobody can still use: idle past the TTL, no active stream, and superseded. */
    private void sweep(Instant now) {
        Iterator<RandomSnapshot> it = byContext.values().iterator();
        while (it.hasNext()) {
            RandomSnapshot s = it.next();
            if (!isExpired(s, now)) {
                continue;
            }
            Key key = new Key(s.client(), s.directoryId());
            boolean isCurrent = current.get(key) == s;
            if (!isCurrent || Duration.between(s.lastActivity(), now).compareTo(RETAIN_CURRENT) > 0) {
                it.remove();
                if (isCurrent) {
                    current.remove(key);
                }
            }
        }
    }
}
