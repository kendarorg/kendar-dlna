package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SortMode;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.shared.RootRef;

class JsonCatalogStoreTest {

    @TempDir
    Path dir;

    Snapshot sample;

    @BeforeEach
    void sample() {
        DirectorySettings settings = new DirectorySettings(SortMode.EXPLICIT, List.of("b.mp3", "a.mp3"),
                new AlbumInfo("Alb", "Art", 1999, "Rock", 1, 2), 7, List.of(ViewKind.ALBUM, ViewKind.TITLE, ViewKind.COMPOSER), 12, "Shown Name");
        List<Folder> folders = List.of(new Folder(RootRef.root("main"), 10, settings, null, true, false),
                new Folder(RootRef.parse("main/sub"), 11, null, "bad json", false, true),
                new Folder(RootRef.root("other"), 12, null, null, false, false));
        TrackTags tags = new TrackTags("T", "A", "Al", "AA", "G", 2001, 3, 1, 2, List.of("A", "B"), List.of("AA"),
                List.of("G1", "G2"), "Comp", List.of("Comp", "Comp2"));
        List<Track> tracks = List.of(
                new Track(RootRef.parse("main/a.mp3"), 100, 2000, Format.MP3, tags, 61_500, 320, 44100, 2, true,
                        2_711_000, 0, 1_700_000_000_123L),
                new Track(RootRef.parse("main/sub/éè \"q\".mid"), 101, 30, Format.MIDI, null, 5000, 0,
                        0, 0, false, 0, 4_321),
                new Track(RootRef.parse("other/z.flac"), 5, 7, Format.FLAC, TrackTags.EMPTY, 0, 0, 0, 0, false));
        List<UnsupportedFile> unsupported = List.of(new UnsupportedFile(RootRef.parse("main/x.m4a"), 9, "no decoder"));
        List<Playlist> playlists = List.of(new Playlist("fav", "Favourites", List.of("main/a.mp3", "main/gone.mp3")));
        sample = new Snapshot(42, Instant.parse("2026-02-03T04:05:06.789Z"), folders, tracks, unsupported, playlists,
                Map.of("x", 40L, "y", 42L));
    }

    private JsonCatalogStore store(boolean gzip) {
        return new JsonCatalogStore(dir, "fp1", "1.0", gzip);
    }

    @Test
    void roundTripsEverything() {
        JsonCatalogStore s = store(false);
        s.save(sample);
        Snapshot back = s.load().orElseThrow();

        assertThat(back.updateId()).isEqualTo(42);
        assertThat(back.scannedAt()).isEqualTo(sample.scannedAt());
        assertThat(back.containerUpdateIds()).isEqualTo(Map.of("x", 40L, "y", 42L));
        assertThat(back.allFolders()).containsExactlyElementsOf(sample.allFolders());
        assertThat(back.tracks()).containsExactlyInAnyOrderElementsOf(sample.tracks());
        assertThat(back.unsupported()).isEqualTo(sample.unsupported());
        assertThat(back.playlistDefinitions()).isEqualTo(sample.playlistDefinitions());
        assertThat(back.roots()).extracting(f -> f.ref().root()).containsExactly("main", "other");
        assertThat(back.playlists().get(0).brokenCount()).isEqualTo(1);
    }

    @Test
    void indexWrittenBeforeDisplayNameStillLoads() throws IOException {
        JsonCatalogStore s = store(false);
        s.save(sample);
        String json = Files.readString(dir.resolve("catalog.json"));
        assertThat(json).contains("\"displayName\":\"Shown Name\"");
        Files.writeString(dir.resolve("catalog.json"), json.replace(",\"displayName\":\"Shown Name\"", ""));
        Snapshot back = s.load().orElseThrow();
        assertThat(back.folder(RootRef.root("main")).orElseThrow().settings().displayName()).isNull();
        assertThat(back.folder(RootRef.root("main")).orElseThrow().settings().recent()).isEqualTo(12);
    }

    @Test
    void gzipRoundTripIsDetectedOnRead() throws IOException {
        store(true).save(sample);
        assertThat(Files.readAllBytes(dir.resolve("catalog.json"))[0]).isEqualTo((byte) 0x1f);
        assertThat(store(false).load()).isPresent();
        assertThat(store(false).load().orElseThrow().tracks()).hasSize(3);
    }

    @Test
    void emptySnapshotRoundTrips() {
        JsonCatalogStore s = store(false);
        s.save(Snapshot.empty());
        assertThat(s.load().orElseThrow().tracks()).isEmpty();
    }

    @Test
    void missingFileIsEmpty() {
        assertThat(store(false).load()).isEmpty();
    }

    @Test
    void corruptFileIsEmpty() throws IOException {
        Files.writeString(dir.resolve("catalog.json"), "{ this is not json");
        assertThat(store(false).load()).isEmpty();
        Files.writeString(dir.resolve("catalog.json"), "");
        assertThat(store(false).load()).isEmpty();
    }

    @Test
    void truncatedFileIsEmpty() throws IOException {
        store(false).save(sample);
        byte[] all = Files.readAllBytes(dir.resolve("catalog.json"));
        Files.write(dir.resolve("catalog.json"), java.util.Arrays.copyOf(all, all.length * 2 / 3));
        assertThat(store(false).load()).isEmpty();
    }

    @Test
    void inconsistentContentIsEmpty() throws IOException {
        store(false).save(sample);
        String json = Files.readString(dir.resolve("catalog.json")).replace("main/sub", "main/nowhere/deeper");
        Files.writeString(dir.resolve("catalog.json"), json);
        // the track path inside "main/sub" is rewritten too: tracks without parent folder are rejected
        assertThat(store(false).load()).isEmpty();
    }

    @Test
    void formatVersionMismatchIsEmpty() throws IOException {
        store(false).save(sample);
        String json = Files.readString(dir.resolve("catalog.json")).replace("\"formatVersion\":2", "\"formatVersion\":99");
        assertThat(json).contains("99");
        Files.writeString(dir.resolve("catalog.json"), json);
        assertThat(store(false).load()).isEmpty();
    }

    @Test
    void changedRootsAreRejected() {
        store(false).save(sample);
        assertThat(new JsonCatalogStore(dir, "other-roots", "1.0", false).load()).isEmpty();
        assertThat(new JsonCatalogStore(dir, "fp1", "2.0", false).load()).isPresent();   // app version is informational
    }

    @Test
    void saveLeavesNoTempFileAndReplacesAtomically() throws IOException {
        JsonCatalogStore s = store(false);
        s.save(sample);
        s.save(Snapshot.empty());
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("catalog.json");
        }
        assertThat(s.load().orElseThrow().tracks()).isEmpty();
    }

    @Test
    void headerIsFirstAndHasTheDocumentedFields() throws IOException {
        store(false).save(sample);
        String json = Files.readString(dir.resolve("catalog.json"));
        assertThat(json).startsWith("{\"header\":{");
        assertThat(json).contains("\"formatVersion\":2", "\"appVersion\":\"1.0\"", "\"rootsFingerprint\":\"fp1\"",
                "\"writtenAt\"", "\"lastScanAt\":", "\"systemUpdateId\":42");
    }

    @Test
    void unwritableTargetDoesNotThrow() throws IOException {
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        new JsonCatalogStore(blocker.resolve("sub"), "fp", "1", false).save(sample);   // parent is a file
    }
}
