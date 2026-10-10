package org.kendar.dlna.transcoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Generators and measuring helpers for the transcoding spike tests. */
final class TestAudio {

    private TestAudio() {
    }

    /** Interleaved stereo s16le sine, both channels identical. */
    static byte[] sine(double freq, double amplitude, int rate, int frames) {
        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short s = (short) Math.round(amplitude * 32767 * Math.sin(2 * Math.PI * freq * i / rate));
            put(out, i, s, s);
        }
        return out;
    }

    /** Linear sweep f0..f1 over the duration. */
    static byte[] sweep(double f0, double f1, double amplitude, int rate, int frames) {
        byte[] out = new byte[frames * 4];
        double dur = (double) frames / rate;
        for (int i = 0; i < frames; i++) {
            double t = (double) i / rate;
            double ph = 2 * Math.PI * (f0 * t + (f1 - f0) / (2 * dur) * t * t);
            short s = (short) Math.round(amplitude * 32767 * Math.sin(ph));
            put(out, i, s, s);
        }
        return out;
    }

    private static void put(byte[] out, int frame, short l, short r) {
        out[frame * 4] = (byte) l;
        out[frame * 4 + 1] = (byte) (l >> 8);
        out[frame * 4 + 2] = (byte) r;
        out[frame * 4 + 3] = (byte) (r >> 8);
    }

    static short[] toShorts(byte[] pcm) {
        short[] s = new short[pcm.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
        }
        return s;
    }

    static byte[] toBytes(short[] s, int len) {
        byte[] b = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            b[2 * i] = (byte) s[i];
            b[2 * i + 1] = (byte) (s[i] >> 8);
        }
        return b;
    }

    /** Decoded MP3: interleaved samples, header facts and frame count. */
    record Mp3Pcm(short[] samples, int sampleRate, int channels, int frames) {
        int sampleFrames() {
            return samples.length / channels;
        }
    }

    /** Decodes with the JLayer low-level API. */
    static Mp3Pcm decodeMp3(byte[] mp3) {
        Bitstream bs = new Bitstream(new ByteArrayInputStream(mp3));
        Decoder dec = new Decoder();
        short[] all = new short[0];
        int n = 0;
        int frames = 0;
        int rate = 0;
        int ch = 0;
        try {
            Header h;
            while ((h = bs.readFrame()) != null) {
                SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                rate = sb.getSampleFrequency();
                ch = sb.getChannelCount();
                if (n + sb.getBufferLength() > all.length) {
                    all = Arrays.copyOf(all, Math.max(all.length * 2, n + sb.getBufferLength()));
                }
                System.arraycopy(sb.getBuffer(), 0, all, n, sb.getBufferLength());
                n += sb.getBufferLength();
                frames++;
                bs.closeFrame();
            }
        } catch (BitstreamException | DecoderException e) {
            throw new IllegalStateException("JLayer failed after " + frames + " frames: " + e.getMessage(), e);
        }
        return new Mp3Pcm(Arrays.copyOf(all, n), rate, ch, frames);
    }

    /** Left-channel samples as doubles in -1..1. */
    static double[] left(short[] interleaved, int channels, int from, int count) {
        double[] d = new double[count];
        for (int i = 0; i < count; i++) {
            d[i] = interleaved[(from + i) * channels] / 32768.0;
        }
        return d;
    }

    /** Result of a least-squares fit of a sinusoid of known frequency. */
    record ToneFit(double amplitude, double snrDb) {
    }

    static ToneFit fitTone(double[] x, double freq, int rate) {
        double sc = 0;
        double ss = 0;
        double cc = 0;
        double sx = 0;
        double cx = 0;
        double energy = 0;
        for (int i = 0; i < x.length; i++) {
            double s = Math.sin(2 * Math.PI * freq * i / rate);
            double c = Math.cos(2 * Math.PI * freq * i / rate);
            ss += s * s;
            cc += c * c;
            sc += s * c;
            sx += s * x[i];
            cx += c * x[i];
            energy += x[i] * x[i];
        }
        double det = ss * cc - sc * sc;
        double a = (sx * cc - cx * sc) / det;
        double b = (cx * ss - sx * sc) / det;
        double fitted = a * sx + b * cx;
        double resid = Math.max(energy - fitted, 1e-12);
        return new ToneFit(Math.hypot(a, b), 10 * Math.log10(fitted / resid));
    }

    /** Lag (in samples, within +-max) at which {@code b} best matches {@code a}, by normalized correlation. */
    static int bestLag(double[] a, double[] b, int max) {
        int best = 0;
        double bestV = -2;
        for (int lag = -max; lag <= max; lag++) {
            double dot = 0;
            double ea = 0;
            double eb = 0;
            for (int i = Math.max(0, -lag); i < Math.min(a.length, b.length - lag); i++) {
                dot += a[i] * b[i + lag];
                ea += a[i] * a[i];
                eb += b[i + lag] * b[i + lag];
            }
            double v = dot / Math.sqrt(ea * eb + 1e-12);
            if (v > bestV) {
                bestV = v;
                best = lag;
            }
        }
        return best;
    }

    static byte[] readAll(Path p) {
        try {
            return java.nio.file.Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path resource(String name) {
        try {
            return Path.of(TestAudio.class.getResource("/audio/" + name).toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads a PCM stream fully. */
    static byte[] drain(InputStream in) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a WAV (via Java Sound) of s16le PCM. */
    static byte[] wav(byte[] pcm, int rate, int channels) {
        AudioFormat f = new AudioFormat(rate, 16, channels, true, false);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), f,
                pcm.length / f.getFrameSize())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
