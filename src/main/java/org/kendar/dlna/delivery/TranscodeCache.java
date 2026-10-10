package org.kendar.dlna.delivery;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Disk cache of finished transcodes in the data directory: LRU with a size cap and a time to live (counted from
 * the last use). Entries are written to a temporary file and renamed, so a reader never sees a partial entry;
 * concurrent requests for the same key wait for one producer. Each entry remembers which source version it was made
 * from, so entries of changed files can be dropped when the catalog changes.
 */
public final class TranscodeCache {

    private static final Logger LOG = Logger.getLogger(TranscodeCache.class.getName());
    private static final String DATA = ".bin";
    private static final String META = ".meta";

    /** The source an entry was made from: library location, file version and requested form. */
    public record Source(String ref, long mtime, long size, String variant) {
    }

    /** Writes the entry; must stop (by throwing) when {@code cancelled} turns true. */
    @FunctionalInterface
    public interface Producer {
        void produce(OutputStream out, BooleanSupplier cancelled) throws IOException;
    }

    private record Entry(String key, Source source, long bytes, long lastUsed) {
    }

    private final Path dir;
    private final long maxBytes;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);   // access order = LRU
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private long totalBytes;

    public TranscodeCache(Path dir, long maxBytes, Duration ttl, Clock clock) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.ttl = ttl;
        this.clock = clock;
        load();
    }

    /** A stable file-name-safe key for the given identifying parts. */
    public static String key(Object... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Object p : parts) {
                md.update(String.valueOf(p).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest(), 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void load() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warning("Cannot create the transcode cache directory " + dir + ": " + e);
            return;
        }
        List<Entry> found = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    Files.deleteIfExists(p);
                } else if (name.endsWith(DATA)) {
                    String key = name.substring(0, name.length() - DATA.length());
                    Source src = readMeta(key);
                    if (src == null) {
                        Files.deleteIfExists(p);
                    } else {
                        found.add(new Entry(key, src, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("Cannot scan the transcode cache " + dir + ": " + e);
        }
        found.sort((a, b) -> Long.compare(a.lastUsed(), b.lastUsed()));
        synchronized (this) {
            for (Entry e : found) {
                entries.put(e.key(), e);
                totalBytes += e.bytes();
            }
            enforce(null);
        }
    }

    private Path data(String key) {
        return dir.resolve(key + DATA);
    }

    private Path meta(String key) {
        return dir.resolve(key + META);
    }

    private Source readMeta(String key) {
        Path m = meta(key);
        if (!Files.isRegularFile(m)) {
            return null;
        }
        try (Reader r = Files.newBufferedReader(m, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            return new Source(p.getProperty("ref", ""), Long.parseLong(p.getProperty("mtime", "0")),
                    Long.parseLong(p.getProperty("size", "0")), p.getProperty("variant", ""));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeMeta(String key, Source s) throws IOException {
        Properties p = new Properties();
        p.setProperty("ref", s.ref());
        p.setProperty("mtime", Long.toString(s.mtime()));
        p.setProperty("size", Long.toString(s.size()));
        p.setProperty("variant", s.variant());
        try (Writer w = Files.newBufferedWriter(meta(key), StandardCharsets.UTF_8)) {
            p.store(w, null);
        }
    }

    /** The cached file of a key (counts as a use), or empty. */
    public synchronized Optional<Path> get(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return Optional.empty();
        }
        Path file = data(key);
        if (!Files.isRegularFile(file)) {
            drop(e);
            return Optional.empty();
        }
        long now = clock.millis();
        if (expired(e, now)) {
            drop(e);
            return Optional.empty();
        }
        entries.put(key, new Entry(key, e.source(), e.bytes(), now));
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(now));
        } catch (IOException ex) {
            // the in-memory time still counts
        }
        return Optional.of(file);
    }

    public synchronized boolean contains(String key) {
        Entry e = entries.get(key);
        return e != null && !expired(e, clock.millis()) && Files.isRegularFile(data(key));
    }

    private boolean expired(Entry e, long now) {
        return now - e.lastUsed() > ttl.toMillis();
    }

    /**
     * The cached file of a key, produced first when missing. Concurrent callers for one key wait for a single
     * producer. A failed or cancelled production leaves nothing behind.
     */
    public Path getOrCreate(String key, Source source, Producer producer, BooleanSupplier cancelled)
            throws IOException {
        Optional<Path> hit = get(key);
        if (hit.isPresent()) {
            return hit.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for " + key);
        }
        try {
            hit = get(key);
            if (hit.isPresent()) {
                return hit.get();
            }
            Files.createDirectories(dir);
            Path tmp = dir.resolve(key + "." + Long.toHexString(System.nanoTime()) + ".tmp");
            try {
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
                    producer.produce(out, cancelled);
                }
                if (cancelled.getAsBoolean()) {
                    throw new InterruptedIOException("Cancelled " + key);
                }
                long bytes = Files.size(tmp);
                writeMeta(key, source);
                move(tmp, data(key));
                synchronized (this) {
                    Entry old = entries.remove(key);
                    if (old != null) {
                        totalBytes -= old.bytes();
                    }
                    entries.put(key, new Entry(key, source, bytes, clock.millis()));
                    totalBytes += bytes;
                    enforce(key);
                }
                return data(key);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } finally {
            lock.unlock();
            locks.remove(key, lock);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes the entries whose source matches. @return how many were removed */
    public synchronized int invalidate(Predicate<Source> stale) {
        List<Entry> doomed = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (stale.test(e.source())) {
                doomed.add(e);
            }
        }
        doomed.forEach(this::drop);
        return doomed.size();
    }

    /** Removes one entry (e.g. a cached file whose size is wrong). */
    public synchronized void remove(String key) {
        Entry e = entries.get(key);
        if (e != null) {
            drop(e);
        }
    }

    /** Drops expired entries and evicts least-recently-used ones until the cache fits its cap. */
    public synchronized void sweep() {
        enforce(null);
    }

    private void enforce(String protectedKey) {
        long now = clock.millis();
        List<Entry> old = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (expired(e, now) && !e.key().equals(protectedKey)) {
                old.add(e);
            }
        }
        old.forEach(this::drop);
        for (java.util.Iterator<Entry> it = new ArrayList<>(entries.values()).iterator();
                totalBytes > maxBytes && it.hasNext();) {
            Entry e = it.next();
            if (!e.key().equals(protectedKey)) {
                drop(e);
            }
        }
    }

    private void drop(Entry e) {
        entries.remove(e.key());
        totalBytes -= e.bytes();
        try {
            Files.deleteIfExists(data(e.key()));
            Files.deleteIfExists(meta(e.key()));
        } catch (IOException ex) {
            LOG.warning("Cannot delete cache entry " + e.key() + ": " + ex);
        }
    }

    public synchronized long sizeBytes() {
        return totalBytes;
    }

    public synchronized int entryCount() {
        return entries.size();
    }
}
