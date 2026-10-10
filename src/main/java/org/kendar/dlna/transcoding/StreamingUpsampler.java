package org.kendar.dlna.transcoding;

import java.io.ByteArrayOutputStream;

/**
 * Streaming form of {@link LowRateUpsampler}: the same integer-factor windowed-sinc filter, fed in arbitrary
 * chunks, with the same output (bit-identical to the whole-buffer version). Needs {@code HALF_WIDTH} input frames of
 * look-ahead, so output trails the input until {@link #flush()}. Interleaved stereo s16le in and out.
 */
public final class StreamingUpsampler {

    private static final int HALF_WIDTH = 16;

    private final int factor;
    private final double[] h;
    private short[] left = new short[1024];
    private short[] right = new short[1024];
    private int count;           // frames currently held
    private long base;           // global index of the first held frame
    private long next;           // next global input index to emit
    private long total;          // frames received so far

    public StreamingUpsampler(int sourceRate) {
        this.factor = LowRateUpsampler.factor(sourceRate);
        int l = factor;
        h = new double[2 * HALF_WIDTH * l + 1];
        for (int k = -HALF_WIDTH * l; k <= HALF_WIDTH * l; k++) {
            double x = (double) k / l;
            double sinc = k == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
            double w = 0.42 + 0.5 * Math.cos(Math.PI * k / (HALF_WIDTH * l + 1))
                    + 0.08 * Math.cos(2 * Math.PI * k / (HALF_WIDTH * l + 1));
            h[k + HALF_WIDTH * l] = sinc * w;
        }
    }

    public int factor() {
        return factor;
    }

    /** Feeds {@code len} bytes (whole frames) and returns the output bytes that became available. */
    public byte[] process(byte[] pcm, int off, int len) {
        int frames = len / 4;
        ensure(count + frames);
        for (int i = 0; i < frames; i++) {
            int p = off + i * 4;
            left[count] = (short) ((pcm[p] & 0xff) | (pcm[p + 1] << 8));
            right[count] = (short) ((pcm[p + 2] & 0xff) | (pcm[p + 3] << 8));
            count++;
        }
        total += frames;
        ByteArrayOutputStream out = new ByteArrayOutputStream(frames * 4 * factor);
        while (next + HALF_WIDTH < base + count) {
            emit(next++, out);
        }
        compact();
        return out.toByteArray();
    }

    /** Emits the tail (look-ahead beyond the end counts as silence); the total output is {@code frames * factor}. */
    public byte[] flush() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (next < total) {
            emit(next++, out);
        }
        compact();
        return out.toByteArray();
    }

    private void emit(long n0, ByteArrayOutputStream out) {
        for (int phase = 0; phase < factor; phase++) {
            long m = n0 * factor + phase;
            for (int ch = 0; ch < 2; ch++) {
                short[] src = ch == 0 ? left : right;
                double acc = 0;
                for (long n = n0 - HALF_WIDTH + 1; n <= n0 + HALF_WIDTH; n++) {
                    if (n < 0 || n >= total) {
                        continue;
                    }
                    long k = m - n * factor;
                    if (Math.abs(k) > HALF_WIDTH * factor) {
                        continue;
                    }
                    acc += src[(int) (n - base)] * h[(int) k + HALF_WIDTH * factor];
                }
                int v = (int) Math.max(-32768, Math.min(32767, Math.round(acc)));
                out.write(v);
                out.write(v >> 8);
            }
        }
    }

    private void ensure(int frames) {
        if (frames > left.length) {
            int cap = Math.max(frames, left.length * 2);
            left = java.util.Arrays.copyOf(left, cap);
            right = java.util.Arrays.copyOf(right, cap);
        }
    }

    /** Drops frames that no later output needs. */
    private void compact() {
        long keepFrom = Math.max(0, next - HALF_WIDTH + 1);
        int drop = (int) Math.min(count, Math.max(0, keepFrom - base));
        if (drop > 0) {
            System.arraycopy(left, drop, left, 0, count - drop);
            System.arraycopy(right, drop, right, 0, count - drop);
            count -= drop;
            base += drop;
        }
    }
}
