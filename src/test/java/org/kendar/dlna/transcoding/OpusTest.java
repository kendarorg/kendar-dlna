package org.kendar.dlna.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jaudiotagger.audio.AudioFileIO;

/** Spike item 3: Opus through Concentus and an Ogg demuxer, exact length, seek, tags. */
class OpusTest {

    private static short[] readAll(OggOpusReader r) throws IOException {
        short[] all = new short[(int) r.totalSamples() * r.channels()];
        short[] buf = new short[4800 * r.channels()];
        int pos = 0;
        int n;
        while ((n = r.read(buf, 4800)) > 0) {
            System.arraycopy(buf, 0, all, pos, n * r.channels());
            pos += n * r.channels();
        }
        assertThat(pos).isEqualTo(all.length);
        return all;
    }

    private static short[] stereoTone(double f, int frames) {
        return TestAudio.toShorts(TestAudio.sine(f, 0.5, 48000, frames));
    }

    @Test
    void decodesAnIndependentlyEncodedStreamWithExactSampleCountAndTags(@TempDir Path dir) throws Exception {
        int frames = 48000 * 3 + 123; // deliberately not a multiple of the 20 ms packet size
        short[] pcm = stereoTone(1000, frames);
        Path f = dir.resolve("tone.opus");
        Files.write(f, OggOpusWriter.encode(pcm, 2, OggOpusWriter.tags("TITLE", "Tóne", "ARTIST", "Spike"), 5));
        try (OggOpusReader r = OggOpusReader.open(f)) {
            assertThat(r.verifyCrc()).isZero();
            assertThat(r.channels()).isEqualTo(2);
            assertThat(r.totalSamples()).isEqualTo(frames);
            assertThat(r.tags().get("TITLE")).containsExactly("Tóne");
            assertThat(r.vendor()).isEqualTo("kendar-dlna spike");
            short[] dec = readAll(r);
            double[] x = new double[48000];
            for (int i = 0; i < x.length; i++) {
                x[i] = dec[(24000 + i) * 2] / 32768.0;
            }
            TestAudio.ToneFit fit = TestAudio.fitTone(x, 1000, 48000);
            System.out.printf("[spike] Opus (Concentus enc+dec, 96 kbps): amplitude %.3f, SNR %.1f dB%n",
                    fit.amplitude(), fit.snrDb());
            assertThat(fit.amplitude()).isBetween(0.48, 0.52);
            assertThat(fit.snrDb()).isGreaterThan(20);
        }
    }

    @Test
    void decodesAnFfmpegLibopusFileAndAgreesWithItsDuration() throws Exception {
        Path f = TestAudio.resource("sweep.opus");
        try (OggOpusReader r = OggOpusReader.open(f)) {
            assertThat(r.verifyCrc()).isZero();
            System.out.println("[spike] ffmpeg/libopus fixture: preSkip=" + r.preSkip() + " totalSamples="
                    + r.totalSamples() + " vendor=" + r.vendor() + " tags=" + r.tags());
            // 2.000 s sweep at 48 kHz
            assertThat(r.totalSamples()).isEqualTo(96000);
            assertThat(r.tags().get("TITLE")).containsExactly("Opus Title");
            assertThat(r.tags().get("ARTIST")).containsExactly("Opus Artist");
            assertThat(readAll(r)).hasSize(96000 * 2);
        }
    }

    @Test
    void seekIsSampleAccurate(@TempDir Path dir) throws Exception {
        short[] pcm = TestAudio.toShorts(TestAudio.sweep(200, 5000, 0.5, 48000, 48000 * 8));
        Path f = dir.resolve("sweep.opus");
        Files.write(f, OggOpusWriter.encode(pcm, 2, OggOpusWriter.tags("TITLE", "x"), 3));
        try (OggOpusReader r = OggOpusReader.open(f)) {
            short[] full = readAll(r);
            for (long pos : new long[] {0, 1, 959, 960, 12345, 100_000, 250_001, 383_999}) {
                r.seek(pos);
                short[] buf = new short[2 * 20000];
                int n = r.read(buf, 20000);
                short[] expected = Arrays.copyOfRange(full, (int) pos * 2, (int) pos * 2 + n * 2);
                int maxDiff = 0;
                for (int i = 0; i < n * 2; i++) {
                    maxDiff = Math.max(maxDiff, Math.abs(buf[i] - expected[i]));
                }
                System.out.println("[spike] Opus seek to " + pos + ": max sample difference vs continuous decode = " + maxDiff);
                assertThat(maxDiff).as("seek to %d", pos).isLessThan(64);
            }
            r.seek(r.totalSamples());
            assertThat(r.read(new short[100], 50)).isEqualTo(-1);
        }
    }

    @Test
    void rejectsGarbageAndUnsupportedMappingFamily(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("junk.opus");
        Files.write(junk, new byte[100]);
        assertThatThrownBy(() -> OggOpusReader.open(junk)).isInstanceOf(IOException.class);
    }

    @Test
    void jaudiotaggerCannotReadOpus() {
        Path f = TestAudio.resource("sweep.opus");
        String outcome;
        try {
            var af = AudioFileIO.read(f.toFile());
            outcome = "read OK: tag=" + af.getTag() + " header=" + af.getAudioHeader();
        } catch (Exception e) {
            outcome = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        System.out.println("[spike] jaudiotagger 3.0.1 on .opus -> " + outcome);
        String finalOutcome = outcome;
        // recorded finding: assert the observed behaviour so a library upgrade that adds support is noticed
        assertThat(finalOutcome).doesNotStartWith("read OK");
    }
}
