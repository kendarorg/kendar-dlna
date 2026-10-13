package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WAV, 24-bit, resampling, DoP and the ffmpeg-made lossless forms. */
class OutputFormsTest {

    @TempDir
    Path tmp;

    private String ffmpegBefore;

    @BeforeEach
    void remember() {
        ffmpegBefore = Ffmpeg.binary();
    }

    @AfterEach
    void restore() {
        Ffmpeg.configure(ffmpegBefore);
        Ffmpeg.configureDsd(44_100, false);
    }

    private static Transcoder.Rendered sine(int rate, int frames) {
        return new Transcoder.Rendered(TestAudio.sine(440, 0.5, rate, frames), rate);
    }

    private static byte[] all(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    @Test
    void wavHasAHeaderAndTheExactLengthAndSeeksByte() throws Exception {
        Transcoder.Rendered r = sine(44100, 20000);
        TranscodeOutput out = new TranscodeOutput(TranscodeTarget.WAV, 0, 16, 0);
        byte[] whole = all(Transcoder.open(r, out, 0));
        assertThat(whole).hasSize((int) Transcoder.length(out, 44100, 20000)).hasSize(44 + 80000);
        ByteBuffer h = ByteBuffer.wrap(whole).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(new String(whole, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(whole, 8, 4)).isEqualTo("WAVE");
        assertThat(h.getShort(22)).isEqualTo((short) 2);
        assertThat(h.getInt(24)).isEqualTo(44100);
        assertThat(h.getShort(34)).isEqualTo((short) 16);
        assertThat(h.getInt(40)).isEqualTo(80000);
        assertThat(Arrays.copyOfRange(whole, 44, whole.length)).isEqualTo(r.pcm());
        for (long off : new long[] {0, 10, 44, 45, 47, 1001, 80043}) {
            assertThat(all(Transcoder.open(r, out, off))).isEqualTo(Arrays.copyOfRange(whole, (int) off, whole.length));
        }
    }

    @Test
    void twentyFourBitLpcmAndWavKeepTheSamplesAndHaveSixByteFrames() throws Exception {
        Transcoder.Rendered r = sine(44100, 5000);
        TranscodeOutput lpcm = new TranscodeOutput(TranscodeTarget.LPCM, 0, 24, 0);
        byte[] l = all(Transcoder.open(r, lpcm, 0));
        assertThat(l).hasSize(5000 * 6).hasSize((int) Transcoder.length(lpcm, 44100, 5000));
        // big-endian 24-bit: the 16 bit sample, then a zero byte
        for (int i = 0; i < 5000; i++) {
            int s16 = (short) ((r.pcm()[i * 4] & 0xff) | r.pcm()[i * 4 + 1] << 8);
            int s24 = (l[i * 6] << 16) | ((l[i * 6 + 1] & 0xff) << 8) | (l[i * 6 + 2] & 0xff);
            assertThat(s24).isEqualTo(s16 << 8);
        }
        assertThat(all(Transcoder.open(r, lpcm, 7))).isEqualTo(Arrays.copyOfRange(l, 7, l.length));
        TranscodeOutput wav = new TranscodeOutput(TranscodeTarget.WAV, 0, 24, 0);
        byte[] w = all(Transcoder.open(r, wav, 0));
        assertThat(w).hasSize(44 + 5000 * 6);
        assertThat(ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN).getShort(34)).isEqualTo((short) 24);
        assertThat(LpcmStream.mimeType(96000, 24)).isEqualTo("audio/L24;rate=96000;channels=2");
    }

    @Test
    void resamplingKeepsTheLevelHasAnExactLengthAndIsSeekStable() throws Exception {
        Transcoder.Rendered r = new Transcoder.Rendered(TestAudio.sine(1000, 0.5, 96000, 96000), 96000);
        TranscodeOutput out = new TranscodeOutput(TranscodeTarget.LPCM, 0, 16, 48000);
        assertThat(Transcoder.outputInfo(96000, 96000, out)).isEqualTo(new Transcoder.SourceInfo(48000, 48000));
        byte[] whole = all(Transcoder.open(r, out, 0));
        assertThat(whole).hasSize(48000 * 4).hasSize((int) Transcoder.length(out, 96000, 96000));
        int peak = 0;
        for (int i = 2000; i < 46000; i++) {   // away from the edges
            peak = Math.max(peak, Math.abs((whole[i * 4] << 8) | (whole[i * 4 + 1] & 0xff)));
        }
        assertThat(peak).isBetween((int) (0.5 * 32767 * 0.97), (int) (0.5 * 32767 * 1.03));
        for (long off : new long[] {4, 40001, 123456, 150000}) {
            assertThat(all(Transcoder.open(r, out, off))).isEqualTo(Arrays.copyOfRange(whole, (int) off, whole.length));
        }
        // a source at or below the cap is not touched
        assertThat(Transcoder.outputInfo(44100, 1000, out)).isEqualTo(new Transcoder.SourceInfo(44100, 1000));
    }

    @Test
    void aSegmentIsCutBeforeItIsResampled() throws Exception {
        Transcoder.Rendered r = new Transcoder.Rendered(TestAudio.sine(1000, 0.5, 96000, 96000), 96000);
        AudioDecoder seg = new SegmentDecoder(r.decoder(), 10000, 30000);
        AudioDecoder res = Decoders.limitRate(seg, 48000);
        assertThat(res.totalSamples()).isEqualTo(10000);
        assertThat(res.sampleRate()).isEqualTo(48000);
        res.close();
    }

    private Path dsf(int blocks) throws Exception {
        int block = 4096;
        long dataBytes = (long) block * 2 * blocks;
        ByteBuffer b = ByteBuffer.allocate((int) (28 + 52 + 12 + dataBytes)).order(ByteOrder.LITTLE_ENDIAN);
        b.put("DSD ".getBytes()).putLong(28).putLong(b.capacity()).putLong(0);
        b.put("fmt ".getBytes()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2).putInt(2_822_400).putInt(1)
                .putLong((long) block * 8 * blocks).putInt(block).putInt(0);
        b.put("data".getBytes()).putLong(12 + dataBytes);
        int n = 0;
        while (b.hasRemaining()) {
            b.put((byte) (n++ * 7));
        }
        return Files.write(tmp.resolve("t.dsf"), b.array());
    }

    @Test
    void dopPacksTheDsdBitsWithAlternatingMarkers() throws Exception {
        Path f = dsf(2);
        try (DsdDopDecoder d = DsdDopDecoder.open(f)) {
            assertThat(d.sampleRate()).isEqualTo(176400);
            assertThat(d.frameBytes()).isEqualTo(6);
            assertThat(d.totalSamples()).isEqualTo(4096);
            byte[] all = new byte[(int) d.totalSamples() * 6];
            assertThat(d.read(all, 0, all.length)).isEqualTo(all.length);
            // The file bytes are 0,7,14.. in the first block of the left channel; bits are LSB first, so reversed.
            int b0 = Integer.reverse(0) >>> 24;
            int b1 = Integer.reverse(7) >>> 24;
            assertThat(all[0] & 0xff).isEqualTo(b1);
            assertThat(all[1] & 0xff).isEqualTo(b0);
            assertThat(all[2] & 0xff).isEqualTo(0x05);
            assertThat(all[8] & 0xff).isEqualTo(0xFA);
            assertThat(all[6 * 2 + 2] & 0xff).isEqualTo(0x05);
            d.seek(1001);
            byte[] part = new byte[6 * 10];
            d.read(part, 0, part.length);
            assertThat(part).isEqualTo(Arrays.copyOfRange(all, 1001 * 6, 1001 * 6 + 60));
            assertThat(part[2] & 0xff).isEqualTo(0xFA);   // odd frame
        }
    }

    @Test
    void dsdGoesThroughDopOnlyWhenAsked() throws Exception {
        Path f = dsf(2);
        Ffmpeg.configureDsd(44_100, true);
        Transcoder.SourceInfo info = Decoders.probe(SourceFormat.DSD, f);
        assertThat(info.sampleRate()).isEqualTo(176400);
        assertThat(info.totalSamples()).isEqualTo(4096);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> Decoders.open(SourceFormat.DSD, f, 16));
    }

    @Test
    void dsdPcmRateFollowsTheSetting() {
        Ffmpeg.configureDsd(176_400, false);
        assertThat(Ffmpeg.outputRate(2_822_400)).isEqualTo(176_400);
        assertThat(Ffmpeg.outputRate(3_072_000)).isEqualTo(192_000);
        assertThat(Ffmpeg.outputRate(96_000)).isEqualTo(96_000);
        Ffmpeg.configureDsd(88_200, false);
        assertThat(Ffmpeg.outputRate(5_644_800)).isEqualTo(88_200);
        assertThat(Ffmpeg.outputRate(6_144_000)).isEqualTo(96_000);
    }

    // ---- ffmpeg made forms ----

    private static void needFfmpeg() {
        assumeTrue(Ffmpeg.configure(null) != null, "no ffmpeg on the PATH");
    }

    @Test
    void flacOutputDecodesBackToTheSamePcm() throws Exception {
        needFfmpeg();
        Transcoder.Rendered r = sine(44100, 30000);
        TranscodeOutput out = new TranscodeOutput(TranscodeTarget.FLAC, 0, 16, 0);
        byte[] flac = all(Transcoder.open(r, out, 0));
        assertThat(new String(flac, 0, 4)).isEqualTo("fLaC");
        assertThat(Transcoder.length(out, 44100, 30000)).isEqualTo(-1);
        Path f = Files.write(tmp.resolve("o.flac"), flac);
        try (AudioDecoder d = Decoders.open(SourceFormat.FLAC, f)) {
            assertThat(d.totalSamples()).isEqualTo(30000);
            assertThat(DecodersTest.readAll(d)).isEqualTo(r.pcm());
        }
        assertThat(all(Transcoder.open(r, out, 100))).isEqualTo(Arrays.copyOfRange(flac, 100, flac.length));
    }

    @Test
    void twentyFourBitFlacAndAlacAreLossless() throws Exception {
        needFfmpeg();
        Transcoder.Rendered r = sine(48000, 20000);
        for (TranscodeTarget t : new TranscodeTarget[] {TranscodeTarget.FLAC, TranscodeTarget.ALAC}) {
            TranscodeOutput out = new TranscodeOutput(t, 0, 24, 0);
            byte[] enc = all(Transcoder.open(r, out, 0));
            Path f = Files.write(tmp.resolve("o24." + (t == TranscodeTarget.FLAC ? "flac" : "m4a")), enc);
            SourceFormat sf = t == TranscodeTarget.FLAC ? SourceFormat.FLAC : SourceFormat.ALAC;
            try (AudioDecoder d = Decoders.open(sf, f, 24)) {
                assertThat(d.totalSamples()).isEqualTo(20000);
                byte[] pcm24 = DecodersTest.readAll(d);
                try (AudioDecoder src = new PadTo24Decoder(r.decoder())) {
                    assertThat(pcm24).as(t.name()).isEqualTo(DecodersTest.readAll(src));
                }
            }
        }
    }

    @Test
    void alacOutputDecodesBackToTheSamePcm() throws Exception {
        needFfmpeg();
        Transcoder.Rendered r = sine(44100, 20000);
        byte[] enc = all(Transcoder.open(r, new TranscodeOutput(TranscodeTarget.ALAC, 0, 16, 0), 0));
        Path f = Files.write(tmp.resolve("o.m4a"), enc);
        try (AudioDecoder d = Decoders.open(SourceFormat.ALAC, f)) {
            assertThat(DecodersTest.readAll(d)).isEqualTo(r.pcm());
        }
    }

    @Test
    void wavPackAndAudiobooksAndHeAacAreDecodedByFfmpeg() throws Exception {
        needFfmpeg();
        Path wv = tmp.resolve("t.wv");
        Process p = new ProcessBuilder(Ffmpeg.binary(), "-y", "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=1:sample_rate=44100", wv.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor() == 0, "ffmpeg cannot encode WavPack here");
        try (AudioDecoder d = Decoders.open(SourceFormat.WAVPACK, wv)) {
            assertThat(d.sampleRate()).isEqualTo(44100);
            assertThat(d.totalSamples()).isEqualTo(44100);
        }
        Path m4b = tmp.resolve("book.m4b");
        Files.copy(org.kendar.dlna.testutil.AudioFiles.fixture("sweep.m4a", tmp.resolve("sweep.m4a")), m4b);
        try (AudioDecoder d = Decoders.open(SourceFormat.M4B, m4b)) {
            assertThat(d.totalSamples()).isBetween(87_000L, 89_500L);
        }
        Path he = org.kendar.dlna.testutil.AudioFiles.fixture("he-aac.m4a", tmp.resolve("he.m4a"));
        try (AudioDecoder d = Decoders.open(SourceFormat.AAC, he)) {
            assertThat(d.totalSamples()).isGreaterThan(0);
            assertThat(DecodersTest.readAll(d)).hasSize((int) d.totalSamples() * 4);
        }
    }
}
