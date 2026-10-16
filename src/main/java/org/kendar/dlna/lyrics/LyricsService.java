package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.curation.AtomicFiles;
import org.kendar.dlna.curation.CurationException;
import org.kendar.dlna.curation.LibraryFiles;
import org.kendar.dlna.shared.RootRef;

/**
 * Lyrics for the tracks of the library: what a track already has (sidecar, tag, karaoke events), a lookup at the
 * configured provider (off by default), saving a result as a sidecar {@code .lrc} (synced) or {@code .txt}, and a
 * folder job that looks up every track of a directory tree with a throttle and keeps the results for review.
 * Nothing is written without an explicit save or apply.
 */
public final class LyricsService {

    private static final Logger LOG = Logger.getLogger(LyricsService.class.getName());
    static final int MAX_REVIEW = 5000;

    /** The outcome of the lookup of one track in a folder job. */
    public enum Outcome {
        /** Lyrics exist already and were not looked up. */
        HAS_LYRICS,
        /** One clear candidate, ready to apply. */
        MATCHED,
        /** Several candidates that differ: the first one is proposed. */
        AMBIGUOUS,
        NOT_FOUND,
        FAILED
    }

    /** One reviewed track. {@code candidate} is the proposed lyrics (null when none). */
    public record Item(String path, String title, Outcome outcome, Lyrics candidate, String error, boolean applied) {
    }

    /** State of the folder job; a finished job stays readable until the next one starts. */
    public record Status(boolean running, String directory, int total, int done, List<Item> items, Instant startedAt,
            Instant finishedAt, String error) {
    }

    private final Supplier<Snapshot> catalog;
    private final LibraryFiles files;
    private final LyricsProvider provider;
    private final long minIntervalMillis;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lyrics-job");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object throttle = new Object();
    private long lastCall;
    private volatile Status status = new Status(false, null, 0, 0, List.of(), null, null, null);
    private final Map<String, Item> review = new LinkedHashMap<>();

    /** @param provider the online provider, or null when lookups are off */
    public LyricsService(Supplier<Snapshot> catalog, LibraryFiles files, LyricsProvider provider,
            long minIntervalMillis) {
        this.catalog = catalog;
        this.files = files;
        this.provider = provider;
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    /** The id of the configured provider, or null when lookups are off. */
    public String providerId() {
        return provider == null ? null : provider.id();
    }

    // ---- one track ----

    /** What the track already has. */
    public Optional<Lyrics> local(RootRef ref) {
        Track t = track(ref);
        Path file = files.resolve(t.fileRef());
        if (file == null || !Files.isRegularFile(file)) {
            throw new CurationException(CurationException.NOT_FOUND, "File is missing on disk: " + ref);
        }
        return LocalLyrics.read(file, t.format());
    }

    /** The provider's candidates for a track, best first. */
    public List<Lyrics> lookup(RootRef ref) {
        if (provider == null) {
            throw new CurationException(CurationException.INVALID,
                    "No lyrics provider is configured (lyrics.provider in config.json)");
        }
        Track t = track(ref);
        try {
            pause();
            return provider.find(query(t));
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "The lyrics service did not answer: " + e.getMessage(), null, e);
        }
    }

    // ---- one track, as the answers arrive ----

    /** What one provider has said so far: {@code pending}, {@code done} (with how many it found) or {@code failed}. */
    public record ProviderState(String id, String state, int count, String error) {
    }

    /** What a lookup has to show now: the providers' states and the candidates that arrived after the first {@code after}. */
    public record LookupView(String id, String path, boolean running, List<ProviderState> providers,
            List<Lyrics> candidates, int total, String error) {
    }

    /** A lookup running in the background: candidates are only ever appended, so an index never moves. */
    public static final class Lookup {
        private final String id;
        private final String path;
        private final Map<String, ProviderState> states = new LinkedHashMap<>();
        private final List<Lyrics> candidates = new ArrayList<>();
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        private boolean running = true;
        private String error;

        Lookup(String id, String path, List<String> providerIds) {
            this.id = id;
            this.path = path;
            providerIds.forEach(p -> states.put(p, new ProviderState(p, "pending", 0, null)));
        }

        synchronized void answered(String provider, List<Lyrics> found, String err) {
            int added = 0;
            for (Lyrics l : found) {
                if (seen.add(l.text().strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "))) {
                    candidates.add(l);
                    added++;
                }
            }
            states.put(provider, new ProviderState(provider, err == null ? "done" : "failed", added, err));
        }

        synchronized void finished(String err) {
            running = false;
            error = err;
        }

        public synchronized LookupView view(int after) {
            int from = Math.max(0, Math.min(after, candidates.size()));
            return new LookupView(id, path, running, List.copyOf(states.values()),
                    List.copyOf(candidates.subList(from, candidates.size())), candidates.size(), error);
        }
    }

    private final Map<String, Lookup> lookups = new LinkedHashMap<>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lookup> eldest) {
            return size() > 20;
        }
    };

    /**
     * Starts asking the provider(s) for a track in the background and returns at once; {@link Lookup#view} shows what
     * has arrived, provider by provider.
     */
    public Lookup startLookup(RootRef ref) {
        if (provider == null) {
            throw new CurationException(CurationException.INVALID,
                    "No lyrics provider is configured (lyrics.provider in config.json)");
        }
        Track t = track(ref);
        LyricsProvider.Query query = query(t);
        Lookup lookup = new Lookup(java.util.UUID.randomUUID().toString(), ref.qualified(), provider.ids());
        synchronized (lookups) {
            lookups.put(lookup.id, lookup);
        }
        Thread.ofVirtual().name("lyrics-lookup").start(() -> {
            String err = null;
            try {
                pause();
                provider.find(query, lookup::answered);
            } catch (IOException e) {
                err = "The lyrics service did not answer: " + e.getMessage();
            } catch (RuntimeException e) {
                LOG.warning("Lyrics lookup failed: " + e);
                err = "The lyrics lookup failed: " + e.getMessage();
            } finally {
                lookup.finished(err);
            }
        });
        return lookup;
    }

    public Optional<Lookup> lookupJob(String id) {
        synchronized (lookups) {
            return Optional.ofNullable(lookups.get(id));
        }
    }

    /** Writes the lyrics next to the audio file: {@code name.lrc} when synced, else {@code name.txt}. */
    public String save(RootRef ref, Lyrics lyrics) {
        Track t = track(ref);
        if (lyrics == null || lyrics.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "No lyrics to save");
        }
        Path audio = files.resolve(t.fileRef());
        if (audio == null || !Files.isRegularFile(audio)) {
            throw new CurationException(CurationException.NOT_FOUND, "File is missing on disk: " + ref);
        }
        Path target = LocalLyrics.sidecar(audio, lyrics.synced() ? "lrc" : "txt");
        try {
            AtomicFiles.write(target, lyrics.toLrc().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot write " + target.getFileName() + ": " + e.getMessage(), null, e);
        }
        return target.getFileName().toString();
    }

    private Track track(RootRef ref) {
        return catalog.get().track(ref)
                .orElseThrow(() -> new CurationException(CurationException.NOT_FOUND, "Unknown track: " + ref));
    }

    private static LyricsProvider.Query query(Track t) {
        String artist = t.tags().artist() != null ? t.tags().artist() : t.tags().albumArtist();
        String title = t.tags().title() != null ? t.tags().title() : DisplayNames.trackTitle(t);
        return new LyricsProvider.Query(artist, title, t.tags().album(), (int) (t.durationMillis() / 1000));
    }

    /** At most one provider call per {@code minIntervalMillis}. */
    private void pause() {
        synchronized (throttle) {
            long wait = lastCall + minIntervalMillis - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastCall = System.currentTimeMillis();
        }
    }

    // ---- a folder tree ----

    public Status status() {
        return status;
    }

    /**
     * Starts looking up the lyrics of every whole-file track below {@code dir}. Tracks that have lyrics are
     * reported as such unless {@code replaceExisting}.
     */
    public Status startFolder(RootRef dir, boolean replaceExisting) {
        if (provider == null) {
            throw new CurationException(CurationException.INVALID,
                    "No lyrics provider is configured (lyrics.provider in config.json)");
        }
        Snapshot snap = catalog.get();
        if (snap.folder(dir).isEmpty()) {
            throw new CurationException(CurationException.NOT_FOUND, "Unknown directory: " + dir);
        }
        List<Track> tracks = snap.subtreeTracks(dir).stream().filter(t -> !t.isSegment()).toList();
        if (tracks.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "No track below " + dir);
        }
        if (!running.compareAndSet(false, true)) {
            throw new CurationException(CurationException.CONFLICT, "A lyrics lookup is already running");
        }
        synchronized (review) {
            review.clear();
        }
        Instant started = Instant.now();
        status = new Status(true, dir.qualified(), tracks.size(), 0, List.of(), started, null, null);
        pool.execute(() -> runFolder(dir, tracks, replaceExisting, started));
        return status;
    }

    private void runFolder(RootRef dir, List<Track> tracks, boolean replaceExisting, Instant started) {
        int done = 0;
        String fatal = null;
        try {
            for (Track t : tracks) {
                Item item = lookupOne(t, replaceExisting);
                synchronized (review) {
                    if (review.size() < MAX_REVIEW) {
                        review.put(item.path(), item);
                    }
                }
                done++;
                publish(dir, tracks.size(), done, started, true, null);
            }
        } catch (RuntimeException e) {
            LOG.warning("Lyrics job failed: " + e);
            fatal = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            publish(dir, tracks.size(), done, started, false, fatal);
            running.set(false);
        }
    }

    private void publish(RootRef dir, int total, int done, Instant started, boolean live, String error) {
        List<Item> items;
        synchronized (review) {
            items = List.copyOf(review.values());
        }
        status = new Status(live, dir.qualified(), total, done, items, started, live ? null : Instant.now(), error);
    }

    private Item lookupOne(Track t, boolean replaceExisting) {
        String path = t.ref().qualified();
        String title = DisplayNames.trackTitle(t);
        try {
            Path file = files.resolve(t.fileRef());
            if (file != null && !replaceExisting && LocalLyrics.read(file, t.format()).isPresent()) {
                return new Item(path, title, Outcome.HAS_LYRICS, null, null, false);
            }
            pause();
            List<Lyrics> found = provider.find(query(t));
            if (found.isEmpty()) {
                return new Item(path, title, Outcome.NOT_FOUND, null, null, false);
            }
            boolean ambiguous = found.size() > 1 && found.stream().map(Lyrics::text).distinct().count() > 1;
            return new Item(path, title, ambiguous ? Outcome.AMBIGUOUS : Outcome.MATCHED,
                    found.get(0), null, false);
        } catch (IOException | RuntimeException e) {
            return new Item(path, title, Outcome.FAILED, null, e.getMessage(), false);
        }
    }

    /**
     * Saves the proposed lyrics of the chosen reviewed tracks as sidecar files.
     *
     * @return the paths written
     */
    public List<String> applyFolder(List<String> paths) {
        List<String> written = new ArrayList<>();
        for (String p : paths) {
            Item item;
            synchronized (review) {
                item = review.get(p);
            }
            if (item == null || item.candidate() == null || item.applied()) {
                continue;
            }
            save(RootRef.parse(p), item.candidate());
            synchronized (review) {
                review.put(p, new Item(item.path(), item.title(), item.outcome(), item.candidate(), null, true));
            }
            written.add(p);
        }
        Status cur = status;
        List<Item> items;
        synchronized (review) {
            items = List.copyOf(review.values());
        }
        status = new Status(cur.running(), cur.directory(), cur.total(), cur.done(), items, cur.startedAt(),
                cur.finishedAt(), cur.error());
        return written;
    }
}
