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
import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.ingestion.ListJson;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.LibraryFixture;

class ListServiceTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    ListService lists;
    Path list;
    final RootRef album = RootRef.parse("main/Album");
    final RootRef four = RootRef.parse("main/Album/04 four.mid");

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        TagService tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
        lists = new FolderService(lib.catalog, lib.files, tags).lists();
        list = lib.music.resolve("Album/list.json");
    }

    private static ListJson.Entry e(String file, String author, String partOf, String title, String transcriber) {
        return new ListJson.Entry(file, author, partOf, title, transcriber);
    }

    @Test
    void readsADirectoryWithoutAFileAndReportsEveryTrackAsMissing() {
        ListService.View v = lists.read(album);
        assertThat(v.exists()).isFalse();
        assertThat(v.version()).isEqualTo(ListService.NO_FILE);
        assertThat(v.entries()).isEmpty();
        assertThat(v.files()).containsExactly("01 one.wav", "02 two.flac", "03 three.mp3", "04 four.mid");
        assertThat(v.missing()).isEqualTo(v.files());
        assertThat(lists.read(RootRef.parse("main/Other")).files()).isEmpty();
    }

    @Test
    void savesAtomicallyAndTheChangeIsEffectiveAtOnce() throws IOException {
        long id = lib.catalog.current().updateId();
        ListService.View v = lists.save(album, List.of(
                e("04 FOUR.mid", "Me", "Set", "Shown title", "T.Ranscriber"),
                e("01 one.wav", null, null, "One shown", null),
                e("02 two.flac", " ", "", null, null)),   // no metadata: dropped
                ListService.NO_FILE);

        assertThat(v.exists()).isTrue();
        assertThat(v.entries()).extracting(ListJson.Entry::filename).containsExactly("04 four.mid", "01 one.wav");
        assertThat(v.missing()).containsExactly("02 two.flac", "03 three.mp3");
        assertThat(DisplayNames.trackTitle(lib.catalog.current().track(four).orElseThrow())).isEqualTo("Shown title");
        assertThat(lib.catalog.current().track(four).orElseThrow().tags().transcriber()).isEqualTo("T.Ranscriber");
        assertThat(lib.catalog.current().updateId()).isGreaterThan(id);
        assertThat(lib.store.saves).isGreaterThan(0);

        // the file round-trips through the tolerant parser, in the documented shape (all five fields)
        String text = Files.readString(list);
        assertThat(text).contains("\"transcriber\": \"T.Ranscriber\"").contains("\"partOf\": \"\"");
        assertThat(ListJson.parseFile(list).entries()).isEqualTo(v.entries());
        try (var files = Files.list(list.getParent())) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith(AtomicFiles.TEMP_PREFIX))).isEmpty();
        }
    }

    @Test
    void anEmptyListDeletesTheFileAndRestoresTheEmbeddedTags() {
        lists.save(album, List.of(e("04 four.mid", null, null, "Listed", null)), ListService.NO_FILE);
        String version = lists.read(album).version();
        ListService.View v = lists.save(album, List.of(), version);
        assertThat(v.exists()).isFalse();
        assertThat(list).doesNotExist();
        assertThat(DisplayNames.trackTitle(lib.catalog.current().track(four).orElseThrow())).isEqualTo("Four");
        assertThat(lib.catalog.current().folder(album).orElseThrow().listHash()).isNull();
    }

    @Test
    void rejectsInvalidInputAndWritesNothing() {
        assertThatThrownBy(() -> lists.save(album, List.of(e("../x.mid", null, null, "t", null)), null))
                .isInstanceOf(CurationException.class).extracting("code").isEqualTo(CurationException.INVALID);
        assertThatThrownBy(() -> lists.save(album, List.of(e("", null, null, "t", null)), null))
                .isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> lists.save(album, List.of(e("nope.mid", null, null, "t", null)), null))
                .hasMessageContaining("no file 'nope.mid'");
        assertThatThrownBy(() -> lists.save(album, List.of(e("04 four.mid", null, null, "a", null),
                e("04 FOUR.MID", null, null, "b", null)), null)).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> lists.save(album, List.of(e("04 four.mid", null, null, "x".repeat(501), null)),
                null)).hasMessageContaining("at most");
        assertThatThrownBy(() -> lists.save(album, null, null)).isInstanceOf(CurationException.class);
        assertThat(list).doesNotExist();
        assertThatThrownBy(() -> lists.save(RootRef.parse("main/Nope"), List.of(), null))
                .extracting("code").isEqualTo(CurationException.NOT_FOUND);
    }

    @Test
    void aStaleVersionIsAConflictAndKeepsTheOtherEditorsChange() throws IOException {
        ListService.View first = lists.save(album, List.of(e("04 four.mid", null, null, "Mine", null)),
                ListService.NO_FILE);
        // someone else edits the file on disk
        Files.writeString(list, "[{\"filename\":\"04 four.mid\",\"title\":\"Theirs\"}]");
        assertThatThrownBy(() -> lists.save(album, List.of(e("04 four.mid", null, null, "Mine 2", null)),
                first.version())).isInstanceOf(CurationException.class).extracting("code")
                .isEqualTo(CurationException.CONFLICT);
        assertThat(Files.readString(list)).contains("Theirs");
        // a creation race: the editor believed there was no file
        assertThatThrownBy(() -> lists.save(album, List.of(), ListService.NO_FILE))
                .extracting("code").isEqualTo(CurationException.CONFLICT);
        // without a version the caller overwrites on purpose
        lists.save(album, List.of(e("04 four.mid", null, null, "Forced", null)), null);
        assertThat(lists.read(album).entries().get(0).title()).isEqualTo("Forced");
    }

    @Test
    void keepsAlreadyListedOrphansEditableAndReportsThemAsUnknown() throws IOException {
        Files.writeString(list, "[{\"filename\":\"ghost.mid\",\"title\":\"G\"},{\"filename\":\"04 four.mid\",\"title\":\"F\"}]");
        ListService.View v = lists.read(album);
        assertThat(v.unknown()).containsExactly("ghost.mid");
        ListService.View saved = lists.save(album, List.of(e("ghost.mid", null, null, "G2", null),
                e("04 four.mid", null, null, "F", null)), v.version());
        assertThat(saved.entries()).hasSize(2);
        assertThat(saved.unknown()).containsExactly("ghost.mid");
    }

    @Test
    void aMalformedExistingFileIsReportedAndCanBeReplaced() throws IOException {
        Files.writeString(list, "[{broken");
        ListService.View v = lists.read(album);
        assertThat(v.error()).startsWith("malformed JSON");
        assertThat(v.entries()).isEmpty();
        lists.save(album, List.of(e("04 four.mid", null, null, "Fixed", null)), v.version());
        assertThat(lists.read(album).error()).isNull();
        assertThat(lists.read(album).entries()).hasSize(1);
    }

    @Test
    void tagEditsKeepTheListValuesOnTop() {
        lists.save(album, List.of(e("04 four.mid", "Listed artist", null, "Listed title", null)), ListService.NO_FILE);
        TagService tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
        tags.writeOne(four, new TagEdit("Edited", null, null, null, null, null, null, null, null));
        assertThat(DisplayNames.trackTitle(lib.catalog.current().track(four).orElseThrow())).isEqualTo("Listed title");
    }
}
