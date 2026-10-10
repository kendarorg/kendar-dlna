package org.kendar.dlna.ingestion;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Live counters and errors of one scan; written by the scanner, read by status reporting. Thread-safe. */
public final class ScanProgress {

    static final int MAX_ERRORS = 200;

    private final AtomicLong directories = new AtomicLong();
    private final AtomicLong audioFiles = new AtomicLong();
    private final AtomicLong tracksReused = new AtomicLong();
    private final AtomicLong tracksRead = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final List<String> errors = new CopyOnWriteArrayList<>();

    void directory() {
        directories.incrementAndGet();
    }

    void audioFile() {
        audioFiles.incrementAndGet();
    }

    void reused() {
        tracksReused.incrementAndGet();
    }

    void read() {
        tracksRead.incrementAndGet();
    }

    void error(String message) {
        errorCount.incrementAndGet();
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        }
    }

    public long directories() {
        return directories.get();
    }

    /** Audio files found so far (supported or not). */
    public long audioFiles() {
        return audioFiles.get();
    }

    /** Tracks taken over unchanged from the previous snapshot (tags not re-read). */
    public long tracksReused() {
        return tracksReused.get();
    }

    /** Tracks whose tags were read from the file. */
    public long tracksRead() {
        return tracksRead.get();
    }

    public long errorCount() {
        return errorCount.get();
    }

    /** The first {@value #MAX_ERRORS} error messages. */
    public List<String> errors() {
        return List.copyOf(errors);
    }
}
