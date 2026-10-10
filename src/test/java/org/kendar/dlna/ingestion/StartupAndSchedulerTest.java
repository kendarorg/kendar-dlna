package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.testutil.CountingTagReader;
import org.kendar.dlna.testutil.MutableClock;

class StartupAndSchedulerTest {

    static final Duration TWELVE_HOURS = Duration.ofHours(12);

    @TempDir
    Path tmp;
    Path music;
    Path data;
    Map<String, Path> roots = new LinkedHashMap<>();
    CountingTagReader reader = new CountingTagReader();
    MutableClock clock = new MutableClock();
    LibraryScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        music = tmp.resolve("music");
        data = tmp.resolve("data");
        Files.createDirectories(music);
        Files.writeString(music.resolve("a.mp3"), "a");
        Files.writeString(music.resolve("b.mp3"), "bb");
        roots.put("main", music);
        scanner = new LibraryScanner(reader, clock);
    }

    private JsonCatalogStore store() {
        return new JsonCatalogStore(data, RootsFingerprint.of(roots), "t", false, clock);
    }

    private CatalogStartup.Result start() {
        return CatalogStartup.start(store(), scanner, roots, null, clock, TWELVE_HOURS);
    }

    @Test
    void firstStartScansAndPersists() {
        CatalogStartup.Result r = start();
        assertThat(r.loadedFromIndex()).isFalse();
        assertThat(r.catchUpNeeded()).isFalse();
        assertThat(r.catalog().current().tracks()).hasSize(2);
        assertThat(reader.reads.get()).isEqualTo(2);
        assertThat(Files.isRegularFile(data.resolve("catalog.json"))).isTrue();
    }

    @Test
    void restartLoadsTheIndexWithoutReadingAnyTags() {
        start();
        reader.reads.set(0);
        clock.advance(Duration.ofHours(1));

        CatalogStartup.Result r = start();

        assertThat(r.loadedFromIndex()).isTrue();
        assertThat(r.catchUpNeeded()).isFalse();
        assertThat(reader.reads.get()).isZero();
        assertThat(r.catalog().current().tracks()).hasSize(2);
    }

    @Test
    void staleIndexIsServedAndFlaggedForCatchUp() {
        start();
        clock.advance(Duration.ofHours(13));
        CatalogStartup.Result r = start();
        assertThat(r.loadedFromIndex()).isTrue();
        assertThat(r.catchUpNeeded()).isTrue();
    }

    @Test
    void thresholdBoundaryIsExclusive() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(ScanScheduler.catchUpNeeded(t, TWELVE_HOURS, t.plus(TWELVE_HOURS))).isFalse();
        assertThat(ScanScheduler.catchUpNeeded(t, TWELVE_HOURS, t.plus(TWELVE_HOURS).plusSeconds(1))).isTrue();
    }

    @Test
    void corruptIndexForcesAFullScan() throws IOException {
        start();
        Files.writeString(data.resolve("catalog.json"), "garbage");
        reader.reads.set(0);
        CatalogStartup.Result r = start();
        assertThat(r.loadedFromIndex()).isFalse();
        assertThat(reader.reads.get()).isEqualTo(2);
        assertThat(store().load()).isPresent();   // rewritten
    }

    @Test
    void changedRootsForceAFullScan() throws IOException {
        start();
        Path other = tmp.resolve("other");
        Files.createDirectories(other);
        roots.put("extra", other);
        CatalogStartup.Result r = start();
        assertThat(r.loadedFromIndex()).isFalse();
        assertThat(r.catalog().current().roots()).hasSize(2);
    }

    @Test
    void catchUpScanPicksUpChangesMadeWhileDown() throws Exception {
        start();
        Files.writeString(music.resolve("c.mp3"), "ccc");
        Files.delete(music.resolve("a.mp3"));
        clock.advance(Duration.ofHours(13));

        CatalogStartup.Result r = start();
        assertThat(r.catalog().current().tracks()).hasSize(2);   // stale view served at once
        assertThat(r.catalog().current().track(org.kendar.dlna.shared.RootRef.parse("main/a.mp3"))).isPresent();

        RescanService rescans = new RescanService(scanner, r.catalog(), roots, null, clock);
        ScanScheduler scheduler = new ScanScheduler(rescans, CronExpression.parse("0 */12 * * *"), clock);
        assertThat(scheduler.catchUpIfStale(r.catalog().current().scannedAt(), TWELVE_HOURS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (rescans.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        ScanReport report = rescans.status().lastReport();
        assertThat(report.trigger()).isEqualTo(ScanTrigger.CATCH_UP);
        assertThat(r.catalog().current().tracks()).extracting(t -> t.ref().name())
                .containsExactlyInAnyOrder("b.mp3", "c.mp3");
        assertThat(r.catalog().current().updateId()).isEqualTo(2);
        assertThat(store().load().orElseThrow().tracks()).hasSize(2);
        rescans.close();
        scheduler.close();
    }

    @Test
    void freshIndexDoesNotCatchUp() {
        CatalogStartup.Result r = start();
        RescanService rescans = new RescanService(scanner, r.catalog(), roots, null, clock);
        ScanScheduler scheduler = new ScanScheduler(rescans, CronExpression.parse("0 */12 * * *"), clock);
        assertThat(scheduler.catchUpIfStale(r.catalog().current().scannedAt(), TWELVE_HOURS)).isFalse();
        assertThat(rescans.status().lastReport()).isNull();
        rescans.close();
        scheduler.close();
    }

    @Test
    void schedulerFireStartsAScheduledRescanAndSkipsWhenBusy() throws Exception {
        CatalogStartup.Result r = start();
        RescanService rescans = new RescanService(scanner, r.catalog(), roots, null, clock);
        ScanScheduler scheduler = new ScanScheduler(rescans, CronExpression.parse("0 */12 * * *"), clock);
        assertThat(scheduler.nextFire()).isEqualTo(Instant.parse("2026-01-01T12:00:00Z")
                .atZone(clock.getZone()).toInstant());

        reader.gate = new java.util.concurrent.CountDownLatch(1);
        reader.entered = new java.util.concurrent.CountDownLatch(1);
        Files.writeString(music.resolve("late.mp3"), "l");
        scheduler.fire();   // starts, re-arms
        assertThat(reader.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(rescans.status().trigger()).isEqualTo(ScanTrigger.SCHEDULED);
        scheduler.fire();   // busy: skipped, no exception
        reader.gate.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (rescans.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(rescans.status().lastReport().succeeded()).isTrue();
        assertThat(r.catalog().current().tracks()).hasSize(3);
        scheduler.close();
        rescans.close();
    }

    @Test
    void holderIsSharedWithTheCatalog() {
        CatalogStartup.Result r = start();
        SnapshotHolder h = r.catalog().holder();
        assertThat(h.current()).isSameAs(r.catalog().current());
    }
}
