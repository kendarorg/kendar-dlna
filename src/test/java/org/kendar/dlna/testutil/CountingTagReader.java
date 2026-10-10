package org.kendar.dlna.testutil;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.ingestion.TrackMeta;

/**
 * Fake tag reader for scan tests: title = file name without extension, counts reads per file name, fails for
 * names starting with "broken", can block reads on a latch.
 */
public final class CountingTagReader implements TagReader {

    public final AtomicInteger reads = new AtomicInteger();
    public final Map<String, AtomicInteger> perFile = new ConcurrentHashMap<>();
    public volatile CountDownLatch gate;
    public volatile CountDownLatch entered = new CountDownLatch(1);

    @Override
    public boolean supports(Format format) {
        return format != Format.MIDI && format != Format.OPUS;
    }

    @Override
    public TrackMeta read(Path file, Format format) throws TagReadException {
        entered.countDown();
        CountDownLatch g = gate;
        if (g != null) {
            try {
                g.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String name = file.getFileName().toString();
        reads.incrementAndGet();
        perFile.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        if (name.startsWith("broken")) {
            throw new TagReadException("corrupt", null);
        }
        return new TrackMeta(new TrackTags(name.replaceFirst("\\.[^.]*$", ""), "Artist", null, null, null, null,
                null, null, null), 5000, 128, 44100, 2, false);
    }

    public int readsOf(String fileName) {
        AtomicInteger n = perFile.get(fileName);
        return n == null ? 0 : n.get();
    }
}
