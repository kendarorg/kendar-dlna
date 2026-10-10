package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.CatalogChangeListener;
import org.kendar.dlna.catalog.ChangeSet;
import org.kendar.dlna.catalog.ContainerKeys;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.CountingTagReader;
import org.kendar.dlna.testutil.MutableClock;

class RescanServiceTest {

    @TempDir
    Path tmp;
    Path music;
    Path playlists;
    Path data;
    CountingTagReader reader = new CountingTagReader();
    MutableClock clock = new MutableClock();
    Map<String, Path> roots = new LinkedHashMap<>();
    JsonCatalogStore store;
    CatalogService catalog;
    RescanService rescans;
    List<ChangeSet> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        music = tmp.resolve("music");
        playlists = tmp.resolve("playlists");
        data = tmp.resolve("data");
        Files.createDirectories(music.resolve("A"));
        Files.createDirectories(music.resolve("B"));
        Files.createDirectories(playlists);
        write("A/one.mp3", "1");
        write("A/two.mp3", "22");
        write("B/three.flac", "333");
        roots.put("main", music);
        LibraryScanner scanner = new LibraryScanner(reader, clock);
        store = new JsonCatalogStore(data, RootsFingerprint.of(roots), "t", false);
        catalog = new CatalogService(new SnapshotHolder(), store, clock);
        catalog.install(scanner.scan(roots, playlists, 1), true);
        catalog.addListener((CatalogChangeListener) events::add);
        rescans = new RescanService(scanner, catalog, roots, playlists, clock);
        reader.reads.set(0);
        reader.perFile.clear();
    }

    private void write(String rel, String content) throws IOException {
        Path p = music.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private String folderKey(String rel) {
        return Folder.idOf(new RootRef("main", rel));
    }

    @Test
    void unchangedLibraryRereadsNothingAndKeepsUpdateIds() {
        Snapshot before = catalog.current();
        clock.advance(Duration.ofHours(1));
        ScanReport r = rescans.rescanNow(ScanTrigger.MANUAL, null);

        assertThat(r.succeeded()).isTrue();
        assertThat(reader.reads.get()).isZero();
        assertThat(r.tracksReused()).isEqualTo(3);
        assertThat(r.tracksRead()).isZero();
        assertThat(r.changedContainers()).isEmpty();
        assertThat(catalog.current().updateId()).isEqualTo(before.updateId());
        assertThat(catalog.current().scannedAt()).isEqualTo(clock.instant());
        assertThat(events).isEmpty();
        assertThat(store.load().orElseThrow().scannedAt()).isEqualTo(clock.instant());   // lastScan persisted
    }

    @Test
    void addedChangedAndDeletedFilesAreDetected() throws IOException {
        write("A/new.mp3", "n");                                   // added
        write("A/two.mp3", "2222");                                // changed (size)
        Files.delete(music.resolve("B/three.flac"));               // deleted
        clock.advance(Duration.ofMinutes(5));

        ScanReport r = rescans.rescanNow(ScanTrigger.MANUAL, null);

        Snapshot s = catalog.current();
        assertThat(s.tracks()).extracting(t -> t.ref().qualified())
                .containsExactlyInAnyOrder("main/A/one.mp3", "main/A/two.mp3", "main/A/new.mp3");
        assertThat(reader.readsOf("one.mp3")).isZero();
        assertThat(reader.readsOf("new.mp3")).isEqualTo(1);
        assertThat(reader.readsOf("two.mp3")).isEqualTo(1);
        assertThat(s.track(RootRef.parse("main/A/two.mp3")).orElseThrow().size()).isEqualTo(4);
        assertThat(r.tracksBefore()).isEqualTo(3);
        assertThat(r.tracksAfter()).isEqualTo(3);

        assertThat(s.updateId()).isEqualTo(2);   // one bump for the whole commit
        assertThat(r.changedContainers()).containsExactlyInAnyOrder(folderKey("A"), folderKey("B"));
        assertThat(s.containerUpdateId(folderKey("A"))).isEqualTo(2);
        assertThat(s.containerUpdateId(folderKey("B"))).isEqualTo(2);
        assertThat(s.containerUpdateId(folderKey(""))).isZero();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).systemUpdateId()).isEqualTo(2);

        Snapshot persisted = store.load().orElseThrow();
        assertThat(persisted.updateId()).isEqualTo(2);
        assertThat(persisted.tracks()).hasSize(3);
        assertThat(persisted.containerUpdateId(folderKey("A"))).isEqualTo(2);
    }

    @Test
    void changedMtimeAloneTriggersAReread() throws IOException {
        Files.setLastModifiedTime(music.resolve("A/one.mp3"), FileTime.fromMillis(1_000_000_000L));
        rescans.rescanNow(ScanTrigger.MANUAL, null);
        assertThat(reader.readsOf("one.mp3")).isEqualTo(1);
        assertThat(reader.readsOf("two.mp3")).isZero();
    }

    @Test
    void settingsPlaylistsAndArtAreCovered() throws IOException {
        write("A/settings.json", "{\"sort\":\"title\"}");
        write("A/front.jpg", "jpg");
        write("../playlists/fav.json", "{\"name\":\"Fav\",\"songs\":[\"main/A/one.mp3\"]}");
        rescans.rescanNow(ScanTrigger.MANUAL, null);

        Snapshot s = catalog.current();
        Folder a = s.folder(new RootRef("main", "A")).orElseThrow();
        assertThat(a.settings().sort().name()).isEqualTo("TITLE");
        assertThat(a.hasFront()).isTrue();
        assertThat(s.playlists()).hasSize(1);
        assertThat(s.containerUpdateId(ContainerKeys.PLAYLISTS)).isEqualTo(2);
    }

    @Test
    void folderRescanOnlyTouchesItsSubtree() throws IOException {
        write("A/new.mp3", "n");
        write("B/other.mp3", "o");   // outside the scope: must stay unseen
        ScanReport r = rescans.rescanNow(ScanTrigger.MANUAL, new RootRef("main", "A"));

        assertThat(r.scope()).isEqualTo(new RootRef("main", "A"));
        Snapshot s = catalog.current();
        assertThat(s.track(RootRef.parse("main/A/new.mp3"))).isPresent();
        assertThat(s.track(RootRef.parse("main/B/other.mp3"))).isEmpty();
        assertThat(s.track(RootRef.parse("main/B/three.flac"))).isPresent();
        assertThat(r.changedContainers()).containsExactly(folderKey("A"));
    }

    @Test
    void folderRescanCanPickUpANewDirectoryAndDropADeletedOne() throws IOException {
        write("C/x.mp3", "x");
        rescans.rescanNow(ScanTrigger.MANUAL, new RootRef("main", "C"));   // new dir, parent known
        assertThat(catalog.current().track(RootRef.parse("main/C/x.mp3"))).isPresent();

        Files.delete(music.resolve("C/x.mp3"));
        Files.delete(music.resolve("C"));
        rescans.rescanNow(ScanTrigger.MANUAL, new RootRef("main", "C"));
        assertThat(catalog.current().folder(new RootRef("main", "C"))).isEmpty();
        assertThat(catalog.current().tracks()).hasSize(3);
    }

    @Test
    void folderRescanRejectsUnknownScopes() {
        assertThatThrownBy(() -> rescans.rescanFolder(new RootRef("nope", "A")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rescans.rescanFolder(new RootRef("main", "X/Y")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rescans.isRunning()).isFalse();
    }

    @Test
    void unreadableFilesAreReportedAsErrorsAndUnsupported() throws IOException {
        write("A/broken.mp3", "bad");
        ScanReport r = rescans.rescanNow(ScanTrigger.MANUAL, null);
        assertThat(r.succeeded()).isTrue();
        assertThat(r.errors()).singleElement().asString().contains("broken.mp3");
        assertThat(catalog.current().unsupported()).extracting(u -> u.ref().name()).contains("broken.mp3");
    }

    @Test
    void scansNeverOverlapAndStatusShowsProgress() throws Exception {
        write("A/slow.mp3", "s");
        reader.gate = new CountDownLatch(1);
        reader.entered = new CountDownLatch(1);
        CompletableFuture<ScanReport> first = rescans.rescanAll(ScanTrigger.SCHEDULED);
        assertThat(reader.entered.await(5, TimeUnit.SECONDS)).isTrue();

        ScanStatus running = rescans.status();
        assertThat(running.running()).isTrue();
        assertThat(running.trigger()).isEqualTo(ScanTrigger.SCHEDULED);
        assertThat(running.tracksReused()).isEqualTo(3);
        assertThatThrownBy(() -> rescans.rescanAll(ScanTrigger.MANUAL)).isInstanceOf(ScanAlreadyRunningException.class);
        assertThatThrownBy(() -> rescans.rescanFolder(new RootRef("main", "A")))
                .isInstanceOf(ScanAlreadyRunningException.class);

        reader.gate.countDown();
        ScanReport report = first.get(10, TimeUnit.SECONDS);
        ScanStatus idle = rescans.status();
        assertThat(idle.running()).isFalse();
        assertThat(idle.lastReport()).isSameAs(report);
        assertThat(report.tracksRead()).isEqualTo(1);
        assertThat(catalog.current().track(RootRef.parse("main/A/slow.mp3"))).isPresent();
        rescans.rescanAll(ScanTrigger.MANUAL).get(10, TimeUnit.SECONDS);   // free again
    }

    @Test
    void failedScanLeavesTheCatalogUntouchedAndIsReported() throws IOException {
        Snapshot before = catalog.current();
        LibraryScanner failing = new LibraryScanner(reader, clock) {
            @Override
            public Snapshot rescan(Map<String, Path> r, Path p, Snapshot prev, RootRef scope, ScanProgress pr,
                    boolean force) {
                throw new IllegalStateException("disk exploded");
            }
        };
        RescanService bad = new RescanService(failing, catalog, roots, playlists, clock);
        ScanReport r = bad.rescanNow(ScanTrigger.MANUAL, null);
        assertThat(r.succeeded()).isFalse();
        assertThat(r.failure()).contains("disk exploded");
        assertThat(catalog.current()).isSameAs(before);
        assertThat(bad.isRunning()).isFalse();
        assertThat(bad.status().lastReport()).isSameAs(r);
    }
}
