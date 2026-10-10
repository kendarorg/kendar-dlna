package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.search.Query;
import org.kendar.dlna.search.SearchEngine;
import org.kendar.dlna.search.SearchScope;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.LibraryFixture;

/** list.json merged into the catalog by the scanner: precedence, case-insensitive match, rescan on change. */
class ListJsonIngestionTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    Path album;
    final RootRef four = RootRef.parse("main/Album/04 four.mid");
    final RootRef one = RootRef.parse("main/Album/01 one.wav");

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        album = lib.music.resolve("Album");
    }

    private Snapshot rescan() {
        Snapshot s = lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null, new ScanProgress());
        lib.catalog.commit(s);
        return lib.catalog.current();
    }

    private Track track(RootRef ref) {
        return lib.catalog.current().track(ref).orElseThrow();
    }

    @Test
    void embeddedTagsAreUntouchedWithoutAListJson() {
        assertThat(DisplayNames.trackTitle(track(four))).isEqualTo("Four");
        assertThat(track(four).tags().transcriber()).isNull();
        assertThat(lib.catalog.current().folder(RootRef.parse("main/Album")).orElseThrow().listHash()).isNull();
    }

    @Test
    void listJsonOverridesTitleArtistAlbumAndStoresTheTranscriber() throws IOException {
        Files.writeString(album.resolve("list.json"), """
                [{"filename":"04 FOUR.MID","author":"FRANCK, César","partOf":"Organ works","title":"Panis Angelicus",
                  "transcriber":"F.Raborn"},
                 {"filename":"gone.mid","title":"No such file"}]""");
        rescan();

        Track t = track(four);
        assertThat(DisplayNames.trackTitle(t)).isEqualTo("Panis Angelicus");
        assertThat(t.tags().artist()).isEqualTo("FRANCK, César");
        assertThat(t.tags().album()).isEqualTo("Organ works");
        assertThat(t.tags().transcriber()).isEqualTo("F.Raborn");
        // other files of the directory are not affected
        assertThat(track(one).tags().transcriber()).isNull();
        assertThat(lib.catalog.current().folder(RootRef.parse("main/Album")).orElseThrow().listHash()).isNotNull();
        // the new title is what search finds
        assertThat(SearchEngine.search(lib.catalog.current(), SearchScope.LIBRARY, Query.text("angelicus")))
                .hasSize(1);
    }

    @Test
    void blankFieldsKeepTheEmbeddedTags() throws IOException {
        Files.writeString(album.resolve("list.json"),
                "[{\"filename\":\"04 four.mid\",\"author\":\"\",\"partOf\":\"\",\"title\":\"\",\"transcriber\":\"T\"}]");
        rescan();
        assertThat(DisplayNames.trackTitle(track(four))).isEqualTo("Four");
        assertThat(track(four).tags().transcriber()).isEqualTo("T");
    }

    @Test
    void editingListJsonRescansTheDirectoryEvenWhenTheAudioFilesDidNotChange() throws IOException {
        Files.writeString(album.resolve("list.json"), "[{\"filename\":\"04 four.mid\",\"title\":\"First\"}]");
        rescan();
        assertThat(DisplayNames.trackTitle(track(four))).isEqualTo("First");
        long id = lib.catalog.current().updateId();

        Files.writeString(album.resolve("list.json"), "[{\"filename\":\"04 four.mid\",\"title\":\"Second\"}]");
        rescan();
        assertThat(DisplayNames.trackTitle(track(four))).isEqualTo("Second");
        assertThat(lib.catalog.current().updateId()).isGreaterThan(id);   // DLNA clients are told

        // unchanged file: tracks are reused, not re-read
        Snapshot before = lib.catalog.current();
        ScanProgress p = new ScanProgress();
        lib.catalog.commit(lib.scanner.rescan(lib.roots, lib.playlists, before, null, p));
        assertThat(p.tracksReused()).isEqualTo(4);
        assertThat(lib.catalog.current().updateId()).isEqualTo(before.updateId());
    }

    @Test
    void removingTheEntryOrTheFileRestoresTheEmbeddedTags() throws IOException {
        Files.writeString(album.resolve("list.json"), "[{\"filename\":\"04 four.mid\",\"title\":\"Listed\"}]");
        rescan();
        Files.writeString(album.resolve("list.json"), "[]");
        rescan();
        assertThat(DisplayNames.trackTitle(track(four))).isEqualTo("Four");

        Files.writeString(album.resolve("list.json"), "[{\"filename\":\"04 four.mid\",\"title\":\"Listed\"}]");
        rescan();
        Files.delete(album.resolve("list.json"));
        rescan();
        assertThat(DisplayNames.trackTitle(track(four))).isEqualTo("Four");
        assertThat(lib.catalog.current().folder(RootRef.parse("main/Album")).orElseThrow().listHash()).isNull();
    }

    @Test
    void aBrokenListJsonIsAWarningNotAFailure() throws IOException {
        Files.writeString(album.resolve("list.json"), "[{\"filename\": ");
        rescan();
        assertThat(lib.catalog.current().tracks()).hasSize(4);
        assertThat(DisplayNames.trackTitle(track(four))).isEqualTo("Four");
        assertThat(lib.catalog.current().folder(RootRef.parse("main/Album")).orElseThrow().listError())
                .startsWith("malformed JSON");
    }

    @Test
    void aForcedFullScanAndTheIndexKeepTheMergedValues() throws IOException {
        Files.writeString(album.resolve("list.json"), "[{\"filename\":\"04 four.mid\",\"title\":\"Listed\","
                + "\"transcriber\":\"T\"}]");
        Snapshot full = lib.scanner.scan(lib.roots, lib.playlists, 9);
        assertThat(DisplayNames.trackTitle(full.track(four).orElseThrow())).isEqualTo("Listed");

        JsonCatalogStore store = new JsonCatalogStore(tmp.resolve("data"), "fp", "1", false);
        store.save(full);
        Snapshot loaded = store.load().orElseThrow();
        assertThat(loaded.track(four).orElseThrow().tags().transcriber()).isEqualTo("T");
        assertThat(loaded.folder(RootRef.parse("main/Album")).orElseThrow().listHash())
                .isEqualTo(full.folder(RootRef.parse("main/Album")).orElseThrow().listHash());
    }
}
