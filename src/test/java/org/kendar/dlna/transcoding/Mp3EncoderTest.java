package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Spike item 1: jump3r low-level API behaviour (CBR, 48 kHz, no reservoir, no Xing, exact frames, seek, resample). */
class Mp3EncoderTest {

    private static final int KBPS = 320;

    @Test
    void frameMathIsIntegerForEveryBitrate() {
        assertThat(Mp3Frames.frameSize(320)).isEqualTo(960);
        assertThat(Mp3Frames.frameSize(128)).isEqualTo(384);
        assertThat(Mp3Frames.frameCount(1)).isEqualTo(1);
        assertThat(Mp3Frames.frameCount(1152)).isEqualTo(1);
        assertThat(Mp3Frames.frameCount(1153)).isEqualTo(2);
        assertThat(Mp3Frames.samplesAt48k(44100, 44100)).isEqualTo(48000);
        assertThat(Mp3Frames.contentLength(48000, 320)).isEqualTo(42L * 960);
    }

    @Test
    void streamHasConstantFramesNoXingNoId3NoReservoir() {
        byte[] pcm = TestAudio.sweep(200, 8000, 0.5, 44100, 44100 * 3);
        byte[] mp3 = Mp3Encoder.encodeExact(pcm, 44100, KBPS);
        Mp3Frames.Scan scan = Mp3Frames.scan(mp3);
        assertThat(scan.id3v2()).isFalse();
        assertThat(scan.leadingBytes()).isZero();
        assertThat(scan.trailingBytes()).isZero();
        assertThat(scan.allSameLength()).isTrue();
        assertThat(scan.frames()).allSatisfy(f -> {
            assertThat(f.length()).isEqualTo(960);
            assertThat(f.bitrateKbps()).isEqualTo(320);
            assertThat(f.sampleRate()).isEqualTo(48000);
            assertThat(f.padding()).isFalse();
            assertThat(f.xingOrInfo()).isFalse();
            assertThat(f.mainDataBegin()).isZero();
        });
        assertThat(scan.frames().get(0).offset()).isZero();
    }

    @Test
    void reservoirOnWouldCarryDataAcrossFrames() {
        // control experiment: proves the main_data_begin check above is meaningful
        byte[] pcm = TestAudio.sweep(200, 8000, 0.5, 44100, 44100 * 3);
        try (Mp3Encoder enc = new Mp3Encoder(44100, 128, false)) {
            byte[] mp3 = enc.encode(pcm, 0, pcm.length);
            long withBackRefs = Mp3Frames.scan(mp3).frames().stream().filter(f -> f.mainDataBegin() > 0).count();
            System.out.println("[spike] reservoir ON: frames with main_data_begin > 0 = " + withBackRefs);
        }
    }

    @Test
    void lengthMatchesAdvertisedFormulaForManySampleCounts() {
        int[] counts = {1, 100, 1151, 1152, 1153, 2304, 44100, 44101, 100_003, 44100 * 5};
        for (int rate : new int[] {44100, 48000, 32000, 22050}) {
            for (int n : counts) {
                byte[] pcm = TestAudio.sine(440, 0.3, rate, n);
                byte[] mp3 = Mp3Encoder.encodeExact(pcm, rate, KBPS);
                long expected = Mp3Frames.contentLength(Mp3Frames.samplesAt48k(n, rate), KBPS);
                assertThat((long) mp3.length).as("rate %d samples %d", rate, n).isEqualTo(expected);
                Mp3Frames.Scan scan = Mp3Frames.scan(mp3);
                assertThat(scan.trailingBytes()).isZero();
                assertThat(scan.frames()).hasSize((int) (expected / 960));
            }
        }
    }

    @Test
    void rawEncoderFrameCountVersusTarget() {
        // documents how far the raw LAME output is from the formula (padding / truncation needed)
        for (int n : new int[] {44100, 44100 * 4 + 17}) {
            byte[] pcm = TestAudio.sine(440, 0.3, 44100, n);
            try (Mp3Encoder enc = new Mp3Encoder(44100, KBPS)) {
                long frames = enc.encode(pcm, 0, pcm.length).length / 960;
                long target = Mp3Frames.frameCount(Mp3Frames.samplesAt48k(n, 44100));
                System.out.println("[spike] samples=" + n + " target frames=" + target + " raw frames before flush="
                        + frames);
            }
        }
    }

    @Test
    void outputDecodesWithJLayerToTheAdvertisedDuration() {
        int n = 44100 * 2 + 321;
        byte[] mp3 = Mp3Encoder.encodeExact(TestAudio.sweep(300, 6000, 0.5, 44100, n), 44100, KBPS);
        TestAudio.Mp3Pcm dec = TestAudio.decodeMp3(mp3);
        assertThat(dec.sampleRate()).isEqualTo(48000);
        assertThat(dec.channels()).isEqualTo(2);
        assertThat(dec.frames()).isEqualTo(mp3.length / 960);
        assertThat(dec.sampleFrames()).isEqualTo(dec.frames() * 1152);
    }

    @Test
    void freshEncoderAtArbitraryFrameIndexYieldsIndependentlyDecodableFramesAndMatchingAudio() {
        int rate = 44100;
        int total = rate * 6;
        byte[] pcm = TestAudio.sweep(200, 6000, 0.5, rate, total);
        byte[] full = Mp3Encoder.encodeExact(pcm, rate, KBPS);
        TestAudio.Mp3Pcm ref = TestAudio.decodeMp3(full);
        long fullFrames = full.length / 960;
        for (int k : new int[] {1, 37, 100, 211}) {
            // Range request -> frame index k -> time -> source sample; the decoder seeks, a fresh encoder starts there
            long startSample48 = (long) k * 1152;
            long startSource = startSample48 * rate / 48000;
            int remainingSource = (int) (total - startSource);
            byte[] tailPcm = Arrays.copyOfRange(pcm, (int) startSource * 4, total * 4);
            byte[] seeked = Mp3Encoder.encodeExact(tailPcm, rate, KBPS);
            long expectedFrames = Mp3Frames.frameCount(Mp3Frames.samplesAt48k(remainingSource, rate));
            Mp3Frames.Scan scan = Mp3Frames.scan(seeked);
            assertThat(scan.frames()).hasSize((int) expectedFrames);
            assertThat(scan.trailingBytes()).isZero();
            assertThat(scan.frames()).allSatisfy(f -> assertThat(f.mainDataBegin()).isZero());
            TestAudio.Mp3Pcm dec = TestAudio.decodeMp3(seeked);
            // total length of a "seek" response differs from the full-stream remainder by at most 1 frame
            assertThat(Math.abs((fullFrames - k) - dec.frames())).isLessThanOrEqualTo(1);
            double[] a = TestAudio.left(ref.samples(), 2, k * 1152 + 4000, 20000);
            double[] b = TestAudio.left(dec.samples(), 2, 4000, 20000 + 600);
            int lag = TestAudio.bestLag(a, b, 500);
            System.out.println("[spike] seek frame " + k + ": alignment lag vs continuous encode = " + lag
                    + " samples (" + (lag * 1000.0 / 48000) + " ms)");
            assertThat(Math.abs(lag)).isLessThan(1152);
        }
    }

    @Test
    void cutAtFrameBoundaryDecodesIdenticallyToContinuousStreamAfterWarmup() {
        byte[] pcm = TestAudio.sweep(200, 6000, 0.5, 44100, 44100 * 4);
        byte[] full = Mp3Encoder.encodeExact(pcm, 44100, KBPS);
        TestAudio.Mp3Pcm ref = TestAudio.decodeMp3(full);
        for (int k : new int[] {3, 50, 120}) {
            byte[] cut = Arrays.copyOfRange(full, k * 960, full.length);
            TestAudio.Mp3Pcm dec = TestAudio.decodeMp3(cut);
            assertThat(dec.frames()).isEqualTo(full.length / 960 - k);
            // synthesis filterbank + MDCT overlap need up to 2 frames to settle; afterwards bit-identical
            int from = 2 * 1152 * 2;
            for (int i = from; i < dec.samples().length; i++) {
                if (dec.samples()[i] != ref.samples()[k * 1152 * 2 + i]) {
                    throw new AssertionError("sample " + i + " differs after cut at frame " + k);
                }
            }
        }
    }

    @Test
    void resamplingKeepsLevelAndPurityOfTestTones() {
        int rate = 44100;
        StringBuilder report = new StringBuilder("[spike] 44.1->48 kHz, 320 kbps CBR, tone gain / SNR:");
        for (double f : new double[] {100, 440, 1000, 5000, 10000, 15000, 18000}) {
            byte[] mp3 = Mp3Encoder.encodeExact(TestAudio.sine(f, 0.5, rate, rate * 2), rate, KBPS);
            TestAudio.Mp3Pcm dec = TestAudio.decodeMp3(mp3);
            double[] x = TestAudio.left(dec.samples(), 2, 48000 / 2, 48000);
            TestAudio.ToneFit fit = TestAudio.fitTone(x, f, 48000);
            double gainDb = 20 * Math.log10(fit.amplitude() / 0.5);
            report.append(String.format("%n  %6.0f Hz: gain %+.2f dB, SNR %.1f dB", f, gainDb, fit.snrDb()));
            assertThat(Math.abs(gainDb)).as("gain at %.0f Hz", f).isLessThan(f <= 15000 ? 0.5 : 1.5);
            assertThat(fit.snrDb()).as("SNR at %.0f Hz", f).isGreaterThan(f <= 10000 ? 40 : 30);
        }
        System.out.println(report);
    }

    @Test
    void encodingIsFasterThanRealTimeAndDeterministic() {
        byte[] pcm = TestAudio.sweep(100, 12000, 0.5, 44100, 44100 * 30);
        long t0 = System.nanoTime();
        byte[] a = Mp3Encoder.encodeExact(pcm, 44100, KBPS);
        double secs = (System.nanoTime() - t0) / 1e9;
        byte[] b = Mp3Encoder.encodeExact(pcm, 44100, KBPS);
        System.out.printf("[spike] encode speed: 30 s of audio in %.2f s (%.0fx real time, incl. JIT warm-up)%n",
                secs, 30 / secs);
        assertThat(a).isEqualTo(b);
        assertThat(secs).isLessThan(30);
    }

    @Test
    void sourcesBelow32kHzMustBeUpsampledBecauseLameLowersTheBitrate() {
        assertThatThrownBy(() -> new Mp3Encoder(22050, KBPS)).isInstanceOf(IllegalArgumentException.class);
        for (int rate : new int[] {8000, 11025, 16000, 22050, 24000}) {
            int n = rate * 2 + 7;
            byte[] mp3 = Mp3Encoder.encodeExact(TestAudio.sine(1000, 0.5, rate, n), rate, KBPS);
            Mp3Frames.Scan scan = Mp3Frames.scan(mp3);
            assertThat(mp3.length).isEqualTo((int) Mp3Frames.contentLength(Mp3Frames.samplesAt48k(n, rate), KBPS));
            assertThat(scan.frames()).allSatisfy(f -> assertThat(f.bitrateKbps()).isEqualTo(320));
            TestAudio.Mp3Pcm dec = TestAudio.decodeMp3(mp3);
            TestAudio.ToneFit fit = TestAudio.fitTone(TestAudio.left(dec.samples(), 2, 24000, 48000), 1000, 48000);
            double gainDb = 20 * Math.log10(fit.amplitude() / 0.5);
            System.out.printf("[spike] low-rate %d Hz -> upsampled: gain %+.2f dB, SNR %.1f dB%n", rate, gainDb, fit.snrDb());
            assertThat(Math.abs(gainDb)).isLessThan(0.5);
            assertThat(fit.snrDb()).isGreaterThan(40);
        }
    }

    @Test
    void effectiveParametersAreAsConfigured() {
        try (Mp3Encoder enc = new Mp3Encoder(44100, 256)) {
            assertThat(enc.effectiveSampleRate()).isEqualTo(48000);
            assertThat(enc.effectiveBitrateKbps()).isEqualTo(256);
            assertThat(enc.frameSize()).isEqualTo(768);
        }
    }
}
