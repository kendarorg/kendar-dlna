package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The production decoders on the fixtures and on generated files: exact lengths, seek equals continuous decode. */
class DecodersTest {

    private static final int SWEEP_FRAMES = 88200;

    @TempDir
    Path tmp;

    static byte[] readAll(AudioDecoder d) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[7000];
        int n;
        while ((n = d.read(buf, 0, buf.length)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] decodeAll(SourceFormat f, Path p) throws IOException {
        try (AudioDecoder d = Decoders.open(f, p)) {
            return readAll(d);
        }
    }

    private static byte[] decodeFrom(SourceFormat f, Path p, long frame) throws IOException {
        try (AudioDecoder d = Decoders.open(f, p)) {
            d.seek(frame);
            return readAll(d);
        }
    }

    /** Seek(frame) must equal the tail of the continuous decode, up to {@code tolerance} LSB. */
    private static void assertSeekMatches(SourceFormat f, Path p, long frame, int tolerance) throws IOException {
        byte[] full = decodeAll(f, p);
        byte[] seeked = decodeFrom(f, p, frame);
        assertThat(seeked).hasSize(full.length - (int) frame * 4);
        short[] a = TestAudio.toShorts(Arrays.copyOfRange(full, (int) frame * 4, full.length));
        short[] b = TestAudio.toShorts(seeked);
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        assertThat(max).as("max difference after seek to " + frame).isLessThanOrEqualTo(tolerance);
    }

    @Test
    void mp3HasExactHeaderWalkLengthAndSeeksLikeTheContinuousDecode() throws Exception {
        Path mp3 = TestAudio.resource("sweep.mp3");
        Transcoder.SourceInfo info = Decoders.probe(SourceFormat.MP3, mp3);
        assertThat(info.sampleRate()).isEqualTo(44100);
        assertThat(info.totalSamples()).isEqualTo(91008);
        byte[] full = decodeAll(SourceFormat.MP3, mp3);
        assertThat(full).hasSize(91008 * 4);
        // identical to the plain JLayer decode (stereo)
        TestAudio.Mp3Pcm ref = TestAudio.decodeMp3(Files.readAllBytes(mp3));
        assertThat(TestAudio.toShorts(full)).isEqualTo(ref.samples());
        assertSeekMatches(SourceFormat.MP3, mp3, 40L * 1152 + 77, 0);
        assertSeekMatches(SourceFormat.MP3, mp3, 3, 0);
        assertSeekMatches(SourceFormat.MP3, mp3, 91008 - 5, 0);
    }

    @Test
    void vorbisLengthIsTheLastGranuleAndSeekIsSampleAccurate() throws Exception {
        Path ogg = TestAudio.resource("sweep.ogg");
        assertThat(Decoders.probe(SourceFormat.OGG, ogg)).isEqualTo(new Transcoder.SourceInfo(44100, SWEEP_FRAMES));
        assertThat(decodeAll(SourceFormat.OGG, ogg)).hasSize(SWEEP_FRAMES * 4);
        for (long frame : new long[] {1, 1000, 12345, 44100, 70000, SWEEP_FRAMES - 10}) {
            assertSeekMatches(SourceFormat.OGG, ogg, frame, 1);
        }
    }

    @Test
    void flacIsBitExactAgainstTheWavSibling() throws Exception {
        Path flac = TestAudio.resource("sweep.flac");
        assertThat(Decoders.probe(SourceFormat.FLAC, flac)).isEqualTo(new Transcoder.SourceInfo(44100, SWEEP_FRAMES));
        byte[] wav = decodeAll(SourceFormat.WAV, TestAudio.resource("sweep.wav"));
        byte[] decoded = decodeAll(SourceFormat.FLAC, flac);
        assertThat(decoded).hasSize(SWEEP_FRAMES * 4);
        // the fixture is 24-bit or 16-bit: either way the 16-bit result matches the WAV within one LSB
        short[] a = TestAudio.toShorts(wav);
        short[] b = TestAudio.toShorts(decoded);
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        assertThat(max).isLessThanOrEqualTo(1);
        assertSeekMatches(SourceFormat.FLAC, flac, 50000, 0);
        assertSeekMatches(SourceFormat.FLAC, flac, 4096, 0);
    }

    @Test
    void wavAndAiffDecodeIdenticallyAndSeekByteExactly() throws Exception {
        byte[] wav = decodeAll(SourceFormat.WAV, TestAudio.resource("sweep.wav"));
        byte[] aiff = decodeAll(SourceFormat.AIFF, TestAudio.resource("sweep.aiff"));
        assertThat(wav).hasSize(SWEEP_FRAMES * 4);
        assertThat(aiff).isEqualTo(wav);
        assertSeekMatches(SourceFormat.WAV, TestAudio.resource("sweep.wav"), 12345, 0);
    }

    @Test
    void monoAnd8BitAnd24BitWavBecomeStereoSixteenBit() throws Exception {
        int rate = 22050;
        int frames = 1000;
        double[] sine = new double[frames];
        for (int i = 0; i < frames; i++) {
            sine[i] = 0.5 * Math.sin(2 * Math.PI * 440 * i / rate);
        }
        // mono 16
        Path mono = tmp.resolve("mono.wav");
        writeWav(mono, sine, rate, 1, 16);
        byte[] m = decodeAll(SourceFormat.WAV, mono);
        assertThat(m).hasSize(frames * 4);
        short[] s = TestAudio.toShorts(m);
        for (int i = 0; i < frames; i++) {
            assertThat(s[2 * i]).isEqualTo(s[2 * i + 1]);
        }
        assertThat(Math.abs(s[2 * 25] / 32768.0 - sine[25])).isLessThan(0.001);
        // 8 and 24 bit stereo
        for (int bits : new int[] {8, 24}) {
            Path p = tmp.resolve("b" + bits + ".wav");
            writeWav(p, sine, rate, 2, bits);
            byte[] d = decodeAll(SourceFormat.WAV, p);
            assertThat(d).hasSize(frames * 4);
            short[] x = TestAudio.toShorts(d);
            double tolerance = bits == 8 ? 0.02 : 0.001;
            assertThat(Math.abs(x[2 * 25] / 32768.0 - sine[25])).as(bits + " bit").isLessThan(tolerance);
        }
    }

    @Test
    void opusLengthAndSeek() throws Exception {
        Path opus = TestAudio.resource("sweep.opus");
        Transcoder.SourceInfo info = Decoders.probe(SourceFormat.OPUS, opus);
        assertThat(info.sampleRate()).isEqualTo(48000);
        assertThat(info.totalSamples()).isEqualTo(96000);
        assertThat(decodeAll(SourceFormat.OPUS, opus)).hasSize(96000 * 4);
        assertSeekMatches(SourceFormat.OPUS, opus, 50000, 40);
    }

    @Test
    void midiHasNoDecoderAndGarbageIsRejected() throws Exception {
        assertThatThrownBy(() -> Decoders.open(SourceFormat.MIDI, tmp.resolve("x.mid"))).isInstanceOf(IOException.class);
        Path junk = tmp.resolve("junk.flac");
        Files.write(junk, new byte[200]);
        assertThatThrownBy(() -> Decoders.open(SourceFormat.FLAC, junk)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Decoders.open(SourceFormat.OGG, junk)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Decoders.open(SourceFormat.WAV, junk)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Decoders.open(SourceFormat.MP3, junk)).isInstanceOf(IOException.class);
    }

    @Test
    void shortCodecOutputIsPaddedAndLongOutputCut() throws Exception {
        AudioDecoder d = new FixedLengthDecoder(10) {
            private boolean gave;

            @Override
            protected void doSeek(long frame) {
            }

            @Override
            protected int doRead(byte[] buf, int off, int len) {
                if (gave) {
                    return -1;
                }
                gave = true;
                Arrays.fill(buf, off, off + 16, (byte) 1);
                return 16;   // 4 frames, then the codec ends early
            }

            @Override
            public int sampleRate() {
                return 8000;
            }

            @Override
            public void close() {
            }
        };
        byte[] all = readAll(d);
        assertThat(all).hasSize(40);
        assertThat(all[15]).isEqualTo((byte) 1);
        assertThat(all[16]).isEqualTo((byte) 0);
    }

    @Test
    void stereoHelperReducesBitDepth() {
        byte[] out = new byte[4];
        // 24-bit little-endian 0x123456 -> 0x1234 (+ rounding), and a mono source is duplicated
        StereoPcm.toStereo16(new byte[] {0x56, 0x34, 0x12}, 0, 1, 1, 3, 24, out, 0);
        assertThat(TestAudio.toShorts(out)).containsExactly((short) 0x1234, (short) 0x1234);
        // full-scale negative stays in range
        StereoPcm.toStereo16(new byte[] {0x00, 0x00, (byte) 0x80}, 0, 1, 1, 3, 24, out, 0);
        assertThat(TestAudio.toShorts(out)[0]).isEqualTo((short) -32768);
        // positive full scale clamps instead of wrapping
        StereoPcm.toStereo16(new byte[] {(byte) 0xff, (byte) 0xff, 0x7f}, 0, 1, 1, 3, 24, out, 0);
        assertThat(TestAudio.toShorts(out)[0]).isEqualTo((short) 32767);
    }

    static void writeWav(Path file, double[] mono, int rate, int channels, int bits) throws IOException {
        int bytes = bits / 8;
        byte[] data = new byte[mono.length * channels * bytes];
        for (int i = 0; i < mono.length; i++) {
            for (int c = 0; c < channels; c++) {
                int p = (i * channels + c) * bytes;
                if (bits == 8) {
                    data[p] = (byte) Math.round(mono[i] * 127);   // 8-bit WAV is unsigned in the file
                    data[p] = (byte) (data[p] + 128);
                } else {
                    long v = Math.round(mono[i] * (bits == 16 ? 32767 : 8388607));
                    for (int b = 0; b < bytes; b++) {
                        data[p + b] = (byte) (v >> (8 * b));
                    }
                }
            }
        }
        AudioFormat f = new AudioFormat(bits == 8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED,
                rate, bits, channels, channels * bytes, rate, false);
        try (AudioInputStream ais = new AudioInputStream(new java.io.ByteArrayInputStream(data), f, mono.length)) {
            AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, file.toFile());
        }
    }
}
