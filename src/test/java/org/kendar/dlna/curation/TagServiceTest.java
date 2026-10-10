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
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.testutil.LibraryFixture;

class TagServiceTest {

    @TempDir
    Path tmp;
    LibraryFixture lib;
    TagService tags;
    final List<ChangeSet> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        lib.catalog.addListener(events::add);
        tags = new TagService(lib.catalog, lib.files, new PerFormatTagWriter(), lib.reader);
    }

    private Track track(String name) {
        return lib.catalog.current().track(LibraryFixture.ref("main/Album/" + name)).orElseThrow();
    }

    private long tempFiles() throws IOException {
        try (Stream<Path> s = Files.walk(lib.music)) {
            return s.filter(p -> p.getFileName().toString().startsWith(AtomicFiles.TEMP_PREFIX)).count();
        }
    }

    @Test
    void writesTagsOfWavFlacAndMp3AndUpdatesCatalogOnDiskAndIndex() throws Exception {
        for (String name : List.of("01 one.wav", "02 two.flac", "03 three.mp3")) {
            Track before = track(name);
            TagEdit edit = new TagEdit("New " + name, "The Artist", "The Album", null, "Rock", "1999", "7", null,
                    null);
            Track after = tags.writeOne(before.ref(), edit);

            assertThat(after.tags().title()).as(name).isEqualTo("New " + name);
            assertThat(after.tags().artist()).isEqualTo("The Artist");
            assertThat(after.tags().album()).isEqualTo("The Album");
            assertThat(after.tags().genre()).isEqualTo("Rock");
            assertThat(after.tags().year()).isEqualTo(1999);
            assertThat(after.tags().trackNo()).isEqualTo(7);
            // technical properties and the exact length data are untouched
            assertThat(after.durationMillis()).isEqualTo(before.durationMillis());
            assertThat(after.sampleCount()).isEqualTo(before.sampleCount());
            // the file really holds the new tags: a fresh read of the disk agrees with the catalog
            assertThat(lib.reader.read(lib.file(before.ref().qualified()), before.format()).tags().title())
                    .isEqualTo("New " + name);
            assertThat(after.size()).isEqualTo(Files.size(lib.file(before.ref().qualified())));
        }
        assertThat(tempFiles()).isZero();
        assertThat(lib.store.saves).isEqualTo(3);                 // catalog persisted with every commit
        assertThat(events).hasSize(3);                             // change events fire (transcode cache, UpdateIDs)
        assertThat(lib.store.saved).isSameAs(lib.catalog.current());
    }

    @Test
    void oggAndAiffAreWritableToo() throws Exception {
        org.kendar.dlna.testutil.AudioFiles.fixture("sweep.ogg", lib.music.resolve("Other/a.ogg"));
        org.kendar.dlna.testutil.AudioFiles.fixture("sweep.aiff", lib.music.resolve("Other/b.aiff"));
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        for (String name : List.of("a.ogg", "b.aiff")) {
            Track t = tags.writeOne(LibraryFixture.ref("main/Other/" + name),
                    new TagEdit("Tagged " + name, "Art", null, null, null, "2005", null, null, null));
            assertThat(t.tags().title()).as(name).isEqualTo("Tagged " + name);
            assertThat(t.tags().year()).isEqualTo(2005);
        }
    }

    @Test
    void composerIsWrittenReadBackAndCleared() throws Exception {
        org.kendar.dlna.testutil.AudioFiles.fixture("sweep.ogg", lib.music.resolve("Other/a.ogg"));
        org.kendar.dlna.testutil.AudioFiles.fixture("sweep.aiff", lib.music.resolve("Other/b.aiff"));
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        List<org.kendar.dlna.shared.RootRef> refs = new ArrayList<>(List.of(LibraryFixture.ref("main/Other/a.ogg"),
                LibraryFixture.ref("main/Other/b.aiff")));
        for (String name : List.of("01 one.wav", "02 two.flac", "03 three.mp3")) {
            refs.add(track(name).ref());
        }
        for (var ref : refs) {
            Track t = tags.writeOne(ref, new TagEdit(null, null, null, null, null, null, null, null, null, "Erik Satie"));
            assertThat(t.tags().composer()).as(ref.name()).isEqualTo("Erik Satie");
            assertThat(lib.reader.read(lib.file(ref.qualified()), t.format()).tags().allComposers())
                    .containsExactly("Erik Satie");
            t = tags.writeOne(ref, new TagEdit(null, null, null, null, null, null, null, null, null, ""));
            assertThat(t.tags().composer()).as(ref.name()).isNull();
        }
    }

    @Test
    void aComposerOnlyEditIsAValidNonEmptyEdit() {
        TagEdit e = new TagEdit(null, null, null, null, null, null, null, null, null, "X");
        assertThat(e.isEmpty()).isFalse();
        assertThat(e.problems()).isEmpty();
        assertThat(new TagEdit("t", null, null, null, null, null, null, null, null).composer()).isNull();
        assertThat(new TagEdit(null, null, null, null, null, null, null, null, null, "a\u0000b").problems()).isNotEmpty();
    }

    @Test
    void emptyStringClearsAFieldAndNullLeavesItAlone() {
        Track t = track("03 three.mp3");
        tags.writeOne(t.ref(), new TagEdit("Keep me", "Gone", null, null, null, null, null, null, null));
        Track after = tags.writeOne(t.ref(), new TagEdit(null, "", null, null, null, null, null, null, null));
        assertThat(after.tags().title()).isEqualTo("Keep me");
        assertThat(after.tags().artist()).isNull();
    }

    @Test
    void anEditWithoutMidiFieldsSkipsMidiFilesWithoutFailingAndWithoutTouchingThem() throws Exception {
        Track midi = track("04 four.mid");
        byte[] before = Files.readAllBytes(lib.file(midi.ref().qualified()));
        List<TagService.Result> r = tags.write(List.of(midi.ref()), new TagEdit(null, null, "Album", null, "Rock",
                null, "3", null, null, "Composer"));
        assertThat(r).singleElement().satisfies(x -> assertThat(x.ok()).isTrue());
        assertThat(Files.readAllBytes(lib.file(midi.ref().qualified()))).isEqualTo(before);
        assertThat(track("04 four.mid")).isEqualTo(midi);
    }

    @Test
    void batchWritesTheGoodFilesAndReportsTheOthers() {
        List<TagService.Result> r = tags.write(List.of(track("01 one.wav").ref(), track("04 four.mid").ref(),
                track("02 two.flac").ref(), LibraryFixture.ref("main/Album/nope.mp3")),
                new TagEdit(null, null, "Batch Album", "Batch Artist", null, null, null, null, null));
        assertThat(r).extracting(TagService.Result::ok).containsExactly(true, true, true, false);
        assertThat(track("01 one.wav").tags().album()).isEqualTo("Batch Album");
        assertThat(track("02 two.flac").tags().albumArtist()).isEqualTo("Batch Artist");
        assertThat(track("03 three.mp3").tags().album()).isNotEqualTo("Batch Album");
        assertThat(lib.store.saves).as("one commit for the whole batch").isEqualTo(1);
    }

    @Test
    void rejectsEmptyOrMalformedEdits() {
        Track t = track("01 one.wav");
        assertThatThrownBy(() -> tags.write(List.of(t.ref()), new TagEdit(null, null, null, null, null, null, null,
                null, null))).isInstanceOf(CurationException.class).hasMessageContaining("Nothing");
        assertThatThrownBy(() -> tags.write(List.of(t.ref()), new TagEdit(null, null, null, null, null, "abc", null,
                null, null))).isInstanceOf(CurationException.class).hasMessageContaining("year");
        assertThatThrownBy(() -> tags.write(List.of(), new TagEdit("x", null, null, null, null, null, null, null,
                null))).isInstanceOf(CurationException.class);
        assertThatThrownBy(() -> tags.write(List.of(t.ref()), new TagEdit("a\u0000b", null, null, null, null, null,
                null, null, null))).isInstanceOf(CurationException.class);
    }

    @Test
    void aFailingWriterLeavesTheOriginalUntouchedAndNoTempFile() throws IOException {
        TagWriter failing = (file, format, edit) -> {
            try {
                Files.writeString(file, "garbage");   // half-written temp file
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            throw new TagWriter.TagWriteException("disk full", null);
        };
        TagService broken = new TagService(lib.catalog, lib.files, failing, lib.reader);
        Track t = track("01 one.wav");
        byte[] before = Files.readAllBytes(lib.file(t.ref().qualified()));

        assertThatThrownBy(() -> broken.writeOne(t.ref(), new TagEdit("x", null, null, null, null, null, null, null,
                null))).isInstanceOf(CurationException.class).hasMessageContaining("disk full");

        assertThat(Files.readAllBytes(lib.file(t.ref().qualified()))).isEqualTo(before);
        assertThat(tempFiles()).isZero();
        assertThat(track("01 one.wav")).isEqualTo(t);
        assertThat(lib.store.saves).isZero();
    }

    @Test
    void theTempFileIsInTheSameDirectoryAndKeepsTheExtension() {
        Path target = tmp.resolve("x").resolve("a.flac");
        Path temp = AtomicFiles.tempSibling(target);
        assertThat(temp.getParent()).isEqualTo(target.getParent());
        assertThat(temp.getFileName().toString()).startsWith(".").endsWith("a.flac");
    }

    @Test
    void m4aAacAndAlacTagsAreWrittenSafelyAndTheAudioStaysIntact() throws Exception {
        Path aac = org.kendar.dlna.testutil.AudioFiles.fixture("sweep.m4a", lib.music.resolve("Other/a.m4a"));
        Path alac = org.kendar.dlna.testutil.AudioFiles.fixture("sweep-alac.m4a", lib.music.resolve("Other/b.m4a"));
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        Track a = lib.catalog.current().track(LibraryFixture.ref("main/Other/a.m4a")).orElseThrow();
        Track b = lib.catalog.current().track(LibraryFixture.ref("main/Other/b.m4a")).orElseThrow();
        assertThat(a.format()).isEqualTo(Format.AAC);
        assertThat(b.format()).isEqualTo(Format.ALAC);
        assertThat(a.format().tagsEditable()).isTrue();
        assertThat(b.format().tagsEditable()).isTrue();
        byte[] aacBefore = pcm(org.kendar.dlna.transcoding.SourceFormat.AAC, aac);
        byte[] alacBefore = pcm(org.kendar.dlna.transcoding.SourceFormat.ALAC, alac);

        for (Track t : List.of(a, b)) {
            Track after = tags.writeOne(t.ref(), new TagEdit("Nuovo Titolo è♪", "Nuovo Artista", "Album", "AA",
                    "Jazz", "1999", "7", "2", "3", "Erik Satie"));
            assertThat(after.format()).isEqualTo(t.format());
            assertThat(after.tags().title()).isEqualTo("Nuovo Titolo è♪");
            assertThat(after.tags().artist()).isEqualTo("Nuovo Artista");
            assertThat(after.tags().albumArtist()).isEqualTo("AA");
            assertThat(after.tags().composer()).isEqualTo("Erik Satie");
            assertThat(after.tags().genre()).isEqualTo("Jazz");
            assertThat(after.tags().year()).isEqualTo(1999);
            assertThat(after.tags().trackNo()).isEqualTo(7);
            assertThat(after.tags().discNo()).isEqualTo(2);
            assertThat(after.tags().discTotal()).isEqualTo(3);
            assertThat(after.embeddedArt()).as("cover art survives the rewrite").isTrue();
            assertThat(after.sampleCount()).isEqualTo(t.sampleCount());
            assertThat(after.size()).isEqualTo(Files.size(lib.file(t.ref().qualified())));
        }
        assertThat(tempFiles()).isZero();
        // the rewritten containers still decode to exactly the same samples
        assertThat(pcm(org.kendar.dlna.transcoding.SourceFormat.AAC, aac)).isEqualTo(aacBefore);
        assertThat(pcm(org.kendar.dlna.transcoding.SourceFormat.ALAC, alac)).isEqualTo(alacBefore);
        assertThat(new org.kendar.dlna.ingestion.EmbeddedArtExtractor().extract(aac)).isPresent();
        assertThat(new org.kendar.dlna.ingestion.EmbeddedArtExtractor().extract(alac)).isPresent();
    }

    private static byte[] pcm(org.kendar.dlna.transcoding.SourceFormat f, Path p) throws IOException {
        try (org.kendar.dlna.transcoding.AudioDecoder d = org.kendar.dlna.transcoding.Decoders.open(f, p)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = d.read(buf, 0, buf.length)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Test
    void rawAdtsAacHasNoTagContainerAndIsRefusedCleanly() throws Exception {
        Path raw = org.kendar.dlna.testutil.AudioFiles.fixture("transient.aac", lib.music.resolve("Other/raw.aac"));
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        Track t = lib.catalog.current().track(LibraryFixture.ref("main/Other/raw.aac")).orElseThrow();
        assertThat(t.format()).isEqualTo(Format.ADTS);
        long size = Files.size(raw);
        assertThatThrownBy(() -> tags.writeOne(t.ref(), new TagEdit("x", null, null, null, null, null, null, null,
                null))).isInstanceOf(org.kendar.dlna.curation.CurationException.class)
                .hasMessageContaining("cannot be edited");
        assertThat(Files.size(raw)).isEqualTo(size);
        assertThat(tempFiles()).isZero();
    }
}
