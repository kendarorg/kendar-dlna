package org.kendar.dlna.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.ChangeSet;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.testutil.LibraryFixture;

/** MIDI tag edits through {@link TagService}: the safe-write procedure, the catalog commit and the failure paths. */
class MidiTagServiceTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    final List<ChangeSet> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        lib.catalog.addListener(events::add);
    }

    private TagService service(TagWriter writer) {
        return new TagService(lib.catalog, lib.files, writer, lib.reader);
    }

    private Track midi() {
        return lib.catalog.current().track(LibraryFixture.ref("main/Album/04 four.mid")).orElseThrow();
    }

    private long tempFiles() throws IOException {
        try (Stream<Path> s = Files.walk(lib.music)) {
            return s.filter(p -> p.getFileName().toString().startsWith(AtomicFiles.TEMP_PREFIX)).count();
        }
    }

    private static TagEdit edit(String title, String artist, String year) {
        return new TagEdit(title, artist, null, null, null, year, null, null, null);
    }

    @Test
    void editsTitleArtistAndYearAndCommitsTheCatalogResettingTheRenderedLength() throws Exception {
        Track before = midi();
        assertThat(lib.catalog.recordRenderedLength(before, 48_000L * 3, 3000)).isTrue();
        before = midi();
        assertThat(before.renderedMillis()).isEqualTo(3000);
        events.clear();
        lib.store.saves = 0;

        Track after = service(new PerFormatTagWriter()).writeOne(before.ref(),
                new TagEdit("A New Title", "A New Artist", "Album is ignored", null, "Genre ignored", "1999", "5", null,
                        null, "Composer ignored"));

        assertThat(after.tags().title()).isEqualTo("A New Title");
        assertThat(after.tags().artist()).isEqualTo("A New Artist");
        assertThat(after.tags().year()).isEqualTo(1999);
        assertThat(after.tags().album()).isNull();
        assertThat(after.tags().genre()).isNull();
        assertThat(after.tags().trackNo()).isNull();
        assertThat(after.tags().composer()).isNull();
        // the file changed on disk and the catalog entry follows it (this is what keys the transcode cache)
        assertThat(after.size()).isNotEqualTo(before.size());
        assertThat(after.size()).isEqualTo(Files.size(lib.file(before.ref().qualified())));
        assertThat(after.mtime()).isNotEqualTo(before.mtime());
        // the rendered length of the old content is gone, in the catalog and in the persisted index
        assertThat(after.renderedMillis()).isZero();
        assertThat(after.sampleCount()).isZero();
        assertThat(lib.store.saves).isEqualTo(1);
        assertThat(lib.store.saved.track(before.ref()).orElseThrow().renderedMillis()).isZero();
        assertThat(lib.store.saved.track(before.ref()).orElseThrow().tags().title()).isEqualTo("A New Title");
        assertThat(events).isNotEmpty();
        // the duration is that of the unchanged sequence
        assertThat(after.durationMillis()).isEqualTo(before.durationMillis());
        // no temp file and no backup is left behind
        assertThat(tempFiles()).isZero();
        try (Stream<Path> s = Files.list(lib.music.resolve("Album"))) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("01 one.wav", "02 two.flac",
                    "03 three.mp3", "04 four.mid");
        }
    }

    @Test
    void anEditWithOnlyUnsupportedFieldsLeavesTheFileAndTheRenderedLengthAlone() throws Exception {
        Track before = midi();
        lib.catalog.recordRenderedLength(before, 48_000L * 3, 3000);
        before = midi();
        byte[] bytes = Files.readAllBytes(lib.file(before.ref().qualified()));
        events.clear();

        List<TagService.Result> r = service(new PerFormatTagWriter()).write(List.of(before.ref()),
                new TagEdit(null, null, "Album", "AA", "Rock", null, "3", "1", "2", "Comp"));

        assertThat(r).singleElement().satisfies(x -> assertThat(x.ok()).isTrue());
        assertThat(Files.readAllBytes(lib.file(before.ref().qualified()))).isEqualTo(bytes);
        assertThat(midi().renderedMillis()).isEqualTo(3000);
    }

    @Test
    void aBatchMixingFormatsWritesWhatEachFormatCanHold() {
        Track wav = lib.catalog.current().track(LibraryFixture.ref("main/Album/01 one.wav")).orElseThrow();
        List<TagService.Result> r = service(new PerFormatTagWriter()).write(List.of(wav.ref(), midi().ref()),
                new TagEdit("Shared", null, "Shared Album", null, null, "2001", null, null, null));
        assertThat(r).extracting(TagService.Result::ok).containsExactly(true, true);
        assertThat(midi().tags().title()).isEqualTo("Shared");
        assertThat(midi().tags().year()).isEqualTo(2001);
        assertThat(lib.catalog.current().track(wav.ref()).orElseThrow().tags().album()).isEqualTo("Shared Album");
    }

    @Test
    void aFaultyPatcherIsCaughtByTheVerificationAndTheOriginalStaysUntouched() throws Exception {
        Track before = midi();
        Path file = lib.file(before.ref().qualified());
        byte[] bytes = Files.readAllBytes(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        lib.store.saves = 0;

        // changes a note byte as well as the title
        MidiTagWriter.Patcher faulty = (smf, edit) -> {
            byte[] patched = MidiPatcher.patch(smf, edit);
            patched[patched.length - 6] ^= 1;
            return patched;
        };
        // a patcher that loses the requested title
        MidiTagWriter.Patcher lossy = (smf, edit) -> smf;
        // a patcher that returns garbage
        MidiTagWriter.Patcher garbage = (smf, edit) -> new byte[] { 1, 2, 3 };

        for (MidiTagWriter.Patcher patcher : List.of(faulty, lossy, garbage)) {
            TagService svc = service(new PerFormatTagWriter(new MidiTagWriter(patcher), new JAudioTaggerWriter()));
            assertThatThrownBy(() -> svc.writeOne(before.ref(), edit("Broken", null, null)))
                    .isInstanceOf(CurationException.class).hasMessageContaining("original is untouched");
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
            assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(mtime);
            assertThat(tempFiles()).isZero();
            assertThat(midi()).isEqualTo(before);
            assertThat(lib.store.saves).isZero();
        }
    }

    @Test
    void aCorruptFileIsRefusedWithTheReasonAndLeftUntouched() throws Exception {
        Track before = midi();
        Path file = lib.file(before.ref().qualified());
        byte[] good = Files.readAllBytes(file);
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 5);
        Files.write(file, truncated);
        lib.store.saves = 0;

        List<TagService.Result> r = service(new PerFormatTagWriter()).write(List.of(before.ref()),
                edit("x", null, null));

        assertThat(r).singleElement().satisfies(x -> {
            assertThat(x.ok()).isFalse();
            assertThat(x.error()).contains("Cannot edit this MIDI file").contains("longer than the file");
        });
        assertThat(Files.readAllBytes(file)).isEqualTo(truncated);
        assertThat(tempFiles()).isZero();
        assertThat(lib.store.saves).isZero();
    }

    @Test
    void anEditThatClearsTheTitleOfAFileAlsoWorksEndToEnd() throws Exception {
        Track after = service(new PerFormatTagWriter()).writeOne(midi().ref(), edit("", null, null));
        assertThat(after.tags().title()).isNull();
    }
}
