package org.kendar.dlna.curation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.ingestion.ListGenerator;
import org.kendar.dlna.ingestion.ListJson;
import org.kendar.dlna.ingestion.ScanProgress;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.ChapterFiles;
import org.kendar.dlna.testutil.ChapterFiles.Chap;
import org.kendar.dlna.testutil.LibraryFixture;

/**
 * Embedded chapters (ID3 CHAP/CTOC, MP4 chpl, Vorbis CUESHEET / CHAPTERnnn) through list generation, list.json
 * matching and ingestion into virtual albums. All fixtures are 2 s long.
 */
class EmbeddedChaptersListTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    ListService lists;
    Path dirPath;
    final RootRef dir = RootRef.parse("main/Chap");

    @BeforeEach
    void setUp() throws IOException {
        lib = new LibraryFixture(tmp);
        dirPath = Files.createDirectories(lib.music.resolve("Chap"));
        TagService tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
        lists = new FolderService(lib.catalog, lib.files, tags).lists();
    }

    private Snapshot rescan() {
        Snapshot s = lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null, new ScanProgress(),
                false);
        lib.catalog.commit(s);
        return s;
    }

    private List<Track> album(String file) {
        Snapshot s = rescan();
        return s.children(dir.child(file)).stream().map(c -> ((Child.TrackChild) c).track()).toList();
    }

    private ListGenerator.Result generate() {
        rescan();
        return lists.generate(dir);
    }

    private Path book() {
        return ChapterFiles.mp3Chapters(dirPath.resolve("book.mp3"), true,
                new Chap("c1", 0, 700, "Intro", "Narrator"), new Chap("c2", 700, 1400, "Middle", "Guest"),
                new Chap("c3", 1400, 2000, "End"));
    }

    // ---- (a) generation ----

    @Test
    void generatesOneEntryPerChapterWithIndexStartTitleAndAuthor() {
        book();

        ListGenerator.Result r = generate();

        assertThat(r.warnings()).isEmpty();
        assertThat(r.sources()).hasSize(1);
        assertThat(r.sources().get(0).filename()).isEqualTo("book.mp3");
        assertThat(r.sources().get(0).origin()).isEqualTo("chapters");
        assertThat(r.sources().get(0).cueFile()).isNull();
        assertThat(r.sources().get(0).tracks()).isEqualTo(3);
        assertThat(r.entries()).extracting(ListJson.Entry::filename).containsOnly("book.mp3");
        assertThat(r.entries()).extracting(ListJson.Entry::index).containsExactly(1, 2, 3);
        assertThat(r.entries()).extracting(ListJson.Entry::start).containsExactly("00:00.000", "00:00.700",
                "00:01.400");
        assertThat(r.entries()).extracting(ListJson.Entry::title).containsExactly("Intro", "Middle", "End");
        assertThat(r.entries().get(0).author()).isEqualTo("Narrator");
        assertThat(r.entries().get(1).author()).isEqualTo("Guest");
        assertThat(r.entries().get(2).author()).isNotEqualTo("Guest");   // falls back to the file's own artist
        assertThat(r.entries()).extracting(ListJson.Entry::partOf).containsOnly(r.entries().get(0).partOf());
        assertThat(r.entries().get(0).partOf()).isNotBlank();
        assertThat(r.entries()).extracting(ListJson.Entry::transcriber).hasSize(3);
    }

    @Test
    void generatesFromMp4ChplAndVorbisChapterTags() {
        ChapterFiles.m4a(dirPath.resolve("a.m4a"), new Object[] {0L, "Alpha"}, new Object[] {900L, "Beta"});
        ChapterFiles.vorbis("sweep.ogg", dirPath.resolve("o.ogg"), "CHAPTER001", "00:00:00.000", "CHAPTER001NAME",
                "One", "CHAPTER002", "00:00:01.000", "CHAPTER002NAME", "Two");
        ChapterFiles.vorbis("sweep.flac", dirPath.resolve("f.flac"), "CHAPTER002", "00:00:01.250", "CHAPTER001",
                "00:00:00.000", "CHAPTER002NAME", "Zwei", "CHAPTER001NAME", "Eins");

        ListGenerator.Result r = generate();

        assertThat(r.warnings()).isEmpty();
        assertThat(r.sources()).extracting(ListGenerator.Source::filename).containsExactly("a.m4a", "f.flac",
                "o.ogg");
        assertThat(r.sources()).extracting(ListGenerator.Source::origin).containsOnly("chapters");
        assertThat(r.entries()).extracting(e -> e.filename() + "|" + e.index() + "|" + e.start() + "|" + e.title())
                .containsExactly("a.m4a|1|00:00.000|Alpha", "a.m4a|2|00:00.900|Beta", "f.flac|1|00:00.000|Eins",
                        "f.flac|2|00:01.250|Zwei", "o.ogg|1|00:00.000|One", "o.ogg|2|00:01.000|Two");
    }

    @Test
    void precedenceIsCueFileThenTagCuesheetThenChapters() throws IOException {
        String cueTag = "TITLE \"Tagged\"\nFILE \"x.flac\" WAVE\n TRACK 01 AUDIO\n  TITLE \"TagA\"\n  INDEX 01 00:00:00\n"
                + " TRACK 02 AUDIO\n  TITLE \"TagB\"\n  INDEX 01 00:01:00\n";
        // tag cuesheet and chapter tags together: the cuesheet wins
        ChapterFiles.vorbis("sweep.flac", dirPath.resolve("both.flac"), "CUESHEET", cueTag, "CHAPTER001",
                "00:00:00.000", "CHAPTER001NAME", "ChapA", "CHAPTER002", "00:00:00.500", "CHAPTER002NAME", "ChapB");
        // the same plus a cue file next to it: the cue file wins
        ChapterFiles.vorbis("sweep.flac", dirPath.resolve("all.flac"), "CUESHEET", cueTag, "CHAPTER001",
                "00:00:00.000", "CHAPTER001NAME", "ChapA", "CHAPTER002", "00:00:00.500", "CHAPTER002NAME", "ChapB");
        Files.writeString(dirPath.resolve("all.cue"), "TITLE \"File cue\"\nFILE \"all.flac\" WAVE\n TRACK 01 AUDIO\n"
                + "  TITLE \"CueA\"\n  INDEX 01 00:00:00\n TRACK 02 AUDIO\n  TITLE \"CueB\"\n  INDEX 01 00:00:30\n");
        // an mp3 with chapters and a cue file: the cue file wins
        book();
        Files.writeString(dirPath.resolve("book.cue"), "FILE \"book.mp3\" MP3\n TRACK 01 AUDIO\n  TITLE \"CueOne\"\n"
                + "  INDEX 01 00:00:00\n TRACK 02 AUDIO\n  TITLE \"CueTwo\"\n  INDEX 01 00:01:00\n");

        ListGenerator.Result r = generate();

        assertThat(r.sources()).extracting(ListGenerator.Source::filename).containsExactly("all.flac", "book.mp3",
                "both.flac");
        assertThat(r.sources()).extracting(ListGenerator.Source::origin).containsExactly("cue", "cue", "tag-cue");
        assertThat(r.sources().get(0).cueFile()).isEqualTo("all.cue");
        assertThat(r.entries()).extracting(e -> e.filename() + "|" + e.title()).containsExactly("all.flac|CueA",
                "all.flac|CueB", "book.mp3|CueOne", "book.mp3|CueTwo", "both.flac|TagA", "both.flac|TagB");
        assertThat(r.entries().stream().filter(e -> e.filename().equals("both.flac")).map(ListJson.Entry::partOf))
                .containsOnly("Tagged");
    }

    // ---- (d) edge cases ----

    @Test
    void noCtocUnorderedChaptersAndMissingTitles() {
        ChapterFiles.mp3Chapters(dirPath.resolve("u.mp3"), false, new Chap("z", 1200, 2000, "Last"),
                new Chap("a", 0, 600, null), new Chap("m", 600, 1200, "  "));

        ListGenerator.Result r = generate();

        assertThat(r.entries()).extracting(ListJson.Entry::index).containsExactly(1, 2, 3);
        assertThat(r.entries()).extracting(ListJson.Entry::start).containsExactly("00:00.000", "00:00.600",
                "00:01.200");
        assertThat(r.entries()).extracting(ListJson.Entry::title).containsExactly("Track 01", "Track 02", "Last");
    }

    @Test
    void ctocOrderListsOnlyTheTopLevelChapters() {
        ChapterFiles.mp3(dirPath.resolve("t.mp3"), ChapterFiles.chap(new Chap("a", 0, 500, "A")),
                ChapterFiles.chap(new Chap("hidden", 300, 400, "Hidden")),
                ChapterFiles.chap(new Chap("b", 500, 900, "B")), ChapterFiles.ctoc("a", "b"));

        assertThat(generate().entries()).extracting(ListJson.Entry::title).containsExactly("A", "B");
    }

    @Test
    void overlappingZeroLengthAndDuplicateStartChaptersStillGiveIncreasingSections() {
        // c1 overlaps c2 (its end is after c2 starts), c3 has zero length, c4 starts at the same time as c3
        ChapterFiles.mp3Chapters(dirPath.resolve("o.mp3"), true, new Chap("c1", 0, 1500, "One"),
                new Chap("c2", 500, 800, "Two"), new Chap("c3", 1000, 1000, "Three"),
                new Chap("c4", 1000, 1900, "Four"));

        ListGenerator.Result r = generate();

        assertThat(r.entries()).extracting(ListJson.Entry::index).containsExactly(1, 2, 3);
        assertThat(r.entries()).extracting(ListJson.Entry::start).containsExactly("00:00.000", "00:00.500",
                "00:01.000");
        assertThat(r.entries()).extracting(ListJson.Entry::title).containsExactly("One", "Two", "Three");

        List<Track> t = album("o.mp3");
        assertThat(t).hasSize(3);
        for (int i = 0; i < t.size(); i++) {
            assertThat(t.get(i).segment().endSample()).isGreaterThan(t.get(i).segment().startSample());
            if (i > 0) {
                assertThat(t.get(i).segment().startSample()).isEqualTo(t.get(i - 1).segment().endSample());
            }
        }
    }

    @Test
    void aSingleChapterOrChapterStartingBeyondTheEndIsNotASplit() {
        ChapterFiles.mp3Chapters(dirPath.resolve("one.mp3"), true, new Chap("a", 0, 2000, "Only"));
        ChapterFiles.mp3Chapters(dirPath.resolve("late.mp3"), true, new Chap("a", 0, 1000, "In"),
                new Chap("b", 600_000, 700_000, "Beyond"));

        ListGenerator.Result r = generate();

        assertThat(r.sources()).extracting(ListGenerator.Source::filename).doesNotContain("one.mp3", "late.mp3");
        assertThat(r.entries()).extracting(ListJson.Entry::filename).doesNotContain("one.mp3", "late.mp3");
        Snapshot s = rescan();
        assertThat(s.track(dir.child("one.mp3"))).isPresent();
        assertThat(s.track(dir.child("late.mp3"))).isPresent();
        assertThat(s.folder(dir.child("one.mp3"))).isEmpty();
        assertThat(s.folder(dir.child("late.mp3"))).isEmpty();
    }

    // ---- (b) end to end: generated list.json overrides ----

    @Test
    void theGeneratedListIsSavedMatchedByIndexAndByStartAndOverridesTitles() throws IOException {
        book();
        List<ListJson.Entry> edited = new ArrayList<>(generate().entries());
        edited.set(0, new ListJson.Entry("book.mp3", "Narrator", edited.get(0).partOf(), "Prologue", "Tom", 1,
                "00:00.000"));
        // the right index, a stale start
        edited.set(1, new ListJson.Entry("book.mp3", null, null, "By index", null, 2, "00:05.000"));
        // no index: located by its start, 0.4 s off the 1.4 s chapter (within the half second tolerance)
        edited.set(2, new ListJson.Entry("book.mp3", null, null, "By start", null, null, "00:01.800"));

        ListService.View v = lists.save(dir, edited, ListService.NO_FILE);

        assertThat(v.entries()).isEqualTo(edited);
        assertThat(v.files()).contains("book.mp3");
        List<Track> t = album("book.mp3");
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Prologue", "By index", "By start");
        assertThat(t.get(0).tags().transcriber()).isEqualTo("Tom");
        assertThat(t.get(0).tags().artist()).isEqualTo("Narrator");
        assertThat(t.get(1).tags().artist()).isEqualTo("Guest");   // untouched chapter data

        // beyond the tolerance (0.6 s) the entry matches nothing
        edited.set(2, new ListJson.Entry("book.mp3", null, null, "Too far", null, null, "00:02.000"));
        lists.save(dir, edited, v.version());
        assertThat(album("book.mp3")).extracting(x -> x.tags().title()).containsExactly("Prologue", "By index",
                "End");

        // the list is in the file and survives a forced rescan
        assertThat(ListJson.parseFile(dirPath.resolve("list.json")).entries()).hasSize(3);
        lib.catalog.commit(lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null,
                new ScanProgress(), true));
        assertThat(lib.catalog.current().children(dir.child("book.mp3")).stream()
                .map(c -> ((Child.TrackChild) c).track().tags().title())).containsExactly("Prologue", "By index",
                        "End");
    }

    @Test
    void aWholeFileEntryRetitlesTheChapterAlbum() throws IOException {
        book();
        rescan();
        lists.save(dir, List.of(new ListJson.Entry("book.mp3", "Reader", "The Book", null, null)),
                ListService.NO_FILE);

        List<Track> t = album("book.mp3");

        assertThat(t).extracting(x -> x.tags().album()).containsOnly("The Book");
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Intro", "Middle", "End");
        assertThat(t.get(2).tags().albumArtist()).isEqualTo("Reader");
        assertThat(lib.catalog.current().folder(dir.child("book.mp3")).orElseThrow().settings().displayName())
                .isEqualTo("The Book");
    }

    // ---- (c) ingestion ----

    @Test
    void chapterFilesBecomeVirtualAlbumsForEveryFormat() {
        book();
        ChapterFiles.m4a(dirPath.resolve("a.m4a"), new Object[] {0L, "Alpha"}, new Object[] {900L, "Beta"});
        ChapterFiles.vorbis("sweep.ogg", dirPath.resolve("o.ogg"), "CHAPTER001", "00:00:00.000", "CHAPTER001NAME",
                "One", "CHAPTER002", "00:00:01.000", "CHAPTER002NAME", "Two");
        ChapterFiles.vorbis("sweep.flac", dirPath.resolve("f.flac"), "CUESHEET",
                "FILE \"f.flac\" WAVE\n TRACK 01 AUDIO\n  TITLE \"A\"\n  INDEX 01 00:00:00\n TRACK 02 AUDIO\n"
                        + "  TITLE \"B\"\n  INDEX 01 00:01:00\n");
        Snapshot s = rescan();

        for (String f : List.of("book.mp3", "a.m4a", "o.ogg", "f.flac")) {
            assertThat(s.folder(dir.child(f))).as(f).isPresent();
            assertThat(s.folder(dir.child(f)).orElseThrow().isVirtual()).as(f).isTrue();
            assertThat(s.track(dir.child(f))).as(f).isEmpty();
        }
        assertThat(s.folder(dir.child("f.flac")).orElseThrow().split().origin()).isEqualTo("tag-cue");
        assertThat(s.folder(dir.child("book.mp3")).orElseThrow().split().origin()).isEqualTo("chapters");
        List<Track> t = album("a.m4a");
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Alpha", "Beta");
        assertThat(t).extracting(x -> x.ref().qualified()).containsExactly("main/Chap/a.m4a#1", "main/Chap/a.m4a#2");
        assertThat(t.get(1).segment().startMillis()).isEqualTo(900);
        assertThat(t.get(0).segment().origin()).isEqualTo("chapters");
        assertThat(t.get(0).tags().trackNo()).isEqualTo(1);
    }

    @Test
    void keepWholeFilesListsTheChapterFileNextToItsAlbumAndBackAgain() throws IOException {
        book();
        Files.writeString(dirPath.resolve("settings.json"), "{\"keepWholeFiles\": true}");

        Snapshot s = rescan();

        assertThat(s.track(dir.child("book.mp3"))).isPresent();
        assertThat(s.folder(dir.child("book.mp3")).orElseThrow().isVirtual()).isTrue();
        assertThat(s.children(dir.child("book.mp3"))).hasSize(3);
        assertThat(s.children(dir)).hasSize(2);

        // an unchanged rescan reuses the split and keeps both
        ScanProgress p = new ScanProgress();
        Snapshot again = lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null, p, false);
        assertThat(again.track(dir.child("book.mp3"))).isPresent();
        assertThat(again.children(dir)).hasSize(2);
        assertThat(again.children(dir.child("book.mp3"))).hasSize(3);

        Files.writeString(dirPath.resolve("settings.json"), "{}");
        Snapshot off = rescan();
        assertThat(off.track(dir.child("book.mp3"))).isEmpty();
        assertThat(off.children(dir)).hasSize(1);
        assertThat(off.children(dir.child("book.mp3"))).hasSize(3);

        Files.writeString(dirPath.resolve("settings.json"), "{\"keepWholeFiles\": true}");
        assertThat(rescan().track(dir.child("book.mp3"))).isPresent();
    }

    @Test
    void editingTheChaptersOfAFileRebuildsItsAlbum() throws IOException {
        book();
        assertThat(album("book.mp3")).hasSize(3);

        ChapterFiles.mp3Chapters(dirPath.resolve("book.mp3"), true, new Chap("c1", 0, 1000, "New one"),
                new Chap("c2", 1000, 2000, "New two"));
        Files.setLastModifiedTime(dirPath.resolve("book.mp3"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertThat(album("book.mp3")).extracting(x -> x.tags().title()).containsExactly("New one", "New two");
    }
}
