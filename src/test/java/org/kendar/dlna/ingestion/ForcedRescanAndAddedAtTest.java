package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.CountingTagReader;
import org.kendar.dlna.testutil.MutableClock;

/** Forced re-read of tags and the first-seen timestamp ({@code addedAt}) across scans. */
class ForcedRescanAndAddedAtTest {

    @TempDir
    Path tmp;
    Path music;
    Path playlists;
    CountingTagReader reader = new CountingTagReader();
    MutableClock clock = new MutableClock();
    Map<String, Path> roots = new LinkedHashMap<>();
    CatalogService catalog;
    RescanService rescans;
    LibraryScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        music = tmp.resolve("music");
        playlists = tmp.resolve("playlists");
        Files.createDirectories(playlists);
        write("A/one.mp3", "1", 1_000_000_000_000L);
        write("A/two.mp3", "22", 1_100_000_000_000L);
        write("B/three.flac", "333", 1_200_000_000_000L);
        roots.put("main", music);
        scanner = new LibraryScanner(reader, clock);
        catalog = new CatalogService(new SnapshotHolder(), new JsonCatalogStore(tmp.resolve("data"),
                RootsFingerprint.of(roots), "t", false), clock);
        catalog.install(scanner.scan(roots, playlists, 1), true);
        rescans = new RescanService(scanner, catalog, roots, playlists, clock);
        reader.reads.set(0);
        reader.perFile.clear();
    }

    private void write(String rel, String content, long mtime) throws IOException {
        Path p = music.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
    }

    private Track track(String rel) {
        return catalog.current().track(RootRef.parse("main/" + rel)).orElseThrow();
    }

    // ---- forced re-read -----------------------------------------------------------------------------------------

    @Test
    void unchangedFilesAreNotReReadWithoutForce() {
        ScanReport r = rescans.rescanNow(ScanTrigger.MANUAL, null, false);

        assertThat(reader.reads.get()).isZero();
        assertThat(r.tracksReused()).isEqualTo(3);
        assertThat(r.tracksRead()).isZero();
    }

    @Test
    void forceReReadsEveryUnchangedFile() {
        ScanReport r = rescans.rescanNow(ScanTrigger.MANUAL, null, true);

        assertThat(r.succeeded()).isTrue();
        assertThat(reader.reads.get()).isEqualTo(3);
        assertThat(r.tracksRead()).isEqualTo(3);
        assertThat(r.tracksReused()).isZero();
        assertThat(catalog.current().tracks()).hasSize(3);
    }

    @Test
    void forceKeepsTheFirstSeenTimestampOfExistingTracks() {
        long one = track("A/one.mp3").addedAt();
        long three = track("B/three.flac").addedAt();
        assertThat(one).isPositive();
        clock.advance(Duration.ofDays(30));

        rescans.rescanNow(ScanTrigger.MANUAL, null, true);

        assertThat(track("A/one.mp3").addedAt()).isEqualTo(one);
        assertThat(track("B/three.flac").addedAt()).isEqualTo(three);
    }

    @Test
    void forcedFolderRescanOnlyReReadsThatSubtree() {
        ScanReport r = rescans.rescanNow(ScanTrigger.MANUAL, RootRef.parse("main/A"), true);

        assertThat(r.succeeded()).isTrue();
        assertThat(reader.readsOf("one.mp3")).isEqualTo(1);
        assertThat(reader.readsOf("two.mp3")).isEqualTo(1);
        assertThat(reader.readsOf("three.flac")).isZero();
        assertThat(catalog.current().tracks()).hasSize(3);
    }

    @Test
    void forcedRescanPicksUpChangedTagsOfUnchangedFiles() {
        // the same file, but the reader now yields other tags: only a forced scan sees them
        LibraryScanner other = new LibraryScanner(new TagReader() {
            @Override
            public boolean supports(org.kendar.dlna.catalog.Format format) {
                return true;
            }

            @Override
            public org.kendar.dlna.ingestion.TrackMeta read(Path file, org.kendar.dlna.catalog.Format format)
                    throws TagReadException {
                return new org.kendar.dlna.ingestion.TrackMeta(new org.kendar.dlna.catalog.TrackTags("Fresh", "X",
                        null, null, null, null, null, null, null, null, null, null, "Composer X", null), 5000, 128,
                        44100, 2, false);
            }
        }, clock);
        RescanService plain = new RescanService(other, catalog, roots, playlists, clock);

        plain.rescanNow(ScanTrigger.MANUAL, null, false);
        assertThat(track("A/one.mp3").tags().composer()).isNull();

        plain.rescanNow(ScanTrigger.MANUAL, null, true);
        assertThat(track("A/one.mp3").tags().composer()).isEqualTo("Composer X");
        assertThat(track("A/one.mp3").tags().title()).isEqualTo("Fresh");
    }

    // ---- addedAt ------------------------------------------------------------------------------------------------

    @Test
    void theVeryFirstScanUsesCreationTimeElseMtime() throws IOException {
        Track t = track("A/one.mp3");
        long created = Files.readAttributes(music.resolve("A/one.mp3"),
                java.nio.file.attribute.BasicFileAttributes.class).creationTime().toMillis();
        long expected = created > 0 ? created : t.mtime();

        assertThat(t.addedAt()).isEqualTo(expected);
        assertThat(t.addedAt()).isPositive();
    }

    @Test
    void filesFirstSeenByALaterRescanGetTheScanTime() throws IOException {
        clock.advance(Duration.ofHours(2));
        write("A/new.mp3", "n", 1_000L);   // an old mtime must not matter: it was added now

        rescans.rescanNow(ScanTrigger.MANUAL, null, false);

        assertThat(track("A/new.mp3").addedAt()).isEqualTo(clock.millis());
    }

    @Test
    void addedAtSurvivesAChangedFile() throws IOException {
        long before = track("A/two.mp3").addedAt();
        clock.advance(Duration.ofHours(2));
        write("A/two.mp3", "2222", 1_300_000_000_000L);   // changed: re-read, but not "added" again

        rescans.rescanNow(ScanTrigger.MANUAL, null, false);

        assertThat(reader.readsOf("two.mp3")).isEqualTo(1);
        assertThat(track("A/two.mp3").addedAt()).isEqualTo(before);
    }

    @Test
    void addedAtSurvivesThePersistedIndex() {
        JsonCatalogStore store = new JsonCatalogStore(tmp.resolve("data2"), "fp", "t", false);
        store.save(catalog.current());

        Track back = store.load().orElseThrow().track(RootRef.parse("main/A/one.mp3")).orElseThrow();

        assertThat(back.addedAt()).isEqualTo(track("A/one.mp3").addedAt());
    }
}
