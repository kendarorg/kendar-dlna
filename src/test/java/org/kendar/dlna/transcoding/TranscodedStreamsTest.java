package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MP3 and LPCM streams: exact lengths, constant frames, determinism, offsets. */
class TranscodedStreamsTest {

    @TempDir
    Path tmp;

    private final Transcoder transcoder = new Transcoder(null);

    private static byte[] drain(InputStream in) throws IOException {
        try (InputStream s = in) {
            return s.readAllBytes();
        }
    }

    private Path sineWav(String name, int rate, int channels, int bits, int frames) throws IOException {
        double[] sine = new double[frames];
        for (int i = 0; i < frames; i++) {
            sine[i] = 0.5 * Math.sin(2 * Math.PI * 1000 * i / rate);
        }
        Path p = tmp.resolve(name);
        DecodersTest.writeWav(p, sine, rate, channels, bits);
        return p;
    }

    @Test
    void streamingUpsamplerEqualsTheWholeBufferVersionForAnyChunking() {
        Random rnd = new Random(7);
        for (int rate : new int[] {8000, 11025, 22050, 24000}) {
            byte[] pcm = new byte[4 * 700];
            for (int i = 0; i < 700; i++) {
                short l = (short) (rnd.nextInt(20000) - 10000);
                short r = (short) (rnd.nextInt(20000) - 10000);
                pcm[i * 4] = (byte) l;
                pcm[i * 4 + 1] = (byte) (l >> 8);
                pcm[i * 4 + 2] = (byte) r;
                pcm[i * 4 + 3] = (byte) (r >> 8);
            }
            byte[] expected = LowRateUpsampler.upsample(pcm, rate);
            for (int chunkFrames : new int[] {1, 7, 100, 700}) {
                StreamingUpsampler up = new StreamingUpsampler(rate);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                for (int f = 0; f < 700; f += chunkFrames) {
                    int n = Math.min(chunkFrames, 700 - f);
                    out.writeBytes(up.process(pcm, f * 4, n * 4));
                }
                out.writeBytes(up.flush());
                assertThat(out.toByteArray()).as("rate " + rate + " chunk " + chunkFrames).isEqualTo(expected);
            }
        }
    }

    @Test
    void mp3LengthIsExactAndFramesAreConstantForEverySourceRate() throws Exception {
        for (int rate : new int[] {8000, 22050, 32000, 44100, 48000, 96000}) {
            int frames = rate / 2 + 123;
            Path wav = sineWav("r" + rate + ".wav", rate, 2, 16, frames);
            long expected = Transcoder.mp3Length(frames, rate, 320);
            byte[] mp3 = drain(transcoder.openMp3(SourceFormat.WAV, wav, 320, 0));
            assertThat((long) mp3.length).as("rate " + rate).isEqualTo(expected);
            Mp3Frames.Scan scan = Mp3Frames.scan(mp3);
            assertThat(scan.leadingBytes()).isZero();
            assertThat(scan.trailingBytes()).isZero();
            assertThat(scan.allSameLength()).isTrue();
            assertThat(scan.frames()).hasSize((int) Mp3Frames.frameCount(Mp3Frames.samplesAt48k(frames, rate)));
            assertThat(scan.frames()).allSatisfy(f -> {
                assertThat(f.length()).isEqualTo(960);
                assertThat(f.sampleRate()).isEqualTo(48000);
                assertThat(f.bitrateKbps()).isEqualTo(320);
                assertThat(f.mainDataBegin()).isZero();
                assertThat(f.xingOrInfo()).isFalse();
            });
        }
    }

    @Test
    void mp3DecodesToTheRightDurationAndTone() throws Exception {
        int frames = 44100;
        Path wav = sineWav("tone.wav", 44100, 2, 16, frames);
        byte[] mp3 = drain(transcoder.openMp3(SourceFormat.WAV, wav, 320, 0));
        TestAudio.Mp3Pcm pcm = TestAudio.decodeMp3(mp3);
        assertThat(pcm.sampleRate()).isEqualTo(48000);
        assertThat(pcm.sampleFrames()).isEqualTo(Mp3Frames.frameCount(Mp3Frames.samplesAt48k(frames, 44100)) * 1152);
        TestAudio.ToneFit fit = TestAudio.fitTone(TestAudio.left(pcm.samples(), 2, 4800, 24000), 1000, 48000);
        assertThat(fit.amplitude()).isBetween(0.45, 0.55);
        assertThat(fit.snrDb()).isGreaterThan(40);
    }

    @Test
    void monoAndLowRateSourcesGiveStereoMp3At320() throws Exception {
        Path mono = sineWav("mono.wav", 16000, 1, 16, 16000);
        byte[] mp3 = drain(transcoder.openMp3(SourceFormat.WAV, mono, 320, 0));
        assertThat((long) mp3.length).isEqualTo(Transcoder.mp3Length(16000, 16000, 320));
        TestAudio.Mp3Pcm pcm = TestAudio.decodeMp3(mp3);
        assertThat(pcm.channels()).isEqualTo(2);
        TestAudio.ToneFit fit = TestAudio.fitTone(TestAudio.left(pcm.samples(), 2, 4800, 24000), 1000, 48000);
        assertThat(fit.amplitude()).isBetween(0.45, 0.55);
    }

    @Test
    void everyFixtureFormatGivesTheAdvertisedLength() throws Exception {
        Object[][] cases = {
            {SourceFormat.MP3, "sweep.mp3"}, {SourceFormat.OGG, "sweep.ogg"}, {SourceFormat.FLAC, "sweep.flac"},
            {SourceFormat.WAV, "sweep.wav"}, {SourceFormat.AIFF, "sweep.aiff"}, {SourceFormat.OPUS, "sweep.opus"}};
        for (Object[] c : cases) {
            SourceFormat f = (SourceFormat) c[0];
            Path p = TestAudio.resource((String) c[1]);
            Transcoder.SourceInfo info = transcoder.probe(f, p);
            long mp3Len = Transcoder.mp3Length(info.totalSamples(), info.sampleRate(), 320);
            assertThat((long) drain(transcoder.openMp3(f, p, 320, 0)).length).as("mp3 " + f).isEqualTo(mp3Len);
            assertThat((long) drain(transcoder.openMp3(f, p, 192, 0)).length).as("mp3 192 " + f)
                    .isEqualTo(Transcoder.mp3Length(info.totalSamples(), info.sampleRate(), 192));
            assertThat((long) drain(transcoder.openLpcm(f, p, 0)).length).as("lpcm " + f)
                    .isEqualTo(Transcoder.lpcmLength(info.totalSamples()));
        }
    }

    @Test
    void twoEncodesAreByteIdentical() throws Exception {
        Path p = TestAudio.resource("sweep.flac");
        assertThat(drain(transcoder.openMp3(SourceFormat.FLAC, p, 320, 0)))
                .isEqualTo(drain(transcoder.openMp3(SourceFormat.FLAC, p, 320, 0)));
    }

    @Test
    void offsetStreamsHaveTheExactRemainingLengthAndStayValidMp3() throws Exception {
        Path p = TestAudio.resource("sweep.wav");
        Transcoder.SourceInfo info = transcoder.probe(SourceFormat.WAV, p);
        long total = Transcoder.mp3Length(info.totalSamples(), info.sampleRate(), 320);
        for (long offset : new long[] {960, 960L * 37, 960L * 37 + 123, total - 960, total - 5, 5}) {
            byte[] part = drain(transcoder.openMp3(SourceFormat.WAV, p, 320, offset));
            assertThat((long) part.length).as("offset " + offset).isEqualTo(total - offset);
        }
        // frame-aligned starts decode from byte 0 and carry the expected number of frames
        byte[] aligned = drain(transcoder.openMp3(SourceFormat.WAV, p, 320, 960L * 37));
        Mp3Frames.Scan scan = Mp3Frames.scan(aligned);
        assertThat(scan.frames()).hasSize((int) (total / 960 - 37));
        assertThat(scan.trailingBytes()).isZero();
        assertThat(scan.frames()).allSatisfy(f -> assertThat(f.mainDataBegin()).isZero());
        TestAudio.Mp3Pcm pcm = TestAudio.decodeMp3(aligned);
        assertThat(pcm.sampleFrames()).isEqualTo(scan.frames().size() * 1152);
        // a mid-frame start is the frame-aligned stream minus its first bytes
        byte[] mid = drain(transcoder.openMp3(SourceFormat.WAV, p, 320, 960L * 37 + 123));
        assertThat(mid).isEqualTo(Arrays.copyOfRange(aligned, 123, aligned.length));
    }

    @Test
    void seekedAudioLinesUpWithTheContinuousEncode() throws Exception {
        int frames = 48000 * 3;
        Path wav = tmp.resolve("sweep48.wav");
        byte[] sweep = TestAudio.sweep(200, 4000, 0.5, 48000, frames);
        java.nio.file.Files.write(wav, TestAudio.wav(sweep, 48000, 2));
        byte[] full = drain(transcoder.openMp3(SourceFormat.WAV, wav, 320, 0));
        byte[] seeked = drain(transcoder.openMp3(SourceFormat.WAV, wav, 320, 960L * 50));
        short[] a = TestAudio.decodeMp3(full).samples();
        short[] b = TestAudio.decodeMp3(seeked).samples();
        double[] x = new double[4000];
        double[] y = new double[4000];
        for (int i = 0; i < 4000; i++) {
            x[i] = a[(50 * 1152 + 5000 + i) * 2] / 32768.0;
            y[i] = b[(5000 + i) * 2] / 32768.0;
        }
        assertThat(Math.abs(TestAudio.bestLag(x, y, 600))).isLessThanOrEqualTo(2);
    }

    @Test
    void lpcmIsBigEndianStereoWithExactLengthAndByteExactSeek() throws Exception {
        Path p = TestAudio.resource("sweep.wav");
        byte[] le = DecodersTest.readAll(Decoders.open(SourceFormat.WAV, p));
        byte[] lpcm = drain(transcoder.openLpcm(SourceFormat.WAV, p, 0));
        assertThat(lpcm).hasSize(le.length);
        for (int i = 0; i < le.length; i += 2) {
            assertThat(lpcm[i]).isEqualTo(le[i + 1]);
            assertThat(lpcm[i + 1]).isEqualTo(le[i]);
            if (i > 2000) {
                break;
            }
        }
        for (long offset : new long[] {1, 2, 3, 4, 4003, 12345 * 4L, lpcm.length - 1}) {
            assertThat(drain(transcoder.openLpcm(SourceFormat.WAV, p, offset)))
                    .as("offset " + offset).isEqualTo(Arrays.copyOfRange(lpcm, (int) offset, lpcm.length));
        }
        assertThat(LpcmStream.mimeType(44100)).isEqualTo("audio/L16;rate=44100;channels=2");
    }

    @Test
    void midiWithoutRendererIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transcoder.renderMidi(tmp.resolve("x.mid")))
                .isInstanceOf(IOException.class);
        assertThat(transcoder.midiAvailable()).isFalse();
    }

    @Test
    void renderedPcmIsServedLikeAnyDecoder() throws Exception {
        byte[] pcm = TestAudio.sine(440, 0.4, 48000, 48000 + 7);
        Transcoder.Rendered r = new Transcoder.Rendered(pcm, 48000);
        assertThat(r.frames()).isEqualTo(48007);
        byte[] mp3 = drain(Transcoder.openMp3(r, 320, 0));
        assertThat((long) mp3.length).isEqualTo(Transcoder.mp3Length(48007, 48000, 320));
        assertThat(drain(Transcoder.openLpcm(r, 0))).hasSize(48007 * 4);
    }
}
