package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.flac.FlacTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.testutil.LibraryFixture;

/** Chapters embedded in MP3 (ID3 CHAP/CTOC), MP4 (chpl) and Vorbis comments (CUESHEET, CHAPTERxxx). */
class EmbeddedSectionsTest {

    @TempDir
    Path tmp;

    // ---- ID3 builders ----

    private static byte[] frame(String id, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(be32(data.length));
        o.writeBytes(new byte[] {0, 0});
        o.writeBytes(data);
        return o.toByteArray();
    }

    private static byte[] be32(long v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] text(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        return o.toByteArray();
    }

    private static byte[] chap(String id, long startMs, long endMs, String title) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        o.write(0);
        o.writeBytes(be32(startMs));
        o.writeBytes(be32(endMs));
        o.writeBytes(be32(0xFFFFFFFFL));
        o.writeBytes(be32(0xFFFFFFFFL));
        if (title != null) {
            o.writeBytes(frame("TIT2", text(title)));
        }
        return frame("CHAP", o.toByteArray());
    }

    private static byte[] ctoc(boolean topLevel, String... children) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("toc".getBytes(StandardCharsets.ISO_8859_1));
        o.write(0);
        o.write((topLevel ? 2 : 0) | 1);
        o.write(children.length);
        for (String c : children) {
            o.writeBytes(c.getBytes(StandardCharsets.ISO_8859_1));
            o.write(0);
        }
        return frame("CTOC", o.toByteArray());
    }

    /** An ID3v2.3 tag with the given frames followed by the MP3 body of the sweep fixture. */
    private Path mp3(String name, byte[]... frames) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            body.writeBytes(f);
        }
        int size = body.size();
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.writeBytes("ID3".getBytes(StandardCharsets.ISO_8859_1));
        tag.write(3);
        tag.write(0);
        tag.write(0);
        tag.write((size >> 21) & 0x7F);
        tag.write((size >> 14) & 0x7F);
        tag.write((size >> 7) & 0x7F);
        tag.write(size & 0x7F);
        tag.writeBytes(body.toByteArray());
        Path plain = AudioFiles.fixture("sweep.mp3", tmp.resolve("plain-" + name));
        Path out = tmp.resolve(name);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(tag.toByteArray());
        all.writeBytes(Files.readAllBytes(plain));
        Files.write(out, all.toByteArray());
        return out;
    }

    @Test
    void id3ChaptersAreReadInStartOrderWithTheirTitles() throws IOException {
        Path f = mp3("ch.mp3", chap("c2", 1000, 2000, "Second"), chap("c1", 0, 1000, "First"));

        Optional<EmbeddedSections.Found> found = EmbeddedSections.read(f, Format.MP3);

        assertThat(found).isPresent();
        assertThat(found.get().origin()).isEqualTo("chapters");
        List<CueSheet.CueTrack> t = found.get().sheet().files().get(0).tracks();
        assertThat(t).extracting(CueSheet.CueTrack::title).containsExactly("First", "Second");
        assertThat(t).extracting(CueSheet.CueTrack::startMicros).containsExactly(0L, 1_000_000L);
        assertThat(found.get().sheet().files().get(0).name()).isNull();
    }

    @Test
    void aTopLevelCtocSelectsAndOrdersTheChapters() throws IOException {
        Path f = mp3("toc.mp3", chap("a", 0, 500, "A"), chap("hidden", 300, 400, "Hidden"), chap("b", 500, 900, "B"),
                ctoc(true, "a", "b"));

        List<CueSheet.CueTrack> t = EmbeddedSections.read(f, Format.MP3).orElseThrow().sheet().files().get(0).tracks();

        assertThat(t).extracting(CueSheet.CueTrack::title).containsExactly("A", "B");
    }

    @Test
    void oneChapterOrNoneIsNotASplit() throws IOException {
        assertThat(EmbeddedSections.read(mp3("one.mp3", chap("a", 0, 500, "A")), Format.MP3)).isEmpty();
        assertThat(EmbeddedSections.read(mp3("none.mp3", frame("TIT2", text("Just a title"))), Format.MP3)).isEmpty();
        assertThat(EmbeddedSections.read(AudioFiles.fixture("sweep.mp3", tmp.resolve("plain.mp3")), Format.MP3)).isEmpty();
    }

    @Test
    void garbageNeverThrows() throws IOException {
        Path f = tmp.resolve("bad.mp3");
        Files.write(f, new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 5, 'C', 'H', 'A', 'P', 0, 0, 0, 99, 0, 0, 1});
        assertThat(EmbeddedSections.read(f, Format.MP3)).isEmpty();
        assertThat(EmbeddedSections.read(tmp.resolve("missing.mp3"), Format.MP3)).isEmpty();
        assertThat(EmbeddedSections.read(f, Format.MIDI)).isEmpty();
    }

    // ---- MP4 chpl ----

    private static byte[] box(String type, byte[]... children) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] c : children) {
            body.writeBytes(c);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(be32(body.size() + 8));
        o.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(body.toByteArray());
        return o.toByteArray();
    }

    @Test
    void mp4ChplChaptersAreRead() throws IOException {
        ByteArrayOutputStream chpl = new ByteArrayOutputStream();
        chpl.writeBytes(new byte[] {1, 0, 0, 0});    // version 1, flags
        chpl.writeBytes(new byte[] {0, 0, 0, 0});    // reserved
        chpl.write(2);
        for (Object[] c : new Object[][] {{0L, "Intro"}, {65_000L, "Main é"}}) {
            long units = (Long) c[0] * 10_000L;
            for (int s = 56; s >= 0; s -= 8) {
                chpl.write((int) (units >> s));
            }
            byte[] name = ((String) c[1]).getBytes(StandardCharsets.UTF_8);
            chpl.write(name.length);
            chpl.writeBytes(name);
        }
        byte[] file = concat(box("ftyp", "M4A ".getBytes(StandardCharsets.ISO_8859_1)),
                box("moov", box("udta", box("chpl", chpl.toByteArray()))));
        Path f = tmp.resolve("book.m4a");
        Files.write(f, file);

        List<EmbeddedSections.Chapter> ch = EmbeddedSections.mp4Chapters(f);

        assertThat(ch).extracting(EmbeddedSections.Chapter::title).containsExactly("Intro", "Main é");
        assertThat(ch).extracting(EmbeddedSections.Chapter::startMillis).containsExactly(0L, 65_000L);
        assertThat(EmbeddedSections.read(f, Format.AAC)).isPresent();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    // ---- Vorbis comments ----

    private Path flacWith(String name, String... idValue) throws Exception {
        Path f = AudioFiles.fixture("sweep.flac", tmp.resolve(name));
        AudioFile af = AudioFileIO.read(f.toFile());
        Tag tag = af.getTagOrCreateAndSetDefault();
        FlacTag flac = (FlacTag) tag;
        for (int i = 0; i < idValue.length; i += 2) {
            flac.addField(flac.getVorbisCommentTag().createField(idValue[i], idValue[i + 1]));
        }
        af.commit();
        return f;
    }

    @Test
    void vorbisChapterTagsAreRead() throws Exception {
        Path f = flacWith("ch.flac", "CHAPTER001", "00:00:00.000", "CHAPTER001NAME", "One",
                "CHAPTER002", "00:00:01.250", "CHAPTER002NAME", "Two");

        Optional<EmbeddedSections.Found> found = EmbeddedSections.read(f, Format.FLAC);

        assertThat(found).isPresent();
        assertThat(found.get().origin()).isEqualTo("chapters");
        List<CueSheet.CueTrack> t = found.get().sheet().files().get(0).tracks();
        assertThat(t).extracting(CueSheet.CueTrack::title).containsExactly("One", "Two");
        assertThat(t).extracting(CueSheet.CueTrack::startMicros).containsExactly(0L, 1_250_000L);
    }

    @Test
    void aCuesheetTagIsParsedLikeACueFile() throws Exception {
        Path f = flacWith("cue.flac", "CUESHEET", "TITLE \"Tagged\"\nFILE \"x.flac\" WAVE\n TRACK 01 AUDIO\n  TITLE \"A\"\n"
                + "  INDEX 01 00:00:00\n TRACK 02 AUDIO\n  TITLE \"B\"\n  INDEX 01 00:01:00\n");

        EmbeddedSections.Found found = EmbeddedSections.read(f, Format.FLAC).orElseThrow();

        assertThat(found.origin()).isEqualTo("tag-cue");
        assertThat(found.sheet().title()).isEqualTo("Tagged");
        assertThat(found.sheet().files().get(0).tracks()).extracting(CueSheet.CueTrack::title).containsExactly("A", "B");
    }

    @Test
    void chapterTimesAreParsed() {
        assertThat(EmbeddedSections.chapterMillis("01:02:03.5")).isEqualTo(3_723_500L);
        assertThat(EmbeddedSections.chapterMillis("00:00:07")).isEqualTo(7000L);
        assertThat(EmbeddedSections.chapterMillis("7.5")).isEqualTo(-1);
    }

    // ---- through the scanner ----

    @Test
    void chaptersOfAnMp3BecomeAnAlbumWithoutAnyCueFile() throws IOException {
        LibraryFixture lib = new LibraryFixture(tmp.resolve("lib"));
        Path dir = Files.createDirectories(lib.music.resolve("Chapters"));
        Path f = mp3("x.mp3", chap("c1", 0, 1000, "Part one"), chap("c2", 1000, 2000, "Part two"));
        Files.copy(f, dir.resolve("book.mp3"));
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);

        RootRef book = RootRef.parse("main/Chapters/book.mp3");
        Snapshot s = lib.catalog.current();
        Folder album = s.folder(book).orElseThrow();
        assertThat(album.isVirtual()).isTrue();
        assertThat(album.split().origin()).isEqualTo("chapters");
        assertThat(s.track(book)).isEmpty();
        List<Track> t = s.children(book).stream().map(c -> ((Child.TrackChild) c).track()).toList();
        assertThat(t).extracting(x -> x.tags().title()).containsExactly("Part one", "Part two");
        assertThat(t).extracting(x -> x.ref().qualified()).containsExactly("main/Chapters/book.mp3#1",
                "main/Chapters/book.mp3#2");
        assertThat(t.get(0).segment().startSample()).isZero();
        assertThat(t.get(1).segment().startMillis()).isEqualTo(1000);
        assertThat(t.get(0).segment().endSample()).isEqualTo(t.get(1).segment().startSample());
        assertThat(t.get(1).segment().origin()).isEqualTo("chapters");
        assertThat(t.get(0).tags().album()).isNotBlank();

        // unchanged file: the split is reused, not re-read
        ScanProgress p = new ScanProgress();
        lib.catalog.commit(lib.scanner.rescan(lib.roots, lib.playlists, lib.catalog.current(), null, p));
        assertThat(p.tracksReused()).isGreaterThanOrEqualTo(1);
        assertThat(lib.catalog.current().folder(book).orElseThrow().isVirtual()).isTrue();
    }
}
