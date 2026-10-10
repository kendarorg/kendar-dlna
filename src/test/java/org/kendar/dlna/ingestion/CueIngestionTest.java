package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.testutil.CueFixtures;
import org.kendar.dlna.testutil.LibraryFixture;

/** Cue sheets next to a single audio file become virtual albums in the catalog. */
class CueIngestionTest {

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
        rescan(false);
    }

    private ScanProgress rescan(boolean force) {
        ScanProgress p = new ScanProgress();
        Snapshot s = lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null, p, force);
        lib.catalog.commit(s);
        return p;
    }

    private Snapshot snap() {
        return lib.catalog.current();
    }

    private List<Track> albumTracks() {
        return snap().children(disc).stream().map(c -> ((Child.TrackChild) c).track()).toList();
    }

    @Test
    void theFileBecomesAnAlbumContainerWithOneTrackPerCueTrackAndTheWholeFileIsHidden() {
        Folder album = snap().folder(disc).orElseThrow();
        assertThat(album.isVirtual()).isTrue();
        assertThat(album.split().origin()).isEqualTo("cue");
        assertThat(album.split().cueFile()).isEqualTo("disc.cue");
        assertThat(DisplayNames.folderTitle(album)).isEqualTo("Live Album");
        // the album sits inside its directory, between the real children, and the whole file is not a track
        assertThat(snap().children(RootRef.parse("main/Cues"))).hasSize(1)
                .first().isInstanceOf(Child.FolderChild.class);
        assertThat(snap().track(disc)).isEmpty();

        List<Track> t = albumTracks();
        assertThat(t).extracting(Track::fileName).containsExactly("disc.wav#1", "disc.wav#2", "disc.wav#3");
        assertThat(t).extracting(x -> x.ref().qualified()).containsExactly("main/Cues/disc.wav#1",
                "main/Cues/disc.wav#2", "main/Cues/disc.wav#3");
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Opening", "Middle", "Finale");
        assertThat(t).extracting(x -> x.tags().artist()).containsExactly("The Band", "Guest", "The Band");
        assertThat(t).allSatisfy(x -> {
            assertThat(x.tags().album()).isEqualTo("Live Album");
            assertThat(x.tags().albumArtist()).isEqualTo("The Band");
            assertThat(x.tags().genre()).isEqualTo("Rock");
            assertThat(x.tags().year()).isEqualTo(1999);
            assertThat(x.sampleRate()).isEqualTo(CueFixtures.RATE);
            assertThat(x.parentRef()).isEqualTo(disc);
            assertThat(x.fileRef()).isEqualTo(disc);
        });
        assertThat(t).extracting(x -> x.tags().trackNo()).containsExactly(1, 2, 3);
    }

    @Test
    void segmentsAreSampleExactAndTheDurationsAddUp() {
        List<Track> t = albumTracks();
        assertThat(t.get(0).segment().startSample()).isZero();
        assertThat(t.get(0).segment().endSample()).isEqualTo(CueFixtures.STARTS[1]);
        assertThat(t.get(1).segment().startSample()).isEqualTo(CueFixtures.STARTS[1]);
        assertThat(t.get(1).segment().endSample()).isEqualTo(CueFixtures.STARTS[2]);
        assertThat(t.get(2).segment().endSample()).isEqualTo(CueFixtures.FRAMES);
        assertThat(t).extracting(Track::sampleCount).containsExactly(154_056L, 110_544L, 176_400L);
        assertThat(t.get(2).durationMillis()).isEqualTo(4000);
        assertThat(t.get(1).segment().startMillis()).isEqualTo(3493);
        assertThat(t.get(1).segment().endMillis()).isEqualTo(6000);
        assertThat(t.stream().mapToLong(Track::durationMillis).sum()).isBetween(9998L, 10002L);
        assertThat(t.get(0).isSegment()).isTrue();
    }

    @Test
    void virtualTracksHaveStableIdsAndAreFoundByIdAndByRef() {
        Track two = albumTracks().get(1);
        assertThat(two.id()).isEqualTo(Track.idOf(RootRef.parse("main/Cues/disc.wav#2")));
        assertThat(snap().trackById(two.id())).contains(two);
        assertThat(snap().track(RootRef.parse("main/Cues/disc.wav#2"))).contains(two);

        rescan(true);
        assertThat(snap().track(RootRef.parse("main/Cues/disc.wav#2")).orElseThrow().id()).isEqualTo(two.id());
    }

    @Test
    void anUnchangedRescanReusesTheSplitWithoutReadingTheFile() {
        ScanProgress p = rescan(false);
        assertThat(p.tracksReused()).isGreaterThanOrEqualTo(1);
        assertThat(albumTracks()).hasSize(3);
        assertThat(snap().folder(disc).orElseThrow().isVirtual()).isTrue();
    }

    @Test
    void editingTheCueRescansTheAlbum() throws IOException {
        Files.writeString(cues.resolve("disc.cue"), CueFixtures.CUE.replace("Middle", "Centre")
                .replace("INDEX 01 00:06:00", "INDEX 01 00:08:00"), StandardCharsets.UTF_8);

        rescan(false);

        assertThat(albumTracks()).extracting(x -> x.tags().title()).containsExactly("Opening", "Centre", "Finale");
        assertThat(albumTracks().get(2).segment().startSample()).isEqualTo(8 * 44100L);
        assertThat(albumTracks().get(2).durationMillis()).isEqualTo(2000);
    }

    @Test
    void addingOrRemovingTheCueSwitchesBetweenWholeFileAndAlbum() throws IOException {
        Files.delete(cues.resolve("disc.cue"));
        rescan(false);
        assertThat(snap().folder(disc)).isEmpty();
        assertThat(snap().track(disc)).isPresent();
        assertThat(snap().track(RootRef.parse("main/Cues/disc.wav#1"))).isEmpty();

        Files.writeString(cues.resolve("disc.cue"), CueFixtures.CUE, StandardCharsets.UTF_8);
        rescan(false);
        assertThat(snap().folder(disc).orElseThrow().isVirtual()).isTrue();
        assertThat(snap().track(disc)).isEmpty();
        assertThat(albumTracks()).hasSize(3);
    }

    @Test
    void keepWholeFilesListsTheWholeFileNextToItsAlbum() throws IOException {
        Files.writeString(cues.resolve("settings.json"), "{\"keepWholeFiles\": true}");
        rescan(false);

        assertThat(snap().track(disc)).isPresent();
        assertThat(snap().folder(disc).orElseThrow().isVirtual()).isTrue();
        assertThat(albumTracks()).hasSize(3);
        assertThat(snap().children(RootRef.parse("main/Cues"))).hasSize(2);

        Files.writeString(cues.resolve("settings.json"), "{}");
        rescan(false);
        assertThat(snap().track(disc)).isEmpty();
        assertThat(albumTracks()).hasSize(3);
    }

    @Test
    void withoutVirtualAlbumsTheWholeFileStaysAndCuesAreIgnored() {
        LibraryScanner off = new LibraryScanner(lib.reader, lib.clock, false);
        Snapshot s = off.scan(lib.roots, lib.playlists, 1);
        assertThat(s.track(disc)).isPresent();
        assertThat(s.folder(disc)).isEmpty();
    }

    @Test
    void cueNamingAWavWhileTheFileIsFlacStillMatchesBySiblingName() throws IOException {
        Path dir = lib.music.resolve("Flac");
        Files.createDirectories(dir);
        AudioFiles.fixture("sweep.flac", dir.resolve("disc.flac"));
        Files.writeString(dir.resolve("disc.cue"), """
                TITLE "Sweeps"
                FILE "disc.wav" WAVE
                  TRACK 01 AUDIO
                    INDEX 01 00:00:00
                  TRACK 02 AUDIO
                    TITLE "Second half"
                    INDEX 01 00:01:00
                """);
        rescan(false);

        RootRef f = RootRef.parse("main/Flac/disc.flac");
        assertThat(snap().folder(f).orElseThrow().isVirtual()).isTrue();
        List<Track> t = snap().children(f).stream().map(c -> ((Child.TrackChild) c).track()).toList();
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Track 01", "Second half");
        assertThat(t.get(0).segment().endSample()).isEqualTo(44100);
        assertThat(t.get(1).segment().endSample()).isEqualTo(t.get(1).segment().startSample() + t.get(1).sampleCount());
        assertThat(t.get(1).tags().album()).isEqualTo("Sweeps");
    }

    @Test
    void aCueWithAMissingAudioFileIsIgnoredWithoutFailingTheScan() throws IOException {
        Path dir = lib.music.resolve("Missing");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("gone.cue"), CueFixtures.CUE.replace("disc.wav", "gone.wav"));
        AudioFiles.sineWav(dir.resolve("other.wav"), 44100, 2, 44100, 440);

        ScanProgress p = rescan(false);

        assertThat(snap().track(RootRef.parse("main/Missing/other.wav"))).isPresent();
        assertThat(snap().children(RootRef.parse("main/Missing"))).hasSize(1);
        assertThat(albumTracks()).hasSize(3);
        assertThat(p).isNotNull();
    }

    @Test
    void aSingleTrackCueOnlySuppliesTagsToTheWholeFile() throws IOException {
        Path dir = lib.music.resolve("One");
        Files.createDirectories(dir);
        AudioFiles.sineWav(dir.resolve("one.wav"), 44100, 2, 44100, 440);
        Files.writeString(dir.resolve("one.cue"), """
                PERFORMER "Solo"
                TITLE "Single Album"
                FILE "one.wav" WAVE
                  TRACK 01 AUDIO
                    TITLE "Only Song"
                    INDEX 01 00:00:00
                """);
        rescan(false);

        RootRef r = RootRef.parse("main/One/one.wav");
        assertThat(snap().folder(r)).isEmpty();
        Track t = snap().track(r).orElseThrow();
        assertThat(t.tags().title()).isEqualTo("Only Song");
        assertThat(t.tags().album()).isEqualTo("Single Album");
        assertThat(t.tags().artist()).isEqualTo("Solo");
    }

    @Test
    void listJsonWinsOverTheCue_fileLevelForTheAlbum_trackLevelPerTrack() throws IOException {
        Files.writeString(cues.resolve("list.json"), """
                [{"filename": "disc.wav", "partOf": "Renamed Album", "author": "List Band"},
                 {"filename": "disc.wav#2", "title": "List Title"}]
                """);
        rescan(false);

        assertThat(DisplayNames.folderTitle(snap().folder(disc).orElseThrow())).isEqualTo("Renamed Album");
        List<Track> t = albumTracks();
        assertThat(t).allSatisfy(x -> {
            assertThat(x.tags().album()).isEqualTo("Renamed Album");
            assertThat(x.tags().albumArtist()).isEqualTo("List Band");
        });
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Opening", "List Title", "Finale");
    }

    @Test
    void rescanningTheVirtualAlbumScopeRebuildsItsDirectory() {
        ScanProgress p = new ScanProgress();
        Snapshot s = lib.scanner.rescan(lib.roots, lib.playlists, snap(), disc, p, true);
        lib.catalog.commit(s);
        assertThat(albumTracks()).hasSize(3);
        assertThat(snap().folder(disc).orElseThrow().isVirtual()).isTrue();
    }

    @Test
    void theAlbumSurvivesThePersistedIndex() {
        JsonCatalogStore store = new JsonCatalogStore(tmp.resolve("data"), "fp", "test", false);
        store.save(snap());
        Snapshot loaded = store.load().orElseThrow();

        assertThat(loaded.folder(disc).orElseThrow().split()).isEqualTo(snap().folder(disc).orElseThrow().split());
        assertThat(loaded.track(RootRef.parse("main/Cues/disc.wav#2")).orElseThrow())
                .isEqualTo(snap().track(RootRef.parse("main/Cues/disc.wav#2")).orElseThrow());
        // a rescan on top of the loaded index reuses the split
        lib.catalog.commit(loaded);
        assertThat(rescan(false).tracksReused()).isGreaterThanOrEqualTo(1);
        assertThat(albumTracks()).hasSize(3);
    }

    @Test
    void theFileDisappearingRemovesTheAlbum() throws IOException {
        Files.delete(cues.resolve("disc.wav"));
        rescan(false);
        assertThat(snap().folder(disc)).isEmpty();
        assertThat(snap().tracks().stream().filter(Track::isSegment)).isEmpty();
    }

    @Test
    void removingAVanishedVirtualTrackDropsTheWholeAlbum() {
        lib.catalog.removeTrack(RootRef.parse("main/Cues/disc.wav#2"));
        assertThat(snap().folder(disc)).isEmpty();
        assertThat(snap().tracks().stream().filter(Track::isSegment)).isEmpty();
    }
}
