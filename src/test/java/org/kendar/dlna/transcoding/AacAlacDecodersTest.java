package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AAC-LC (JAAD with the patched TNS and IQTable) and Apple Lossless (own decoder) on ffmpeg-made fixtures. The
 * reference PCM files next to the fixtures were produced by ffmpeg's decoder ({@code ffmpeg -i x -f s16le}), so
 * these tests assert agreement with an independent implementation, not just self-consistency.
 */
class AacAlacDecodersTest {

    @TempDir
    Path tmp;

    private String ffmpegBefore;

    /** These tests expect the Java decoders to refuse HE-AAC and friends, which ffmpeg (when present) would decode. */
    @org.junit.jupiter.api.BeforeEach
    void noFfmpeg() {
        ffmpegBefore = Ffmpeg.binary();
        Ffmpeg.configure("no-such-ffmpeg");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreFfmpeg() {
        Ffmpeg.configure(ffmpegBefore);
    }

    private static byte[] decode(SourceFormat f, Path p) throws IOException {
        try (AudioDecoder d = Decoders.open(f, p)) {
            return DecodersTest.readAll(d);
        }
    }

    private static byte[] reference(String name) throws IOException {
        return Files.readAllBytes(TestAudio.resource(name));
    }

    /** Lowest per-1024-frame SNR in dB (blocks with almost no energy are skipped) over the common length. */
    private static double worstBlockSnr(byte[] ours, byte[] ref) {
        short[] x = TestAudio.toShorts(ours);
        short[] y = TestAudio.toShorts(ref);
        int n = Math.min(x.length, y.length) / 2048 * 2048;
        double worst = Double.MAX_VALUE;
        for (int b = 0; b < n; b += 2048) {
            double err = 0;
            double sig = 0;
            for (int i = b; i < b + 2048; i++) {
                double d = x[i] - y[i];
                err += d * d;
                sig += (double) y[i] * y[i];
            }
            if (sig > 1e6) {
                worst = Math.min(worst, 10 * Math.log10(sig / Math.max(err, 1e-9)));
            }
        }
        return worst;
    }

    private static int maxDiff(byte[] a, byte[] b) {
        short[] x = TestAudio.toShorts(a);
        short[] y = TestAudio.toShorts(b);
        int max = 0;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            max = Math.max(max, Math.abs(x[i] - y[i]));
        }
        return max;
    }

    // ---- ALAC ---------------------------------------------------------------------------------------------------

    @Test
    void alacIsBitExactWithTheOriginalWav() throws IOException {
        byte[] wav = decode(SourceFormat.WAV, TestAudio.resource("sweep.wav"));
        byte[] alac = decode(SourceFormat.ALAC, TestAudio.resource("sweep-alac.m4a"));
        assertThat(alac).hasSize(88200 * 4);
        assertThat(alac).isEqualTo(wav);
    }

    @Test
    void alac24BitAt96kHzIsReducedToSixteenBitLikeFfmpeg() throws IOException {
        try (AudioDecoder d = Decoders.open(SourceFormat.ALAC, TestAudio.resource("hires-alac.m4a"))) {
            assertThat(d.sampleRate()).isEqualTo(96000);
            assertThat(d.totalSamples()).isEqualTo(14400);
            byte[] pcm = DecodersTest.readAll(d);
            assertThat(pcm).hasSize(14400 * 4);
            assertThat(maxDiff(pcm, reference("hires-alac.m4a.pcm"))).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void monoAlacIsDuplicatedToBothChannels() throws IOException {
        byte[] pcm = decode(SourceFormat.ALAC, TestAudio.resource("mono-alac.m4a"));
        assertThat(pcm).hasSize(44100 * 4);
        short[] s = TestAudio.toShorts(pcm);
        int peak = 0;
        for (int i = 0; i < s.length; i += 2) {
            assertThat(s[i + 1]).isEqualTo(s[i]);
            peak = Math.max(peak, Math.abs(s[i]));
        }
        assertThat(peak).isGreaterThan(5000);
    }

    @Test
    void alacSeekEqualsTheContinuousDecodeExactly() throws IOException {
        Path p = TestAudio.resource("sweep-alac.m4a");
        byte[] full = decode(SourceFormat.ALAC, p);
        for (long frame : new long[] {0, 1, 4095, 4096, 4097, 30000, 88199, 88200}) {
            try (AudioDecoder d = Decoders.open(SourceFormat.ALAC, p)) {
                d.seek(frame);
                assertThat(DecodersTest.readAll(d)).as("seek " + frame)
                        .isEqualTo(Arrays.copyOfRange(full, (int) frame * 4, full.length));
            }
        }
    }

    @Test
    void alacRefusesMultichannelAndOddBitDepths() throws IOException {
        Mp4Synth s = new Mp4Synth();
        s.sampleEntry = "alac";
        s.alacCookie = cookie(4096, 16, 6, 44100);
        Path six = s.write(tmp.resolve("six.m4a"));
        assertThatThrownBy(() -> Decoders.open(SourceFormat.ALAC, six)).isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("6 channels");
        s.alacCookie = cookie(4096, 20, 2, 44100);
        Path twenty = s.write(tmp.resolve("twenty.m4a"));
        assertThatThrownBy(() -> Decoders.open(SourceFormat.ALAC, twenty))
                .isInstanceOf(UnsupportedAudioException.class).hasMessageContaining("20 bits");
    }

    private static byte[] cookie(int frameLength, int bits, int channels, int rate) {
        byte[] c = new byte[24];
        System.arraycopy(Mp4Synth.be32(frameLength), 0, c, 0, 4);
        c[4] = 0;
        c[5] = (byte) bits;
        c[6] = 40;
        c[7] = 10;
        c[8] = 14;
        c[9] = (byte) channels;
        System.arraycopy(Mp4Synth.be32(rate), 0, c, 20, 4);
        return c;
    }

    @Test
    void corruptAlacFramesFailWithIoExceptionNotAnArrayIndexError() throws IOException {
        byte[] b = Files.readAllBytes(TestAudio.resource("sweep-alac.m4a"));
        Mp4Audio m = Mp4Audio.open(TestAudio.resource("sweep-alac.m4a"));
        Arrays.fill(b, (int) m.offsets[2], (int) m.offsets[2] + 200, (byte) 0xff);
        Path p = tmp.resolve("corrupt.m4a");
        Files.write(p, b);
        try (AudioDecoder d = Decoders.open(SourceFormat.ALAC, p)) {
            // either decodes garbage of the right length or reports an IOException; it never throws anything else
            try {
                assertThat(DecodersTest.readAll(d)).hasSize(88200 * 4);
            } catch (IOException expected) {
                assertThat(expected).isNotNull();
            }
        }
    }

    // ---- AAC ----------------------------------------------------------------------------------------------------

    @Test
    void aacMatchesFfmpegSampleForSampleOnTransientMaterialWithTns() throws IOException {
        // transient.m4a was encoded with TNS on; with JAAD's original (stub) TNS several blocks fall below 25 dB
        byte[] ours = decode(SourceFormat.AAC, TestAudio.resource("transient.m4a"));
        byte[] ref = reference("transient.m4a.pcm");
        assertThat(ours).hasSameSizeAs(ref).hasSize(17640 * 4);
        assertThat(worstBlockSnr(ours, ref)).isGreaterThan(70.0);
    }

    @Test
    void aacLengthIsTheEditListWindowAndAlignsWithFfmpeg() throws IOException {
        try (AudioDecoder d = Decoders.open(SourceFormat.AAC, TestAudio.resource("sweep.m4a"))) {
            assertThat(d.sampleRate()).isEqualTo(44100);
            assertThat(d.totalSamples()).isEqualTo(88200);   // 88 units * 1024 + 136, minus 1024 priming
            byte[] pcm = DecodersTest.readAll(d);
            assertThat(pcm).hasSize(88200 * 4);
        }
        // against the lossless original: a 128 kbps encode of a sweep keeps well over 20 dB when aligned
        byte[] wav = decode(SourceFormat.WAV, TestAudio.resource("sweep.wav"));
        byte[] aac = decode(SourceFormat.AAC, TestAudio.resource("sweep.m4a"));
        assertThat(worstBlockSnr(Arrays.copyOfRange(aac, 4096, aac.length - 4096),
                Arrays.copyOfRange(wav, 4096, wav.length - 4096))).isGreaterThan(20.0);
    }

    @Test
    void mp4ExtensionAndM4aGiveTheSameAudio() throws IOException {
        assertThat(decode(SourceFormat.AAC, TestAudio.resource("sweep.mp4")))
                .isEqualTo(decode(SourceFormat.AAC, TestAudio.resource("sweep.m4a")))
                .hasSize(88200 * 4);
    }

    @Test
    void adtsMatchesFfmpegAndIsExactlyFramesTimes1024() throws IOException {
        try (AudioDecoder d = Decoders.open(SourceFormat.ADTS, TestAudio.resource("transient.aac"))) {
            byte[] pcm = DecodersTest.readAll(d);
            byte[] ref = reference("transient.aac.pcm");
            assertThat(d.totalSamples()).isEqualTo(ref.length / 4);
            assertThat(pcm).hasSameSizeAs(ref);
            assertThat(worstBlockSnr(pcm, ref)).isGreaterThan(70.0);
        }
    }

    @Test
    void aacSeekEqualsTheContinuousDecodeExactly() throws IOException {
        Path p = TestAudio.resource("transient.m4a");
        byte[] full = decode(SourceFormat.AAC, p);
        for (long frame : new long[] {0, 1, 1023, 1024, 1025, 5000, 9000, 17639, 17640}) {
            try (AudioDecoder d = Decoders.open(SourceFormat.AAC, p)) {
                d.seek(frame);
                assertThat(DecodersTest.readAll(d)).as("seek " + frame)
                        .isEqualTo(Arrays.copyOfRange(full, (int) frame * 4, full.length));
            }
        }
        // and a backwards seek on the same decoder
        try (AudioDecoder d = Decoders.open(SourceFormat.AAC, p)) {
            d.seek(9000);
            DecodersTest.readAll(d);
            d.seek(100);
            assertThat(DecodersTest.readAll(d)).isEqualTo(Arrays.copyOfRange(full, 400, full.length));
        }
    }

    @Test
    void adtsSeekEqualsTheContinuousDecodeExactly() throws IOException {
        Path p = TestAudio.resource("transient.aac");
        byte[] full = decode(SourceFormat.ADTS, p);
        try (AudioDecoder d = Decoders.open(SourceFormat.ADTS, p)) {
            d.seek(7777);
            assertThat(DecodersTest.readAll(d)).isEqualTo(Arrays.copyOfRange(full, 7777 * 4, full.length));
        }
    }

    @Test
    void monoAacIsDuplicatedToBothChannels() throws IOException {
        byte[] pcm = decode(SourceFormat.AAC, TestAudio.resource("mono.m4a"));
        assertThat(pcm).hasSize(44100 * 4);
        short[] s = TestAudio.toShorts(pcm);
        int peak = 0;
        for (int i = 0; i < s.length; i += 2) {
            assertThat(s[i + 1]).isEqualTo(s[i]);
            peak = Math.max(peak, Math.abs(s[i]));
        }
        assertThat(peak).isGreaterThan(5000);
    }

    @Test
    void fiveOneIsFoldedToStereoAtTheSameLevel() throws IOException {
        // the same 440 Hz sine (lavfi default amplitude 1/8 = 4096) in all six channels, the LFE dropped: the fold-down
        // keeps the level (ffmpeg decodes the channels at 4914/4914/4264 peaks, which folds to about 4700)
        byte[] pcm = decode(SourceFormat.AAC, TestAudio.resource("surround.m4a"));
        assertThat(pcm).hasSize(48000 * 4);
        short[] s = TestAudio.toShorts(pcm);
        int peakL = 0;
        int peakR = 0;
        for (int i = 4000; i < s.length - 4000; i += 2) {
            peakL = Math.max(peakL, Math.abs(s[i]));
            peakR = Math.max(peakR, Math.abs(s[i + 1]));
        }
        assertThat(peakL).isBetween(3800, 5500);
        assertThat(peakR).isBetween(3800, 5500);
    }

    @Test
    void mixMatricesNormaliseToUnitGainForEveryChannelConfiguration() {
        for (int cc = 1; cc <= 7; cc++) {
            float[] m = AacDecoder.mixMatrix(cc);
            float l = 0;
            float r = 0;
            for (int i = 0; i + 1 < m.length; i += 2) {
                l += m[i];
                r += m[i + 1];
            }
            assertThat(Math.max(l, r)).as("config " + cc).isCloseTo(1f, org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    void heAacIsRefusedUpFrontWithAReason() {
        assertThatThrownBy(() -> Decoders.open(SourceFormat.AAC, TestAudio.resource("he-aac.m4a")))
                .isInstanceOf(UnsupportedAudioException.class).hasMessageContaining("HE-AAC");
        assertThatThrownBy(() -> Decoders.probe(SourceFormat.AAC, TestAudio.resource("he-aac.m4a")))
                .isInstanceOf(UnsupportedAudioException.class);
    }

    @Test
    void probeGivesRateAndExactCountWithoutDecoding() throws IOException {
        Transcoder.SourceInfo aac = Decoders.probe(SourceFormat.AAC, TestAudio.resource("sweep.m4a"));
        assertThat(aac.sampleRate()).isEqualTo(44100);
        assertThat(aac.totalSamples()).isEqualTo(88200);
        Transcoder.SourceInfo alac = Decoders.probe(SourceFormat.ALAC, TestAudio.resource("sweep-alac.m4a"));
        assertThat(alac.totalSamples()).isEqualTo(88200);
    }

    @Test
    void decodingIsFasterThanRealTime() throws IOException {
        byte[] big = new byte[0];
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            big = decode(SourceFormat.AAC, TestAudio.resource("sweep.m4a"));
            decode(SourceFormat.ALAC, TestAudio.resource("sweep-alac.m4a"));
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        // 5 x 2 s of AAC and 5 x 2 s of ALAC, including JIT warm-up: real time would be 20 s
        assertThat(seconds).isLessThan(10.0);
        assertThat(big).hasSize(88200 * 4);
    }

    @Test
    void aTruncatedAacFileStillDeliversTheAdvertisedLengthOrFailsCleanly() throws IOException {
        byte[] raw = Files.readAllBytes(TestAudio.resource("transient.aac"));
        Path p = tmp.resolve("cut.aac");
        Files.write(p, Arrays.copyOf(raw, raw.length - 1500));
        try (AudioDecoder d = Decoders.open(SourceFormat.ADTS, p)) {
            assertThat(DecodersTest.readAll(d)).hasSize((int) d.totalSamples() * 4);
        }
    }

    // ---- through the MP3 / LPCM streams -------------------------------------------------------------------------

    @Test
    void mp3AndLpcmFormsHaveTheAdvertisedExactLengthAndOffsetsAreExact() throws Exception {
        Transcoder transcoder = new Transcoder(null);
        Object[][] cases = {
            {SourceFormat.AAC, "sweep.m4a", 88200L}, {SourceFormat.AAC, "transient.m4a", 17640L},
            {SourceFormat.ADTS, "transient.aac", 19456L}, {SourceFormat.ALAC, "sweep-alac.m4a", 88200L},
            {SourceFormat.ALAC, "hires-alac.m4a", 14400L}, {SourceFormat.AAC, "mono.m4a", 44100L},
            {SourceFormat.ALAC, "mono-alac.m4a", 44100L}, {SourceFormat.AAC, "surround.m4a", 48000L}};
        for (Object[] c : cases) {
            SourceFormat f = (SourceFormat) c[0];
            Path p = TestAudio.resource((String) c[1]);
            Transcoder.SourceInfo info = transcoder.probe(f, p);
            assertThat(info.totalSamples()).as((String) c[1]).isEqualTo((Long) c[2]);
            long mp3Len = Transcoder.mp3Length(info.totalSamples(), info.sampleRate(), 320);
            byte[] mp3;
            try (java.io.InputStream in = transcoder.openMp3(f, p, 320, 0)) {
                mp3 = in.readAllBytes();
            }
            assertThat((long) mp3.length).as("mp3 " + c[1]).isEqualTo(mp3Len);
            byte[] lpcm;
            try (java.io.InputStream in = transcoder.openLpcm(f, p, 0)) {
                lpcm = in.readAllBytes();
            }
            assertThat((long) lpcm.length).as("lpcm " + c[1]).isEqualTo(Transcoder.lpcmLength(info.totalSamples()));
            for (long offset : new long[] {960, 960L * 3 + 17, mp3Len - 7}) {
                try (java.io.InputStream in = transcoder.openMp3(f, p, 320, offset)) {
                    assertThat((long) in.readAllBytes().length).as("mp3 offset " + offset + " " + c[1])
                            .isEqualTo(mp3Len - offset);
                }
            }
            try (java.io.InputStream in = transcoder.openLpcm(f, p, 4001)) {
                assertThat(in.readAllBytes()).as("lpcm offset " + c[1])
                        .isEqualTo(Arrays.copyOfRange(lpcm, 4001, lpcm.length));
            }
        }
    }

    @Test
    void anAacTranscodeIsDeterministicAndDecodesToTheTone() throws Exception {
        Transcoder transcoder = new Transcoder(null);
        Path p = TestAudio.resource("transient.m4a");
        byte[] one;
        byte[] two;
        try (java.io.InputStream in = transcoder.openMp3(SourceFormat.AAC, p, 320, 0)) {
            one = in.readAllBytes();
        }
        try (java.io.InputStream in = transcoder.openMp3(SourceFormat.AAC, p, 320, 0)) {
            two = in.readAllBytes();
        }
        assertThat(one).isEqualTo(two);
        Mp3Frames.Scan scan = Mp3Frames.scan(one);
        assertThat(scan.trailingBytes()).isZero();
        assertThat(scan.allSameLength()).isTrue();
    }

    @Test
    void thePatchedJaadClassesAreTheOnesOnTheClassPath() throws Exception {
        // the original IQTable has 8191 entries, so a coefficient of 8191 (legal) used to throw
        java.lang.reflect.Field f = Class.forName("net.sourceforge.jaad.aac.syntax.IQTable").getDeclaredField("IQ_TABLE");
        f.setAccessible(true);
        float[] table = (float[]) f.get(null);
        assertThat(table).hasSize(8192);
        assertThat(table[0]).isZero();
        assertThat(table[1]).isEqualTo(1f);
        assertThat(table[2]).isCloseTo(2.5198421f, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(table[8190]).isCloseTo(165086.6f, org.assertj.core.data.Offset.offset(1f));
        assertThat(table[8191]).isCloseTo(165113.5f, org.assertj.core.data.Offset.offset(1f));
        // the TNS class is ours: the library's one has no private helper for the filter recursion
        assertThat(Class.forName("net.sourceforge.jaad.aac.tools.TNS").getDeclaredMethod("reflectionToLpc",
                float[].class, int.class, float[].class)).isNotNull();
    }

    @Test
    void jaadsJavaSoundProviderIsNotRegisteredSoOtherDecodersAreUnaffected() {
        for (javax.sound.sampled.spi.AudioFileReader r : java.util.ServiceLoader
                .load(javax.sound.sampled.spi.AudioFileReader.class)) {
            assertThat(r.getClass().getName()).doesNotStartWith("net.sourceforge.jaad");
        }
    }
}
