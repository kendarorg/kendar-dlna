package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.CueFixtures;
import org.kendar.dlna.testutil.LibraryFixture;

/** list.json entries addressed with {@code filename} + {@code index} / {@code start} reach the virtual tracks. */
class ListJsonSectionsIngestionTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    Path cues;
    final RootRef disc = RootRef.parse("main/Cues/disc.wav");

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        cues = lib.music.resolve("Cues");
        CueFixtures.write(cues);
    }

    private List<Track> scan() {
        Snapshot s = lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null, new ScanProgress(),
                false);
        lib.catalog.commit(s);
        return s.children(disc).stream().map(c -> ((Child.TrackChild) c).track()).toList();
    }

    private void list(String json) throws IOException {
        Files.writeString(cues.resolve("list.json"), json);
    }

    @Test
    void anIndexEntryAndTheOldHashNameDescribeTheSameTrack() throws IOException {
        list("""
                [ {"filename":"disc.wav","index":1,"title":"One (index)","transcriber":"T"},
                  {"filename":"disc.wav#2","title":"Two (legacy)"} ]""");
        List<Track> t = scan();
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("One (index)", "Two (legacy)", "Finale");
        assertThat(t.get(0).tags().transcriber()).isEqualTo("T");
        assertThat(t.get(0).tags().artist()).isEqualTo("The Band");   // untouched cue data
    }

    @Test
    void aStartEntryFindsTheTrackThatBeginsThere() throws IOException {
        list("""
                [ {"filename":"disc.wav","start":"00:03.600","title":"By start","author":"Someone"} ]""");
        List<Track> t = scan();   // track 2 starts at 3.493 s: within the tolerance
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Opening", "By start", "Finale");
        assertThat(t.get(1).tags().artist()).isEqualTo("Someone");

        list("""
                [ {"filename":"disc.wav","start":"00:05.000","title":"Too far"} ]""");
        assertThat(scan()).extracting(x -> x.tags().title()).containsExactly("Opening", "Middle", "Finale");
    }

    @Test
    void theIndexBeatsTheStartAndAWholeFileEntryOnlyTouchesTheAlbum() throws IOException {
        list("""
                [ {"filename":"disc.wav","start":"00:03.493","title":"By start"},
                  {"filename":"disc.wav","index":2,"start":"00:06.000","title":"By index"},
                  {"filename":"disc.wav","partOf":"New album","author":"New artist"} ]""");
        List<Track> t = scan();
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Opening", "By index", "Finale");
        assertThat(t).allSatisfy(x -> assertThat(x.tags().album()).isEqualTo("New album"));
        assertThat(t.get(2).tags().albumArtist()).isEqualTo("New artist");
    }

    @Test
    void editingTheSectionEntriesRebuildsTheAlbumOnTheNextScan() throws IOException {
        scan();
        list("""
                [ {"filename":"disc.wav","index":3,"title":"Closing"} ]""");
        assertThat(scan()).extracting(x -> x.tags().title()).containsExactly("Opening", "Middle", "Closing");
        Files.delete(cues.resolve("list.json"));
        assertThat(scan()).extracting(x -> x.tags().title()).containsExactly("Opening", "Middle", "Finale");
    }
}
