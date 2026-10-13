package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.util.Arrays;

/**
 * Converts another decoder to a different sample rate with a Blackman-windowed sinc low-pass (anti-aliased when the
 * rate goes down). Every output sample is computed from a fixed window of input samples, so a stream started at any
 * offset is byte-identical to the continuous one, and the length is exact:
 * {@code round(inputSamples * outRate / inRate)}. Works on stereo s16le and s24le (the frame size of the inner decoder).
 */
final class ResamplingDecoder implements AudioDecoder {

    private static final int ZEROS = 12;
    private static final int RES = 256;
    private static final int CHUNK_FRAMES = 4096;

    private final AudioDecoder inner;
    private final int inRate;
    private final int outRate;
    private final long inTotal;
    private final long total;
    private final int fb;
    private final int hw;
    private final float[] table;
    private final byte[] raw;

    private float[] left = new float[CHUNK_FRAMES * 4];
    private float[] right = new float[CHUNK_FRAMES * 4];
    private long bufStart;
    private int bufLen;
    private boolean innerEnded;
    private long outPos;

    /** Exact number of output frames for {@code samples} input frames. */
    static long outputSamples(long samples, int inRate, int outRate) {
        return (samples * outRate + inRate / 2) / inRate;
    }

    ResamplingDecoder(AudioDecoder inner, int outRate) {
        this.inner = inner;
        this.inRate = inner.sampleRate();
        this.outRate = outRate;
        this.inTotal = inner.totalSamples();
        this.total = outputSamples(inTotal, inRate, outRate);
        this.fb = inner.frameBytes();
        this.raw = new byte[CHUNK_FRAMES * fb];
        double fc = outRate >= inRate ? 1.0 : 0.97 * outRate / inRate;
        this.hw = (int) Math.ceil(ZEROS / fc);
        this.table = new float[hw * RES + 2];
        for (int i = 0; i < table.length; i++) {
            double t = i / (double) RES;
            double x = t / hw;
            double window = x >= 1 ? 0 : 0.42 + 0.5 * Math.cos(Math.PI * x) + 0.08 * Math.cos(2 * Math.PI * x);
            double s = t == 0 ? 1.0 : Math.sin(Math.PI * fc * t) / (Math.PI * fc * t);
            table[i] = (float) (fc * s * window);
        }
    }

    @Override
    public int sampleRate() {
        return outRate;
    }

    @Override
    public int frameBytes() {
        return fb;
    }

    @Override
    public long totalSamples() {
        return total;
    }

    @Override
    public void seek(long frame) throws IOException {
        outPos = Math.max(0, Math.min(frame, total));
        long center = outPos * inRate / outRate;
        long from = Math.max(0, center - hw + 1);
        inner.seek(Math.min(from, inTotal));
        bufStart = from;
        bufLen = 0;
        innerEnded = false;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (outPos >= total) {
            return -1;
        }
        int frames = (int) Math.min(len / fb, total - outPos);
        if (frames <= 0) {
            return 0;
        }
        for (int k = 0; k < frames; k++) {
            long num = outPos * inRate;
            long ci = num / outRate;
            double frac = (num % outRate) / (double) outRate;
            long lo = ci - hw + 1;
            long hi = ci + hw;
            ensure(hi);
            trim(lo);
            double sl = 0;
            double sr = 0;
            for (long j = Math.max(lo, 0); j <= hi && j < inTotal; j++) {
                double t = Math.abs(j - ci - frac);
                if (t >= hw) {
                    continue;
                }
                double pos = t * RES;
                int i = (int) pos;
                double w = table[i] + (table[i + 1] - table[i]) * (pos - i);
                int idx = (int) (j - bufStart);
                if (idx < 0 || idx >= bufLen) {
                    continue;
                }
                sl += w * left[idx];
                sr += w * right[idx];
            }
            write(buf, off + k * fb, sl, sr);
            outPos++;
        }
        return frames * fb;
    }

    private void write(byte[] out, int pos, double l, double r) {
        if (fb == 6) {
            StereoPcm.put24(out, pos, clamp(l, 8_388_607), clamp(r, 8_388_607));
        } else {
            StereoPcm.put(out, pos, clamp(l, 32_767), clamp(r, 32_767));
        }
    }

    private static int clamp(double v, int max) {
        long x = Math.round(v);
        return (int) Math.max(-max - 1L, Math.min(max, x));
    }

    /** Makes input frames up to index {@code last} available (as far as the source goes). */
    private void ensure(long last) throws IOException {
        while (!innerEnded && bufStart + bufLen <= last && bufStart + bufLen < inTotal) {
            int n = inner.read(raw, 0, raw.length);
            if (n <= 0) {
                innerEnded = true;
                break;
            }
            int frames = n / fb;
            if (bufLen + frames > left.length) {
                int size = Math.max(left.length * 2, bufLen + frames);
                left = Arrays.copyOf(left, size);
                right = Arrays.copyOf(right, size);
            }
            for (int i = 0; i < frames; i++) {
                int p = i * fb;
                if (fb == 6) {
                    left[bufLen + i] = (raw[p] & 0xff) | (raw[p + 1] & 0xff) << 8 | raw[p + 2] << 16;
                    right[bufLen + i] = (raw[p + 3] & 0xff) | (raw[p + 4] & 0xff) << 8 | raw[p + 5] << 16;
                } else {
                    left[bufLen + i] = (short) ((raw[p] & 0xff) | raw[p + 1] << 8);
                    right[bufLen + i] = (short) ((raw[p + 2] & 0xff) | raw[p + 3] << 8);
                }
            }
            bufLen += frames;
        }
    }

    /** Drops buffered input before index {@code lo} once a good part of the buffer is stale. */
    private void trim(long lo) {
        long drop = lo - bufStart;
        if (drop > CHUNK_FRAMES && drop <= bufLen) {
            int d = (int) drop;
            System.arraycopy(left, d, left, 0, bufLen - d);
            System.arraycopy(right, d, right, 0, bufLen - d);
            bufLen -= d;
            bufStart += d;
        }
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }
}
