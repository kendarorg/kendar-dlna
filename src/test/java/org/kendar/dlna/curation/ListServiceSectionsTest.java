package org.kendar.dlna.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.ingestion.ListGenerator;
import org.kendar.dlna.ingestion.ListJson;
import org.kendar.dlna.ingestion.ScanProgress;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.CueFixtures;
import org.kendar.dlna.testutil.LibraryFixture;

/** Generating list.json entries from cue sheets and saving entries addressed to a section. */
class ListServiceSectionsTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    ListService lists;
    Path cues;
    final RootRef dir = RootRef.parse("main/Cues");
    final RootRef disc = RootRef.parse("main/Cues/disc.wav");

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        cues = lib.music.resolve("Cues");
        CueFixtures.write(cues);
        rescan();
        TagService tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
        lists = new FolderService(lib.catalog, lib.files, tags).lists();
    }

    private void rescan() {
        lib.catalog.commit(lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null,
                new ScanProgress(), false));
    }

    private List<Track> albumTracks() {
        return lib.catalog.current().children(disc).stream().map(c -> ((Child.TrackChild) c).track()).toList();
    }

    @Test
    void generatesOneEntryPerCueTrackWithFileIndexStartAndTheCueData() {
        ListGenerator.Result r = lists.generate(dir);

        assertThat(r.warnings()).isEmpty();
        assertThat(r.sources()).hasSize(1);
        assertThat(r.sources().get(0).filename()).isEqualTo("disc.wav");
        assertThat(r.sources().get(0).origin()).isEqualTo("cue");
        assertThat(r.sources().get(0).cueFile()).isEqualTo("disc.cue");
        assertThat(r.sources().get(0).tracks()).isEqualTo(3);
        assertThat(r.entries()).extracting(ListJson.Entry::filename).containsOnly("disc.wav");
        assertThat(r.entries()).extracting(ListJson.Entry::index).containsExactly(1, 2, 3);
        assertThat(r.entries()).extracting(ListJson.Entry::start).containsExactly("00:00.000", "00:03.493",
                "00:06.000");
        assertThat(r.entries()).extracting(ListJson.Entry::title).containsExactly("Opening", "Middle", "Finale");
        assertThat(r.entries()).extracting(ListJson.Entry::author).containsExactly("The Band", "Guest", "The Band");
        assertThat(r.entries()).extracting(ListJson.Entry::partOf).containsOnly("Live Album");
    }

    @Test
    void generatingIsAPreviewAndIgnoresWhatListJsonSaysNow() throws IOException {
        lists.save(dir, List.of(new ListJson.Entry("disc.wav", null, null, "Edited", null, 1, null)),
                ListService.NO_FILE);
        String before = Files.readString(cues.resolve("list.json"));

        ListGenerator.Result r = lists.generate(dir);

        assertThat(r.entries().get(0).title()).isEqualTo("Opening");   // the cue data, not the edit
        assertThat(Files.readString(cues.resolve("list.json"))).isEqualTo(before);
        assertThatThrownBy(() -> lists.generate(RootRef.parse("main/Nope"))).extracting("code")
                .isEqualTo(CurationException.NOT_FOUND);
    }

    @Test
    void aDirectoryWithoutSectionsGeneratesNothing() {
        ListGenerator.Result r = lists.generate(RootRef.parse("main/Album"));
        assertThat(r.entries()).isEmpty();
        assertThat(r.sources()).isEmpty();
    }

    @Test
    void theGeneratedEntriesCanBeSavedAndRoundTripAndAreEffectiveAtOnce() throws IOException {
        List<ListJson.Entry> proposal = lists.generate(dir).entries();
        List<ListJson.Entry> edited = new java.util.ArrayList<>(proposal);
        edited.set(1, new ListJson.Entry("disc.wav", "Guest", "Live Album", "My middle", null, 2, "00:03.493"));

        ListService.View v = lists.save(dir, edited, ListService.NO_FILE);

        assertThat(v.entries()).isEqualTo(edited);
        assertThat(v.files()).contains("disc.wav");   // a split file is a valid target although it is not a track
        assertThat(v.unknown()).isEmpty();
        String text = Files.readString(cues.resolve("list.json"));
        assertThat(text).contains("\"index\": 2").contains("\"start\": \"00:03.493\"");
        assertThat(ListJson.parseFile(cues.resolve("list.json")).entries()).isEqualTo(edited);
        assertThat(albumTracks()).extracting(t -> t.tags().title()).containsExactly("Opening", "My middle", "Finale");

        // and the next scan agrees
        rescan();
        assertThat(albumTracks()).extracting(t -> t.tags().title()).containsExactly("Opening", "My middle", "Finale");
        assertThat(lists.read(dir).entries()).hasSize(3);
    }

    @Test
    void wholeFileAndSectionEntriesOfTheSameFileCoexist() {
        List<ListJson.Entry> entries = List.of(
                new ListJson.Entry("disc.wav", "Artist", "The album", null, null),
                new ListJson.Entry("disc.wav", null, null, "Two", null, 2, null));
        ListService.View v = lists.save(dir, entries, ListService.NO_FILE);
        assertThat(v.entries()).hasSize(2);
        assertThat(albumTracks()).extracting(t -> t.tags().title()).containsExactly("Opening", "Two", "Finale");
        assertThat(albumTracks()).allSatisfy(t -> assertThat(t.tags().album()).isEqualTo("The album"));
    }

    @Test
    void validatesTheLocators() {
        assertThatThrownBy(() -> lists.save(dir, List.of(new ListJson.Entry("disc.wav", null, null, "t", null, 0,
                null)), null)).hasMessageContaining("index");
        assertThatThrownBy(() -> lists.save(dir, List.of(new ListJson.Entry("disc.wav", null, null, "t", null, 1,
                "soon")), null)).hasMessageContaining("start");
        assertThatThrownBy(() -> lists.save(dir, List.of(
                new ListJson.Entry("disc.wav", null, null, "a", null, 2, null),
                new ListJson.Entry("DISC.wav", null, null, "b", null, 2, "01:00")), null))
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> lists.save(dir, List.of(new ListJson.Entry("gone.wav", null, null, "t", null, 1,
                null)), null)).hasMessageContaining("no file 'gone.wav'");
        // two different sections of one file are not duplicates, and neither is the file itself
        lists.save(dir, List.of(new ListJson.Entry("disc.wav", null, null, "a", null, 1, null),
                new ListJson.Entry("disc.wav", null, null, "b", null, null, "00:03.493"),
                new ListJson.Entry("disc.wav", null, "alb", null, null)), null);
        assertThat(lists.read(dir).entries()).hasSize(3);
    }
}
