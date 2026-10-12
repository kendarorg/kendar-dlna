package org.kendar.dlna.personal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.ResolvedPlaylist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.identity.Access;
import org.kendar.dlna.identity.IdentityService;
import org.kendar.dlna.identity.User;
import org.kendar.dlna.personal.UserSpace.History;
import org.kendar.dlna.personal.UserSpace.PlaylistData;
import org.kendar.dlna.personal.UserSpace.QueueState;
import org.kendar.dlna.personal.UserSpace.TrackList;
import org.kendar.dlna.shared.RootRef;

/**
 * The Personal context (plan sections 5 and 7): playlists, favorites, queue and history of every user, sharing,
 * copying, optimistic concurrency and the viewer filter. Results are JSON-ready maps.
 *
 * <p>Viewer filtering: whoever looks at a playlist (own, shared, system) sees only entries inside their allowed
 * roots, the others are counted in {@code unavailable}. The owner's own view keeps them (status
 * {@code no_access}, path only) so they survive a root being removed and come back with access. New entries must
 * come from the editor's allowed roots and exist in the catalog.
 */
public final class PersonalService {

    public static final int MAX_PLAYLISTS = 500;
    public static final int MAX_TRACKS = 20_000;
    public static final int MAX_QUEUE = 5_000;
    public static final int MAX_NAME = 200;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int COUNT_SECONDS = 30;
    public static final int REPORTS_PER_MINUTE = 30;
    public static final int DEFAULT_PAGE = 200;
    public static final int MAX_PAGE = 1000;
    public static final Duration IDLE_EVICTION = Duration.ofMinutes(15);
    public static final Duration HISTORY_DEBOUNCE = Duration.ofSeconds(5);

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private final IdentityService identity;
    private final Supplier<Snapshot> snapshot;
    private final Clock clock;
    private final Duration idle;
    private final ConcurrentHashMap<String, UserSpace> spaces = new ConcurrentHashMap<>();

    public PersonalService(IdentityService identity, Supplier<Snapshot> snapshot, Clock clock) {
        this(identity, snapshot, clock, IDLE_EVICTION);
    }

    public PersonalService(IdentityService identity, Supplier<Snapshot> snapshot, Clock clock, Duration idle) {
        this.identity = identity;
        this.snapshot = snapshot;
        this.clock = clock;
        this.idle = idle;
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    // ---- space management ----

    private <T> T with(String userId, Function<UserSpace, T> f) {
        while (true) {
            Instant now = clock.instant();
            UserSpace s = spaces.computeIfAbsent(userId, id -> new UserSpace(id, identity.dataDir(id), now));
            synchronized (s) {
                if (s.evicted) {
                    continue;
                }
                s.lastAccess = now;
                return f.apply(s);
            }
        }
    }

    private <T> T withOther(String userId, Function<UserSpace, T> f) {
        if (identity.user(userId).isEmpty()) {
            throw new PersonalException("not_found", "No such playlist");
        }
        return with(userId, f);
    }

    /** Number of user spaces currently held in memory. */
    public int loadedSpaces() {
        return spaces.size();
    }

    /** Writes pending history, evicts spaces idle for too long and forgets spaces of deleted users. */
    public void maintain() {
        Instant now = clock.instant();
        for (UserSpace s : new ArrayList<>(spaces.values())) {
            synchronized (s) {
                boolean gone = identity.user(s.userId).isEmpty();
                if (gone || Duration.between(s.lastAccess, now).compareTo(idle) >= 0) {
                    if (!gone) {
                        s.flushHistory(now);
                    }
                    s.evicted = true;
                    spaces.remove(s.userId, s);
                } else if (s.historyDirty() && Duration.between(s.history().lastFlush, now)
                        .compareTo(HISTORY_DEBOUNCE) >= 0) {
                    s.flushHistory(now);
                }
            }
        }
    }

    /** Writes everything pending (shutdown). */
    public void flushAll() {
        Instant now = clock.instant();
        for (UserSpace s : spaces.values()) {
            synchronized (s) {
                if (identity.user(s.userId).isPresent()) {
                    s.flushHistory(now);
                }
            }
        }
    }

    // ---- helpers ----

    private Instant now() {
        return clock.instant();
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > MAX_NAME || n.chars().anyMatch(Character::isISOControl)) {
            throw new PersonalException("invalid", "A name of 1 to " + MAX_NAME + " characters is required");
        }
        return n;
    }

    private static String cleanDescription(String d) {
        String s = d == null ? "" : d.strip();
        if (s.length() > MAX_DESCRIPTION) {
            throw new PersonalException("invalid", "The description is longer than " + MAX_DESCRIPTION);
        }
        return s;
    }

    private static RootRef parse(String path) {
        try {
            RootRef r = RootRef.parse(path);
            if (r.isRoot()) {
                throw new PersonalException("invalid", "Not a track path: " + path);
            }
            return r;
        } catch (IllegalArgumentException e) {
            throw new PersonalException("invalid", "Invalid path: " + path);
        }
    }

    private static List<String> cleanPaths(List<String> paths) {
        if (paths == null) {
            throw new PersonalException("invalid", "A list of track paths is required");
        }
        List<String> out = new ArrayList<>(paths.size());
        for (String p : paths) {
            if (p == null) {
                throw new PersonalException("invalid", "Null path");
            }
            out.add(parse(p).qualified());
        }
        return out;
    }

    /** New entries (those not already in {@code old}) must be inside the editor's roots and exist in the catalog. */
    private void checkNew(User editor, List<String> old, List<String> next) {
        Snapshot snap = snapshot.get();
        Set<String> known = new HashSet<>(old);
        for (String p : next) {
            if (known.contains(p)) {
                continue;
            }
            RootRef ref = parse(p);
            Access.requireRoot(editor, ref);
            if (snap.track(ref).isEmpty()) {
                throw new PersonalException("not_found", "No such track: " + p);
            }
        }
        if (next.size() > MAX_TRACKS) {
            throw new PersonalException("invalid", "At most " + MAX_TRACKS + " tracks");
        }
    }

    private Map<String, Object> trackJson(Snapshot snap, Track t) {
        var g = t.tags();
        boolean hasArt = snap.folder(t.parentRef()).map(org.kendar.dlna.catalog.Folder::hasFront).orElse(false)
                || t.embeddedArt();
        String art = hasArt ? "/api/v1/library/artwork?kind=track&path="
                + java.net.URLEncoder.encode(t.ref().qualified(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20")
                : null;
        return obj("path", t.ref().qualified(), "name", t.fileName(), "title", DisplayNames.trackTitle(t),
                "artist", g.artist(), "album", g.album(), "albumArtist", g.albumArtist(), "genre", g.genre(),
                "year", g.year(), "trackNo", g.trackNo(), "discNo", g.discNo(),
                "format", t.format().name().toLowerCase(Locale.ROOT), "durationMillis", t.durationMillis(),
                "addedAt", t.addedAt(), "art", art);
    }

    /** Result of rendering a path list for a viewer. */
    private record Rendered(List<Map<String, Object>> entries, int unavailable) {
    }

    private Rendered render(Snapshot snap, User viewer, List<String> paths, boolean owner) {
        List<Map<String, Object>> out = new ArrayList<>(paths.size());
        int unavailable = 0;
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            RootRef ref = null;
            try {
                ref = RootRef.parse(p);
            } catch (IllegalArgumentException ignored) {
                // stays null: reported as missing
            }
            if (ref != null && !viewer.mayAccess(ref.root())) {
                unavailable++;
                if (owner) {
                    out.add(obj("index", i, "path", p, "status", "no_access"));
                }
                continue;
            }
            Optional<Track> t = ref == null ? Optional.empty() : snap.track(ref);
            out.add(t.isPresent() ? obj("index", i, "path", p, "status", "ok", "track", trackJson(snap, t.get()))
                    : obj("index", i, "path", p, "status", "missing"));
        }
        return new Rendered(out, unavailable);
    }

    private static int offsetOf(Map<String, String> q) {
        return Math.max(0, intParam(q, "offset", 0));
    }

    private static int limitOf(Map<String, String> q) {
        return Math.min(MAX_PAGE, Math.max(1, intParam(q, "limit", DEFAULT_PAGE)));
    }

    private static int intParam(Map<String, String> q, String name, int def) {
        String v = q.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new PersonalException("invalid", "Not a number: " + name);
        }
    }

    private static <T> List<T> page(List<T> all, int offset, int limit) {
        return offset >= all.size() ? List.of() : all.subList(offset, Math.min(all.size(), offset + limit));
    }

    private Map<String, Object> summary(Snapshot snap, User viewer, UserSpace owner, PlaylistData p, boolean mine) {
        Rendered r = render(snap, viewer, p.tracks, mine);
        User ou = identity.user(owner.userId).orElse(null);
        return obj("id", p.id, "owner", ou == null ? null : obj("id", ou.id(), "displayName", ou.displayName()),
                "mine", mine, "name", p.name, "description", p.description, "shared", p.shared,
                "count", r.entries().size() - (mine ? r.unavailable() : 0), "unavailable", r.unavailable(),
                "version", p.version, "created", p.created.toString(), "updated", p.updated.toString());
    }

    private Map<String, Object> detail(Snapshot snap, User viewer, UserSpace owner, PlaylistData p, boolean mine,
            Map<String, String> q) {
        Rendered r = render(snap, viewer, p.tracks, mine);
        int off = offsetOf(q);
        int lim = limitOf(q);
        Map<String, Object> m = summary(snap, viewer, owner, p, mine);
        m.put("total", r.entries().size());
        m.put("offset", off);
        m.put("limit", lim);
        m.put("entries", page(r.entries(), off, lim));
        m.put("snapshot", snap.updateId());
        return m;
    }

    private static PlaylistData find(UserSpace s, String id) {
        PlaylistData p = validId(id) ? s.playlists().get(id) : null;
        if (p == null) {
            throw new PersonalException("not_found", "No such playlist");
        }
        return p;
    }

    private void touch(TrackList l) {
        l.version++;
        l.updated = now();
    }

    // ---- own playlists ----

    public Map<String, Object> playlists(User me) {
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            List<Object> out = new ArrayList<>();
            s.playlists().values().stream().sorted(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)))
                    .forEach(p -> out.add(summary(snap, me, s, p, true)));
            return obj("playlists", out, "snapshot", snap.updateId());
        });
    }

    public Map<String, Object> createPlaylist(User me, String name, String description, List<String> paths) {
        String n = cleanName(name);
        String d = cleanDescription(description);
        List<String> tracks = cleanPaths(paths == null ? List.of() : paths);
        checkNew(me, List.of(), tracks);
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            if (s.playlists().size() >= MAX_PLAYLISTS) {
                throw new PersonalException("too_many", "At most " + MAX_PLAYLISTS + " playlists");
            }
            return detail(snap, me, s, newPlaylist(s, n, d, tracks, null), true, Map.of());
        });
    }

    private PlaylistData newPlaylist(UserSpace s, String name, String description, List<String> tracks,
            Boolean shared) {
        PlaylistData p = new PlaylistData();
        p.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        p.name = name;
        p.description = description;
        p.tracks = new ArrayList<>(tracks);
        p.shared = shared != null && shared;
        p.created = now();
        p.updated = p.created;
        p.version = 1;
        s.playlists().put(p.id, p);
        s.save(p);
        return p;
    }

    public Map<String, Object> playlist(User me, String id, Map<String, String> q) {
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> detail(snap, me, s, find(s, id), true, q));
    }

    /** Edits name, description and/or the complete ordered track list (null = unchanged). */
    public Map<String, Object> updatePlaylist(User me, String id, Precondition pre, String name, String description,
            List<String> paths) {
        String n = name == null ? null : cleanName(name);
        String d = description == null ? null : cleanDescription(description);
        List<String> tracks = paths == null ? null : cleanPaths(paths);
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            PlaylistData p = find(s, id);
            pre.check(p.version);
            if (tracks != null) {
                checkNew(me, p.tracks, tracks);
                p.tracks = new ArrayList<>(tracks);
            }
            if (n != null) {
                p.name = n;
            }
            if (d != null) {
                p.description = d;
            }
            touch(p);
            s.save(p);
            return detail(snap, me, s, p, true, Map.of());
        });
    }

    /** Appends (or inserts at {@code position}) tracks from the editor's allowed roots. */
    public Map<String, Object> addTracks(User me, String id, Precondition pre, List<String> paths, Integer position) {
        List<String> add = cleanPaths(paths);
        if (add.isEmpty()) {
            throw new PersonalException("invalid", "No tracks given");
        }
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            PlaylistData p = find(s, id);
            pre.check(p.version);
            checkNew(me, List.of(), add);
            List<String> next = new ArrayList<>(p.tracks);
            int at = position == null ? next.size() : Math.max(0, Math.min(next.size(), position));
            next.addAll(at, add);
            if (next.size() > MAX_TRACKS) {
                throw new PersonalException("invalid", "At most " + MAX_TRACKS + " tracks");
            }
            p.tracks = next;
            touch(p);
            s.save(p);
            return detail(snap, me, s, p, true, Map.of());
        });
    }

    public void deletePlaylist(User me, String id, Precondition pre) {
        with(me.id(), s -> {
            PlaylistData p = find(s, id);
            pre.check(p.version);
            s.remove(id);
            return null;
        });
    }

    public Map<String, Object> share(User me, String id, boolean shared, Precondition pre) {
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            PlaylistData p = find(s, id);
            pre.check(p.version);
            if (p.shared != shared) {
                p.shared = shared;
                touch(p);
                s.save(p);
            }
            return detail(snap, me, s, p, true, Map.of());
        });
    }

    /** Replaces the entry at {@code index} (which must currently hold {@code expectedPath}) by an existing track. */
    public Map<String, Object> fixPath(User me, String id, Precondition pre, int index, String newPath) {
        String np = parse(newPath).qualified();
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            PlaylistData p = find(s, id);
            pre.check(p.version);
            if (index < 0 || index >= p.tracks.size()) {
                throw new PersonalException("invalid", "Index out of range: " + index);
            }
            checkNew(me, List.of(), List.of(np));
            p.tracks = new ArrayList<>(p.tracks);
            p.tracks.set(index, np);
            touch(p);
            s.save(p);
            return detail(snap, me, s, p, true, Map.of());
        });
    }

    /** Copies an own playlist (all entries) or a shared one of another user (accessible entries only). */
    public Map<String, Object> copyPlaylist(User me, String ownerId, String id, String newName) {
        Snapshot snap = snapshot.get();
        boolean mine = ownerId == null || ownerId.equals(me.id());
        String ownerKey = mine ? me.id() : ownerId;
        PlaylistData src = withOther(ownerKey, s -> {
            PlaylistData p = find(s, id);
            if (!mine && !p.shared) {
                throw new PersonalException("not_found", "No such playlist");
            }
            return snapshotOf(p);
        });
        List<String> tracks = mine ? src.tracks : accessible(snap, me, src.tracks);
        return storeCopy(me, snap, newName == null ? src.name + " (copy)" : newName, src.description, tracks);
    }

    /** Copies a system playlist; only the entries the copier can access. */
    public Map<String, Object> copySystem(User me, String key, String newName) {
        Snapshot snap = snapshot.get();
        ResolvedPlaylist rp = systemPlaylist(snap, key);
        List<String> tracks = accessible(snap, me, rp.entries().stream().map(ResolvedPlaylist.Entry::path).toList());
        return storeCopy(me, snap, newName == null ? rp.name() + " (copy)" : newName, "", tracks);
    }

    private Map<String, Object> storeCopy(User me, Snapshot snap, String name, String description,
            List<String> tracks) {
        String n = cleanName(name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name);
        return with(me.id(), s -> {
            if (s.playlists().size() >= MAX_PLAYLISTS) {
                throw new PersonalException("too_many", "At most " + MAX_PLAYLISTS + " playlists");
            }
            return detail(snap, me, s, newPlaylist(s, n, description, tracks, false), true, Map.of());
        });
    }

    private static PlaylistData snapshotOf(PlaylistData p) {
        PlaylistData c = new PlaylistData();
        c.id = p.id;
        c.name = p.name;
        c.description = p.description;
        c.shared = p.shared;
        c.tracks = new ArrayList<>(p.tracks);
        c.version = p.version;
        c.created = p.created;
        c.updated = p.updated;
        return c;
    }

    private static List<String> accessible(Snapshot snap, User viewer, List<String> paths) {
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            try {
                RootRef r = RootRef.parse(p);
                if (viewer.mayAccess(r.root()) && snap.track(r).isPresent()) {
                    out.add(r.qualified());
                }
            } catch (IllegalArgumentException ignored) {
                // skip malformed
            }
        }
        return out;
    }

    // ---- favorites ----

    private Map<String, Object> favoritesJson(Snapshot snap, User me, TrackList f, Map<String, String> q) {
        Rendered r = render(snap, me, f.tracks, true);
        int off = offsetOf(q);
        int lim = limitOf(q);
        return obj("version", f.version, "updated", f.updated.toString(), "total", r.entries().size(),
                "unavailable", r.unavailable(), "offset", off, "limit", lim, "entries", page(r.entries(), off, lim),
                "snapshot", snap.updateId());
    }

    public Map<String, Object> favorites(User me, Map<String, String> q) {
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> favoritesJson(snap, me, s.favorites(), q));
    }

    /** Adds tracks (already present ones are skipped) at the end. */
    public Map<String, Object> addFavorites(User me, Precondition pre, List<String> paths) {
        List<String> add = cleanPaths(paths);
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            TrackList f = s.favorites();
            pre.check(f.version);
            Set<String> have = new LinkedHashSet<>(f.tracks);
            List<String> fresh = add.stream().filter(p -> !have.contains(p)).distinct().toList();
            checkNew(me, f.tracks, fresh);
            if (!fresh.isEmpty()) {
                f.tracks = new ArrayList<>(f.tracks);
                f.tracks.addAll(fresh);
                touch(f);
                s.saveFavorites();
            }
            return favoritesJson(snap, me, f, Map.of());
        });
    }

    public Map<String, Object> removeFavorites(User me, Precondition pre, List<String> paths) {
        Set<String> drop = new HashSet<>(cleanPaths(paths));
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            TrackList f = s.favorites();
            pre.check(f.version);
            List<String> next = f.tracks.stream().filter(p -> !drop.contains(p)).toList();
            if (next.size() != f.tracks.size()) {
                f.tracks = new ArrayList<>(next);
                touch(f);
                s.saveFavorites();
            }
            return favoritesJson(snap, me, f, Map.of());
        });
    }

    /** Replaces the complete ordered list (reorder). */
    public Map<String, Object> setFavorites(User me, Precondition pre, List<String> paths) {
        List<String> next = cleanPaths(paths);
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            TrackList f = s.favorites();
            pre.check(f.version);
            checkNew(me, f.tracks, next);
            f.tracks = new ArrayList<>(next);
            touch(f);
            s.saveFavorites();
            return favoritesJson(snap, me, f, Map.of());
        });
    }

    // ---- queue ----

    /** The input of {@link #putQueue}. */
    public record QueueInput(List<String> items, int index, double positionSeconds, boolean shuffle, String repeat,
            com.fasterxml.jackson.databind.JsonNode source, String deviceId) {
    }

    public Map<String, Object> queue(User me) {
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            QueueState q = s.queue();
            List<String> kept = new ArrayList<>();
            int newIndex = 0;
            boolean currentHidden = false;
            for (int i = 0; i < q.items.size(); i++) {
                String p = q.items.get(i);
                boolean ok;
                try {
                    ok = me.mayAccess(RootRef.parse(p).root());
                } catch (IllegalArgumentException e) {
                    ok = false;
                }
                if (i == q.index) {
                    newIndex = kept.size();
                    currentHidden = !ok;
                }
                if (ok) {
                    kept.add(p);
                }
            }
            if (kept.isEmpty()) {
                newIndex = 0;
            } else if (newIndex >= kept.size()) {
                newIndex = kept.size() - 1;
                currentHidden = true;
            }
            Rendered r = render(snap, me, kept, false);
            List<Map<String, Object>> items = new ArrayList<>(r.entries());
            for (int i = 0; i < items.size(); i++) {
                items.get(i).put("index", i);
            }
            return obj("version", q.version, "items", items, "index", newIndex,
                    "positionSeconds", currentHidden ? 0 : q.positionSeconds, "shuffle", q.shuffle,
                    "repeat", q.repeat, "source", q.source, "deviceId", q.deviceId, "updated", q.updated.toString(),
                    "unavailable", q.items.size() - kept.size(), "snapshot", snap.updateId());
        });
    }

    /** Replaces the stored queue (last writer wins unless a version precondition is given). */
    public Map<String, Object> putQueue(User me, Precondition pre, QueueInput in) {
        List<String> items = cleanPaths(in.items());
        if (items.size() > MAX_QUEUE) {
            throw new PersonalException("invalid", "At most " + MAX_QUEUE + " queue items");
        }
        for (String p : items) {
            Access.requireRoot(me, parse(p));
        }
        String repeat = in.repeat() == null ? "off" : in.repeat();
        if (!Set.of("off", "all", "one").contains(repeat)) {
            throw new PersonalException("invalid", "repeat must be off, all or one");
        }
        if (in.source() != null && in.source().toString().length() > 2000) {
            throw new PersonalException("invalid", "source is too large");
        }
        String device = in.deviceId() == null ? null
                : in.deviceId().substring(0, Math.min(100, in.deviceId().length()));
        int index = items.isEmpty() ? 0 : Math.max(0, Math.min(items.size() - 1, in.index()));
        double pos = Double.isFinite(in.positionSeconds()) ? Math.max(0, in.positionSeconds()) : 0;
        with(me.id(), s -> {
            QueueState q = s.queue();
            pre.check(q.version);
            q.items = new ArrayList<>(items);
            q.index = index;
            q.positionSeconds = pos;
            q.shuffle = in.shuffle();
            q.repeat = repeat;
            q.source = in.source() == null || in.source().isNull() ? null : in.source();
            q.deviceId = device;
            q.updated = now();
            q.version++;
            s.saveQueue();
            return null;
        });
        return queue(me);
    }

    // ---- history ----

    /**
     * Reports a play. It counts after {@value #COUNT_SECONDS} s or 50 % of the track. Rate limited per user.
     */
    public Map<String, Object> reportPlay(User me, String path, double playedSeconds, double durationSeconds) {
        return reportPlay(me, path, playedSeconds, durationSeconds, false);
    }

    /** {@code force}: the play counts whatever its length (the user paused or stopped it on purpose). */
    public Map<String, Object> reportPlay(User me, String path, double playedSeconds, double durationSeconds,
            boolean force) {
        RootRef ref = parse(path);
        Access.requireRoot(me, ref);
        Snapshot snap = snapshot.get();
        double dur = durationSeconds > 0 ? durationSeconds
                : snap.track(ref).map(t -> t.durationMillis() / 1000.0).orElse(0.0);
        return with(me.id(), s -> {
            Instant now = now();
            while (!s.reports.isEmpty() && Duration.between(s.reports.peekFirst(), now).toSeconds() >= 60) {
                s.reports.pollFirst();
            }
            if (s.reports.size() >= REPORTS_PER_MINUTE) {
                long wait = Math.max(1, 60 - Duration.between(s.reports.peekFirst(), now).toSeconds());
                throw new PersonalException("rate_limited", "Too many play reports, try again in " + wait + " s",
                        Map.of("retryAfterSeconds", wait));
            }
            s.reports.addLast(now);
            boolean counts = (force && playedSeconds > 0) || playedSeconds >= COUNT_SECONDS
                    || (dur > 0 && playedSeconds >= dur * 0.5);
            if (!counts) {
                return obj("counted", false);
            }
            History h = s.history();
            String key = ref.qualified();
            h.recent.addLast(Map.entry(key, now.toEpochMilli()));
            while (h.recent.size() > UserSpace.HISTORY_SIZE) {
                h.recent.pollFirst();
            }
            long[] c = h.counts.computeIfAbsent(key, k -> new long[2]);
            c[0]++;
            c[1] = now.toEpochMilli();
            h.dirty = true;
            if (Duration.between(h.lastFlush, now).compareTo(HISTORY_DEBOUNCE) >= 0) {
                s.flushHistory(now);
            }
            return obj("counted", true, "playCount", c[0]);
        });
    }

    /** Removes every entry of a track from Recently played (the play count is kept). */
    public void forgetRecent(User me, String path) {
        RootRef ref = parse(path);
        with(me.id(), s -> {
            History h = s.history();
            if (h.recent.removeIf(e -> e.getKey().equals(ref.qualified()))) {
                h.dirty = true;
                s.flushHistory(now());
            }
            return null;
        });
    }

    /** Resets the play count of a track: it leaves Most played (its Recently played entries are kept). */
    public void forgetCount(User me, String path) {
        RootRef ref = parse(path);
        with(me.id(), s -> {
            History h = s.history();
            if (h.counts.remove(ref.qualified()) != null) {
                h.dirty = true;
                s.flushHistory(now());
            }
            return null;
        });
    }

    /** Most recent plays first; {@code distinct} (default true) lists each track once. */
    public Map<String, Object> recentlyPlayed(User me, Map<String, String> q) {
        boolean distinct = !"false".equalsIgnoreCase(q.get("distinct"));
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            List<Map.Entry<String, Long>> plays = new ArrayList<>(s.history().recent);
            java.util.Collections.reverse(plays);
            Set<String> seen = new HashSet<>();
            List<Map.Entry<String, Long>> rows = new ArrayList<>();
            for (var e : plays) {
                if (!distinct || seen.add(e.getKey())) {
                    rows.add(e);
                }
            }
            return historyPage(snap, me, rows, q, null);
        });
    }

    public Map<String, Object> mostPlayed(User me, Map<String, String> q) {
        Snapshot snap = snapshot.get();
        return with(me.id(), s -> {
            Map<String, long[]> counts = s.history().counts;
            List<Map.Entry<String, Long>> rows = counts.entrySet().stream()
                    .sorted((a, b) -> a.getValue()[0] != b.getValue()[0]
                            ? Long.compare(b.getValue()[0], a.getValue()[0])
                            : Long.compare(b.getValue()[1], a.getValue()[1]))
                    .map(e -> Map.entry(e.getKey(), e.getValue()[1])).toList();
            return historyPage(snap, me, rows, q, counts);
        });
    }

    private Map<String, Object> historyPage(Snapshot snap, User me, List<Map.Entry<String, Long>> rows,
            Map<String, String> q, Map<String, long[]> counts) {
        List<Map<String, Object>> visible = new ArrayList<>();
        int unavailable = 0;
        for (var e : rows) {
            String p = e.getKey();
            RootRef ref;
            try {
                ref = RootRef.parse(p);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (!me.mayAccess(ref.root())) {
                unavailable++;
                continue;
            }
            Optional<Track> t = snap.track(ref);
            if (t.isEmpty()) {
                continue;
            }
            Map<String, Object> m = obj("path", p, "status", "ok", "playedAt", Instant.ofEpochMilli(e.getValue()).toString(),
                    "track", trackJson(snap, t.get()));
            if (counts != null) {
                m.put("playCount", counts.get(p)[0]);
            }
            visible.add(m);
        }
        int off = offsetOf(q);
        int lim = limitOf(q);
        return obj("total", visible.size(), "unavailable", unavailable, "offset", off, "limit", lim,
                "entries", page(visible, off, lim), "snapshot", snap.updateId());
    }

    // ---- shared and system playlists ----

    /** Shared playlists of all users (with their owners), each viewed through the viewer's roots. */
    public Map<String, Object> sharedPlaylists(User viewer) {
        Snapshot snap = snapshot.get();
        List<Object> out = new ArrayList<>();
        for (User u : identity.users()) {
            with(u.id(), s -> {
                s.playlists().values().stream().filter(p -> p.shared)
                        .sorted(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)))
                        .forEach(p -> out.add(summary(snap, viewer, s, p, u.id().equals(viewer.id()))));
                return null;
            });
        }
        return obj("playlists", out, "snapshot", snap.updateId());
    }

    public Map<String, Object> sharedPlaylist(User viewer, String ownerId, String id, Map<String, String> q) {
        Snapshot snap = snapshot.get();
        boolean mine = ownerId.equals(viewer.id());
        return withOther(ownerId, s -> {
            PlaylistData p = find(s, id);
            if (!p.shared && !mine) {
                throw new PersonalException("not_found", "No such playlist");
            }
            return detail(snap, viewer, s, p, mine, q);
        });
    }

    private static ResolvedPlaylist systemPlaylist(Snapshot snap, String key) {
        return snap.playlists().stream().filter(p -> p.playlist().key().equals(key)).findFirst()
                .orElseThrow(() -> new PersonalException("not_found", "No such system playlist"));
    }

    public Map<String, Object> systemPlaylists(User viewer) {
        Snapshot snap = snapshot.get();
        List<Object> out = new ArrayList<>();
        for (ResolvedPlaylist rp : snap.playlists()) {
            Rendered r = render(snap, viewer, rp.entries().stream().map(ResolvedPlaylist.Entry::path).toList(),
                    false);
            out.add(obj("key", rp.playlist().key(), "name", rp.name(), "system", true, "count", r.entries().size(),
                    "unavailable", r.unavailable()));
        }
        return obj("playlists", out, "snapshot", snap.updateId());
    }

    public Map<String, Object> systemPlaylist(User viewer, String key, Map<String, String> q) {
        Snapshot snap = snapshot.get();
        ResolvedPlaylist rp = systemPlaylist(snap, key);
        Rendered r = render(snap, viewer, rp.entries().stream().map(ResolvedPlaylist.Entry::path).toList(), false);
        int off = offsetOf(q);
        int lim = limitOf(q);
        return obj("key", key, "name", rp.name(), "system", true, "count", r.entries().size(),
                "unavailable", r.unavailable(), "total", r.entries().size(), "offset", off, "limit", lim,
                "entries", page(r.entries(), off, lim), "snapshot", snap.updateId());
    }

    // ---- moderation (admin: shared playlists only) ----

    public Map<String, Object> adminSharedPlaylists() {
        Snapshot snap = snapshot.get();
        List<Object> out = new ArrayList<>();
        for (User u : identity.users()) {
            with(u.id(), s -> {
                s.playlists().values().stream().filter(p -> p.shared).forEach(p -> out.add(obj("id", p.id,
                        "owner", obj("id", u.id(), "username", u.username(), "displayName", u.displayName()),
                        "name", p.name, "description", p.description, "count", p.tracks.size(),
                        "version", p.version, "updated", p.updated.toString())));
                return null;
            });
        }
        return obj("playlists", out, "snapshot", snap.updateId());
    }

    /** Private playlists look like they do not exist. */
    private PlaylistData shared(UserSpace s, String id) {
        PlaylistData p = find(s, id);
        if (!p.shared) {
            throw new PersonalException("not_found", "No such shared playlist");
        }
        return p;
    }

    public void adminUnshare(String ownerId, String id) {
        withOther(ownerId, s -> {
            PlaylistData p = shared(s, id);
            p.shared = false;
            touch(p);
            s.save(p);
            return null;
        });
    }

    public void adminDelete(String ownerId, String id) {
        withOther(ownerId, s -> {
            shared(s, id);
            s.remove(id);
            return null;
        });
    }
}
