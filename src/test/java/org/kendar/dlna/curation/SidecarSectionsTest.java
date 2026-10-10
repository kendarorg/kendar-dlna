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

/** The per-file {@code disc.wav.json}: precedence, scanning, rescans on change / delete and the editing service. */
class SidecarSectionsTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    ListService lists;
    Path cues;
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

    private ScanProgress rescan() {
        ScanProgress p = new ScanProgress();
        lib.catalog.commit(lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null, p, false));
        return p;
    }

    private List<Track> album() {
        return lib.catalog.current().children(disc).stream().map(c -> ((Child.TrackChild) c).track()).toList();
    }

    private List<String> titles() {
        return album().stream().map(t -> t.tags().title()).toList();
    }

    private void sidecar(String json) throws IOException {
        Files.writeString(cues.resolve("disc.wav.json"), json);
    }

    // ---- scanning and precedence ----

    @Test
    void aSidecarOverridesTheCueSheetAndGivesTheAlbumLevelFields() throws IOException {
        sidecar("""
                [ {"partOf":"Sidecar album","author":"Sidecar band"},
                  {"index":2,"title":"Sidecar middle"} ]""");
        rescan();
        assertThat(titles()).containsExactly("Opening", "Sidecar middle", "Finale");
        assertThat(album()).allSatisfy(t -> assertThat(t.tags().album()).isEqualTo("Sidecar album"));
        assertThat(album().get(1).tags().artist()).isEqualTo("Guest");   // untouched cue data
        assertThat(lib.catalog.current().folder(disc).orElseThrow().settings().displayName())
                .isEqualTo("Sidecar album");
    }

    @Test
    void theSidecarWinsOverTheDirectoryListJsonForItsFileAndTheListIsNotConsultedForIt() throws IOException {
        Files.writeString(cues.resolve("list.json"), """
                [ {"filename":"disc.wav","index":1,"title":"List one"},
                  {"filename":"disc.wav","index":3,"title":"List three"} ]""");
        rescan();
        assertThat(titles()).containsExactly("List one", "Middle", "List three");

        sidecar("[{\"index\":1,\"title\":\"Sidecar one\"}]");
        rescan();
        assertThat(titles()).containsExactly("Sidecar one", "Middle", "Finale");   // list.json entries for the file ignored

        Files.writeString(cues.resolve("list.json"),
                "[{\"filename\":\"disc.wav\",\"index\":3,\"title\":\"List three\"}]");
        Files.delete(cues.resolve("disc.wav.json"));
        rescan();
        assertThat(titles()).containsExactly("Opening", "Middle", "List three");
    }

    @Test
    void aBrokenSidecarIsIgnoredAndTheScanSurvives() throws IOException {
        Files.writeString(cues.resolve("list.json"),
                "[{\"filename\":\"disc.wav\",\"index\":1,\"title\":\"List one\"}]");
        sidecar("[{\"index\": ");
        rescan();
        assertThat(titles()).containsExactly("List one", "Middle", "Finale");
    }

    @Test
    void editingCreatingAndDeletingTheSidecarRescansTheFileEvenWhenTheAudioDidNotChange() throws IOException {
        long id = lib.catalog.current().updateId();
        sidecar("[{\"index\":1,\"title\":\"First\"}]");
        rescan();
        assertThat(titles().get(0)).isEqualTo("First");
        assertThat(lib.catalog.current().updateId()).isGreaterThan(id);
        assertThat(lib.catalog.current().folder(RootRef.parse("main/Cues")).orElseThrow().listHash()).isNotNull();

        sidecar("[{\"index\":1,\"title\":\"Second\"}]");
        rescan();
        assertThat(titles().get(0)).isEqualTo("Second");

        // unchanged: the album is reused, not rebuilt
        ScanProgress p = rescan();
        assertThat(p.tracksReused()).isPositive();
        assertThat(titles().get(0)).isEqualTo("Second");

        Files.delete(cues.resolve("disc.wav.json"));
        rescan();
        assertThat(titles()).containsExactly("Opening", "Middle", "Finale");
        assertThat(lib.catalog.current().folder(RootRef.parse("main/Cues")).orElseThrow().listHash()).isNull();
    }

    @Test
    void aWholeFileSidecarTitlesAnUnsplitFileToo() throws IOException {
        Path album = lib.music.resolve("Album");
        Track first = lib.catalog.current().tracks().stream().filter(t -> t.ref().parent().rel().equals("Album"))
                .findFirst().orElseThrow();
        String name = first.fileName();
        Files.writeString(album.resolve(name + ".json"), "[{\"title\":\"Sidecar title\",\"author\":\"Me\"}]");
        rescan();
        Track t = lib.catalog.current().track(first.ref()).orElseThrow();
        assertThat(t.tags().title()).isEqualTo("Sidecar title");
        assertThat(t.tags().artist()).isEqualTo("Me");
        Files.delete(album.resolve(name + ".json"));
        rescan();
        assertThat(lib.catalog.current().track(t.ref()).orElseThrow().tags().title()).isNotEqualTo("Sidecar title");
    }

    // ---- the service ----

    @Test
    void readsAnEmptyViewForAFileWithoutSidecar() {
        ListService.FileView v = lists.readFile(disc);
        assertThat(v.exists()).isFalse();
        assertThat(v.version()).isEqualTo(ListService.NO_FILE);
        assertThat(v.fileName()).isEqualTo("disc.wav");
        assertThat(v.sidecar()).isEqualTo("disc.wav.json");
        assertThat(v.split()).isTrue();
        assertThat(v.entries()).isEmpty();
    }

    @Test
    void generatesTheSameEntriesAsTheDirectoryGeneratorForOneFileAndWritesNothing() {
        ListGenerator.Result r = lists.generateFile(disc);
        assertThat(r.entries()).isEqualTo(lists.generate(RootRef.parse("main/Cues")).entries());
        assertThat(r.entries()).hasSize(3);
        assertThat(r.sources()).singleElement().satisfies(s -> assertThat(s.filename()).isEqualTo("disc.wav"));
        assertThat(cues.resolve("disc.wav.json")).doesNotExist();
    }

    @Test
    void savedEntriesAreWrittenAtomicallyEffectiveAtOnceAndAgreeWithTheNextScan() throws IOException {
        List<ListJson.Entry> proposal = lists.generateFile(disc).entries();
        List<ListJson.Entry> edited = new java.util.ArrayList<>(proposal);
        edited.set(1, new ListJson.Entry("disc.wav", "Guest", "Live Album", "My middle", null, 2, "00:03.493"));
        edited.add(new ListJson.Entry(null, "Whole band", null, null, null));

        ListService.FileView v = lists.saveFile(disc, edited, ListService.NO_FILE);

        assertThat(v.exists()).isTrue();
        assertThat(v.entries()).hasSize(4);
        assertThat(v.entries()).extracting(ListJson.Entry::filename).containsOnly("disc.wav");
        String text = Files.readString(cues.resolve("disc.wav.json"));
        assertThat(text).contains("\"index\": 2").contains("\"start\": \"00:03.493\"");
        assertThat(cues.resolve("list.json")).doesNotExist();
        try (var s = Files.list(cues)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
        assertThat(titles()).containsExactly("Opening", "My middle", "Finale");
        assertThat(album().get(0).tags().albumArtist()).isEqualTo("Whole band");

        rescan();
        assertThat(titles()).containsExactly("Opening", "My middle", "Finale");
        assertThat(lists.readFile(disc).version()).isEqualTo(v.version());
        assertThat(lists.read(RootRef.parse("main/Cues")).sidecars()).containsExactly("disc.wav");
    }

    @Test
    void savingOnlyTouchesThatFileAndNotTheDirectoryListJson() throws IOException {
        lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, "Only here", null, 1, null)), null);
        assertThat(titles().get(0)).isEqualTo("Only here");
        assertThat(lists.read(RootRef.parse("main/Cues")).entries()).isEmpty();
        assertThat(cues.resolve("list.json")).doesNotExist();
    }

    @Test
    void anEmptyListDeletesTheSidecarAndRestoresTheCueData() throws IOException {
        ListService.FileView v = lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, "X", null, 1,
                null)), null);
        assertThat(cues.resolve("disc.wav.json")).exists();
        ListService.FileView gone = lists.saveFile(disc, List.of(), v.version());
        assertThat(gone.exists()).isFalse();
        assertThat(cues.resolve("disc.wav.json")).doesNotExist();
        assertThat(titles()).containsExactly("Opening", "Middle", "Finale");
        // entries that say nothing are dropped, so a blank-only list deletes too
        lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, "X", null, 1, null)), null);
        lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, null, null, 1, null)), null);
        assertThat(cues.resolve("disc.wav.json")).doesNotExist();
    }

    @Test
    void aStaleVersionIsAConflictAndNothingIsOverwritten() throws IOException {
        sidecar("[{\"index\":1,\"title\":\"Theirs\"}]");
        assertThatThrownBy(() -> lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, "Mine", null, 1,
                null)), ListService.NO_FILE)).isInstanceOf(CurationException.class).extracting("code")
                .isEqualTo(CurationException.CONFLICT);
        assertThat(Files.readString(cues.resolve("disc.wav.json"))).contains("Theirs");
        String version = lists.readFile(disc).version();
        lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, "Mine", null, 1, null)), version);
        assertThat(Files.readString(cues.resolve("disc.wav.json"))).contains("Mine");
    }

    @Test
    void validatesEntriesLocatorsAndTheFileName() {
        assertThatThrownBy(() -> lists.saveFile(disc, List.of(new ListJson.Entry("other.wav", null, null, "t", null,
                1, null)), null)).hasMessageContaining("describes only 'disc.wav'");
        assertThatThrownBy(() -> lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, "t", null, 0,
                null)), null)).hasMessageContaining("index");
        assertThatThrownBy(() -> lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, "t", null, 1,
                "soon")), null)).hasMessageContaining("start");
        assertThatThrownBy(() -> lists.saveFile(disc, List.of(
                new ListJson.Entry(null, null, null, "a", null, 2, null),
                new ListJson.Entry("DISC.wav", null, null, "b", null, 2, "01:00")), null))
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> lists.saveFile(disc, List.of(new ListJson.Entry(null, "x".repeat(501), null, "t",
                null, 1, null)), null)).hasMessageContaining("at most");
        assertThatThrownBy(() -> lists.saveFile(disc, null, null)).extracting("code")
                .isEqualTo(CurationException.INVALID);
        assertThat(cues.resolve("disc.wav.json")).doesNotExist();
    }

    @Test
    void onlyKnownAudioFilesCanHaveASidecar() {
        for (String bad : new String[] { "main/Cues", "main/Cues/disc.cue", "main/Cues/missing.wav", "main" }) {
            assertThatThrownBy(() -> lists.readFile(RootRef.parse(bad))).as(bad).extracting("code")
                    .isEqualTo(CurationException.NOT_FOUND);
        }
        assertThatThrownBy(() -> lists.saveFile(RootRef.parse("main/Cues/missing.wav"), List.of(), null))
                .extracting("code").isEqualTo(CurationException.NOT_FOUND);
        assertThatThrownBy(() -> lists.generateFile(RootRef.parse("main/Nope/x.wav"))).extracting("code")
                .isEqualTo(CurationException.NOT_FOUND);
    }

    @Test
    void theDirectoryListJsonStillWorksAndSavingItKeepsTheSidecar() {
        lists.saveFile(disc, List.of(new ListJson.Entry(null, null, null, "Sidecar one", null, 1, null)), null);
        RootRef dir = RootRef.parse("main/Cues");
        lists.save(dir, List.of(new ListJson.Entry("disc.wav", null, null, "List three", null, 3, null),
                new ListJson.Entry("disc.wav", null, null, "Ignored list one", null, 1, null)), ListService.NO_FILE);
        assertThat(titles()).containsExactly("Sidecar one", "Middle", "Finale");   // list.json entries are shadowed
        assertThat(cues.resolve("disc.wav.json")).exists();
        lists.saveFile(disc, List.of(), null);
        assertThat(titles()).containsExactly("Ignored list one", "Middle", "List three");
    }
}
