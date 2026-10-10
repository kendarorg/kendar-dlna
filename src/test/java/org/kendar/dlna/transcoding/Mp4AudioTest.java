package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The MP4 demuxer: codec detection, sample table and the gapless window rules, on synthetic and real files. */
class Mp4AudioTest {

    @TempDir
    Path tmp;

    private Mp4Audio open(Mp4Synth s) throws IOException {
        return Mp4Audio.open(s.write(tmp.resolve("x.m4a")));
    }

    @Test
    void withoutEditListOrSmpbEverythingIsPlayed() throws IOException {
        Mp4Synth s = new Mp4Synth();
        s.units = 5;
        s.lastUnitDuration = 136;
        Mp4Audio m = open(s);
        assertThat(m.codec).isEqualTo(Mp4Audio.Codec.AAC);
        assertThat(m.count()).isEqualTo(5);
        assertThat(m.sampleRate).isEqualTo(44100);
        assertThat(m.channelConfig).isEqualTo(2);
        assertThat(m.skip).isZero();
        assertThat(m.length).isEqualTo(4 * 1024 + 136);
        assertThat(m.gapless).isFalse();
        assertThat(m.starts[5]).isEqualTo(4 * 1024 + 136);
        assertThat(m.offsets[1] - m.offsets[0]).isEqualTo(4);
    }

    @Test
    void editListGivesSkipAndLengthInMediaSamples() throws IOException {
        Mp4Synth s = new Mp4Synth();
        s.units = 90;
        s.movieTimescale = 1000;
        s.edits = new long[][] {{2000, 1024}};   // 2 s of 44.1 kHz audio after 1024 priming samples
        Mp4Audio m = open(s);
        assertThat(m.skip).isEqualTo(1024);
        assertThat(m.length).isEqualTo(88200);
        assertThat(m.gapless).isTrue();
    }

    @Test
    void editListLongerThanTheMediaIsClippedAndZeroDurationMeansToTheEnd() throws IOException {
        Mp4Synth s = new Mp4Synth();
        s.units = 4;
        s.edits = new long[][] {{999_999, 1000}};
        assertThat(open(s).length).isEqualTo(4 * 1024 - 1000);
        s.edits = new long[][] {{0, 1000}};
        assertThat(open(s).length).isEqualTo(4 * 1024 - 1000);
    }

    @Test
    void emptyEditsAreIgnoredAndTwoRealEditsDisableTheEditList() throws IOException {
        Mp4Synth s = new Mp4Synth();
        s.units = 50;
        s.edits = new long[][] {{500, -1}, {1000, 1024}};
        Mp4Audio one = open(s);
        assertThat(one.skip).isEqualTo(1024);
        assertThat(one.length).isEqualTo(44100);
        s.edits = new long[][] {{500, 0}, {1000, 1024}};
        Mp4Audio two = open(s);
        assertThat(two.gapless).isFalse();
        assertThat(two.skip).isZero();
        assertThat(two.length).isEqualTo(50 * 1024);
    }

    @Test
    void itunesSmpbIsUsedWhenThereIsNoEditList() throws IOException {
        Mp4Synth s = new Mp4Synth();
        s.units = 100;
        s.smpb = " 00000000 00000840 000001CA 0000000000015F3C 00000000 00000000 00000000 00000000";
        Mp4Audio m = open(s);
        assertThat(m.skip).isEqualTo(0x840);
        assertThat(m.length).isEqualTo(0x15F3C);
        assertThat(m.gapless).isTrue();
    }

    @Test
    void smpbWithoutValidSamplesUsesThePaddingAndTheEditListWinsOverIt() throws IOException {
        Mp4Synth s = new Mp4Synth();
        s.units = 50;
        s.smpb = " 00000000 00000400 00000100 0000000000000000 00000000";
        Mp4Audio m = open(s);
        assertThat(m.skip).isEqualTo(0x400);
        assertThat(m.length).isEqualTo(50 * 1024 - 0x400 - 0x100);
        s.edits = new long[][] {{1000, 100}};
        Mp4Audio both = open(s);
        assertThat(both.skip).isEqualTo(100);
        assertThat(both.length).isEqualTo(44100);
    }

    @Test
    void garbageSmpbIsIgnored() throws IOException {
        Mp4Synth s = new Mp4Synth();
        s.smpb = " zz yy xx";
        Mp4Audio m = open(s);
        assertThat(m.skip).isZero();
        assertThat(m.length).isEqualTo(10 * 1024);
    }

    @Test
    void refusesWhatTheDecodersCannotPlayWithAReadableReason() {
        Mp4Synth video = new Mp4Synth();
        video.extraVideoTrack = true;
        assertThatThrownBy(() -> open(video)).isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("video");

        Mp4Synth frag = new Mp4Synth();
        frag.fragmented = true;
        assertThatThrownBy(() -> open(frag)).isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("fragmented");

        Mp4Synth mp3 = new Mp4Synth();
        mp3.objectTypeIndication = 0x6b;
        assertThatThrownBy(() -> open(mp3)).isInstanceOf(UnsupportedAudioException.class).hasMessageContaining("MP3");

        Mp4Synth he = new Mp4Synth();
        he.asc = new byte[] {0x2b, (byte) 0x92, 0x08, 0x00};   // AOT 5 (SBR), 22.05 kHz core
        assertThatThrownBy(() -> open(he)).isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("HE-AAC");

        Mp4Synth main = new Mp4Synth();
        main.asc = new byte[] {0x0a, 0x10};   // AOT 1 = AAC Main
        assertThatThrownBy(() -> open(main)).isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("Main");

        Mp4Synth other = new Mp4Synth();
        other.sampleEntry = "ac-3";
        assertThatThrownBy(() -> open(other)).isInstanceOf(UnsupportedAudioException.class);

        Mp4Synth drm = new Mp4Synth();
        drm.sampleEntry = "drms";
        assertThatThrownBy(() -> open(drm)).isInstanceOf(UnsupportedAudioException.class).hasMessageContaining("DRM");
    }

    @Test
    void timeScaleDifferentFromTheRateIsRefused() {
        Mp4Synth s = new Mp4Synth();
        s.rate = 48000;   // sample entry and mdhd say 48 kHz but the ASC says 44.1 kHz
        assertThatThrownBy(() -> open(s)).isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("time scale");
    }

    @Test
    void notAnMp4IsAPlainIoError() throws IOException {
        Path junk = tmp.resolve("junk.m4a");
        Files.writeString(junk, "this is definitely not an mp4 file at all");
        assertThatThrownBy(() -> Mp4Audio.open(junk)).isInstanceOf(IOException.class)
                .isNotInstanceOf(UnsupportedAudioException.class);
        Path empty = tmp.resolve("empty.m4a");
        Files.write(empty, new byte[0]);
        assertThatThrownBy(() -> Mp4Audio.openAny(empty)).isInstanceOf(IOException.class);
        Path trunc = tmp.resolve("trunc.m4a");
        Files.write(trunc, java.util.Arrays.copyOf(new Mp4Synth().build(), 60));
        assertThatThrownBy(() -> Mp4Audio.open(trunc)).isInstanceOf(IOException.class);
    }

    @Test
    void realFfmpegFilesAreParsedWithTheirGaplessWindow() throws IOException {
        Mp4Audio aac = Mp4Audio.open(TestAudio.resource("sweep.m4a"));
        assertThat(aac.codec).isEqualTo(Mp4Audio.Codec.AAC);
        assertThat(aac.sampleRate).isEqualTo(44100);
        assertThat(aac.channelConfig).isEqualTo(2);
        assertThat(aac.skip).isEqualTo(1024);
        assertThat(aac.length).isEqualTo(88200);
        assertThat(aac.count()).isEqualTo(88);

        Mp4Audio alac = Mp4Audio.open(TestAudio.resource("sweep-alac.m4a"));
        assertThat(alac.codec).isEqualTo(Mp4Audio.Codec.ALAC);
        assertThat(alac.bitsPerSample).isEqualTo(16);
        assertThat(alac.length).isEqualTo(88200);

        Mp4Audio hires = Mp4Audio.open(TestAudio.resource("hires-alac.m4a"));
        assertThat(hires.sampleRate).isEqualTo(96000);
        assertThat(hires.bitsPerSample).isEqualTo(24);
        assertThat(hires.length).isEqualTo(14400);
    }

    @Test
    void adtsIsScannedFrameByFrame() throws IOException {
        Mp4Audio adts = Mp4Audio.openAny(TestAudio.resource("transient.aac"));
        assertThat(adts.codec).isEqualTo(Mp4Audio.Codec.AAC);
        assertThat(adts.sampleRate).isEqualTo(44100);
        assertThat(adts.channelConfig).isEqualTo(2);
        assertThat(adts.length).isEqualTo(adts.count() * 1024L);
        assertThat(adts.skip).isZero();
        assertThat(adts.sizes[0]).isPositive();
    }

    @Test
    void adtsWithAnId3TagInFrontAndJunkBehindIsScannedToo() throws IOException {
        byte[] raw = Files.readAllBytes(TestAudio.resource("transient.aac"));
        byte[] id3 = new byte[10 + 100];
        id3[0] = 'I';
        id3[1] = 'D';
        id3[2] = '3';
        id3[3] = 4;
        id3[9] = 100;   // syncsafe size
        byte[] all = new byte[id3.length + raw.length + 128];
        System.arraycopy(id3, 0, all, 0, id3.length);
        System.arraycopy(raw, 0, all, id3.length, raw.length);
        all[id3.length + raw.length] = 'T';
        all[id3.length + raw.length + 1] = 'A';
        all[id3.length + raw.length + 2] = 'G';
        Path p = tmp.resolve("tagged.aac");
        Files.write(p, all);
        assertThat(Mp4Audio.openAny(p).count())
                .isEqualTo(Mp4Audio.openAny(TestAudio.resource("transient.aac")).count());
    }

    @Test
    void adtsWithNonLcProfileIsRefused() throws IOException {
        byte[] raw = Files.readAllBytes(TestAudio.resource("transient.aac"));
        raw[2] = (byte) (raw[2] & 0x3f);   // profile 0 = Main
        Path p = tmp.resolve("main.aac");
        Files.write(p, raw);
        assertThatThrownBy(() -> Mp4Audio.openAny(p)).isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("Main");
    }
}
