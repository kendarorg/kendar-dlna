package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.testutil.MutableClock;

class TranscodeCacheTest {

    @TempDir
    Path dir;

    final MutableClock clock = new MutableClock();

    private TranscodeCache cache(long max, Duration ttl) {
        return new TranscodeCache(dir.resolve("c"), max, ttl, clock);
    }

    private static TranscodeCache.Source src(String ref, long mtime) {
        return new TranscodeCache.Source(ref, mtime, 10, "mp3");
    }

    private static TranscodeCache.Producer bytes(int n) {
        return (out, cancelled) -> out.write(new byte[n]);
    }

    private static long filesIn(Path d) throws IOException {
        try (Stream<Path> s = Files.list(d)) {
            return s.count();
        }
    }

    @Test
    void keysAreStableAndSensitiveToEveryPart() {
        assertThat(TranscodeCache.key("a", 1, "x")).isEqualTo(TranscodeCache.key("a", 1, "x"));
        assertThat(TranscodeCache.key("a", 1, "x")).isNotEqualTo(TranscodeCache.key("a", 2, "x"));
        assertThat(TranscodeCache.key("ab", "c")).isNotEqualTo(TranscodeCache.key("a", "bc"));
        assertThat(TranscodeCache.key("a")).matches("[0-9a-f]{40}");
    }

    @Test
    void producesOnceThenServesFromTheFile() throws Exception {
        TranscodeCache c = cache(1 << 20, Duration.ofHours(1));
        AtomicInteger calls = new AtomicInteger();
        TranscodeCache.Producer p = (out, cancelled) -> {
            calls.incrementAndGet();
            out.write(new byte[] {1, 2, 3});
        };
        Path f = c.getOrCreate("k1", src("m/a.flac", 1), p, () -> false);
        assertThat(Files.readAllBytes(f)).containsExactly(1, 2, 3);
        assertThat(c.getOrCreate("k1", src("m/a.flac", 1), p, () -> false)).isEqualTo(f);
        assertThat(calls).hasValue(1);
        assertThat(c.get("k1")).contains(f);
        assertThat(c.contains("k1")).isTrue();
        assertThat(c.get("nope")).isEmpty();
        assertThat(c.sizeBytes()).isEqualTo(3);
        assertThat(c.entryCount()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsShareOneProduction() throws Exception {
        TranscodeCache c = cache(1 << 20, Duration.ofHours(1));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TranscodeCache.Producer slow = (out, cancelled) -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
            out.write(7);
        };
        Path[] results = new Path[2];
        Thread t1 = Thread.ofVirtual().start(() -> results[0] = call(c, slow));
        started.await();
        Thread t2 = Thread.ofVirtual().start(() -> results[1] = call(c, slow));
        Thread.sleep(50);
        release.countDown();
        t1.join();
        t2.join();
        assertThat(calls).hasValue(1);
        assertThat(results[0]).isNotNull().isEqualTo(results[1]);
    }

    private static Path call(TranscodeCache c, TranscodeCache.Producer p) {
        try {
            return c.getOrCreate("k", src("m/a", 1), p, () -> false);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void failedOrCancelledProductionLeavesNothing() throws Exception {
        TranscodeCache c = cache(1 << 20, Duration.ofHours(1));
        assertThatThrownBy(() -> c.getOrCreate("bad", src("m/a", 1), (out, cancelled) -> {
            out.write(new byte[100]);
            throw new IOException("decoder failed");
        }, () -> false)).hasMessage("decoder failed");
        assertThatThrownBy(() -> c.getOrCreate("cancel", src("m/a", 1), bytes(10), () -> true))
                .isInstanceOf(InterruptedIOException.class);
        assertThat(c.contains("bad")).isFalse();
        assertThat(c.contains("cancel")).isFalse();
        assertThat(filesIn(dir.resolve("c"))).isZero();
        // and the key can be produced afterwards
        assertThat(c.getOrCreate("bad", src("m/a", 1), bytes(1), () -> false)).exists();
    }

    @Test
    void evictsLeastRecentlyUsedWhenOverTheCap() throws Exception {
        TranscodeCache c = cache(250, Duration.ofHours(1));
        c.getOrCreate("a", src("m/a", 1), bytes(100), () -> false);
        c.getOrCreate("b", src("m/b", 1), bytes(100), () -> false);
        c.get("a");   // a is now more recent than b
        c.getOrCreate("c", src("m/c", 1), bytes(100), () -> false);
        assertThat(c.contains("a")).isTrue();
        assertThat(c.contains("b")).isFalse();
        assertThat(c.contains("c")).isTrue();
        assertThat(c.sizeBytes()).isEqualTo(200);
        assertThat(filesIn(dir.resolve("c"))).isEqualTo(4);   // two entries, data + meta each
    }

    @Test
    void anEntryLargerThanTheCapSurvivesItsOwnInsert() throws Exception {
        TranscodeCache c = cache(10, Duration.ofHours(1));
        Path f = c.getOrCreate("big", src("m/a", 1), bytes(50), () -> false);
        assertThat(f).exists();
        c.getOrCreate("small", src("m/b", 1), bytes(5), () -> false);
        assertThat(c.contains("big")).isFalse();
        assertThat(c.contains("small")).isTrue();
    }

    @Test
    void entriesExpireAfterTheTtlSinceLastUse() throws Exception {
        TranscodeCache c = cache(1 << 20, Duration.ofHours(2));
        c.getOrCreate("a", src("m/a", 1), bytes(10), () -> false);
        c.getOrCreate("b", src("m/b", 1), bytes(10), () -> false);
        clock.advance(Duration.ofMinutes(90));
        assertThat(c.get("a")).isPresent();   // use refreshes a
        clock.advance(Duration.ofMinutes(60));
        assertThat(c.get("b")).as("unused for 150 minutes").isEmpty();
        assertThat(c.get("a")).isPresent();
        clock.advance(Duration.ofHours(3));
        c.sweep();
        assertThat(c.entryCount()).isZero();
        assertThat(filesIn(dir.resolve("c"))).isZero();
    }

    @Test
    void invalidateAndRemoveDropEntries() throws Exception {
        TranscodeCache c = cache(1 << 20, Duration.ofHours(1));
        c.getOrCreate("a", src("m/a", 1), bytes(10), () -> false);
        c.getOrCreate("b", src("m/b", 1), bytes(10), () -> false);
        c.getOrCreate("c", src("m/b", 2), bytes(10), () -> false);
        assertThat(c.invalidate(s -> s.ref().equals("m/b") && s.mtime() == 1)).isEqualTo(1);
        assertThat(c.contains("a")).isTrue();
        assertThat(c.contains("b")).isFalse();
        c.remove("a");
        assertThat(c.contains("a")).isFalse();
        assertThat(c.sizeBytes()).isEqualTo(10);
    }

    @Test
    void entriesSurviveARestartAndTemporaryFilesAreCleaned() throws Exception {
        TranscodeCache c = cache(1 << 20, Duration.ofHours(1));
        Path f = c.getOrCreate("a", src("m/a", 5), bytes(10), () -> false);
        Files.write(dir.resolve("c").resolve("stale.1.tmp"), new byte[3]);
        Files.write(dir.resolve("c").resolve("orphan.bin"), new byte[3]);   // no meta: dropped
        TranscodeCache again = cache(1 << 20, Duration.ofHours(1));
        assertThat(again.get("a")).contains(f);
        assertThat(again.entryCount()).isEqualTo(1);
        assertThat(again.invalidate(s -> s.ref().equals("m/a") && s.mtime() == 5)).isEqualTo(1);
        assertThat(filesIn(dir.resolve("c"))).isZero();
    }

    @Test
    void aVanishedFileIsForgotten() throws Exception {
        TranscodeCache c = cache(1 << 20, Duration.ofHours(1));
        Path f = c.getOrCreate("a", src("m/a", 1), bytes(10), () -> false);
        Files.delete(f);
        assertThat(c.get("a")).isEmpty();
        assertThat(c.entryCount()).isZero();
    }
}
