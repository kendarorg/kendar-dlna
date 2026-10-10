package org.kendar.dlna.transcoding;

/**
 * Integer-factor windowed-sinc upsampler for sources below 32 kHz. jump3r/LAME silently drops a 320 kbps CBR request
 * to 160 kbps when the input rate is below 32 kHz, which would break the constant frame size, so such sources are
 * upsampled before they reach the encoder. Works on whole interleaved stereo s16le buffers (spike implementation;
 * a streaming variant keeps the last {@code HALF_WIDTH} input frames as state).
 */
public final class LowRateUpsampler {

    public static final int MIN_ENCODER_RATE = 32_000;
    private static final int HALF_WIDTH = 16;

    private LowRateUpsampler() {
    }

    public static int factor(int rate) {
        return rate >= MIN_ENCODER_RATE ? 1 : (MIN_ENCODER_RATE + rate - 1) / rate;
    }

    /** Returns PCM at {@code rate * factor(rate)}; the number of frames is exactly {@code frames * factor}. */
    public static byte[] upsample(byte[] pcm, int rate) {
        int l = factor(rate);
        if (l == 1) {
            return pcm;
        }
        int frames = pcm.length / 4;
        double[] h = new double[2 * HALF_WIDTH * l + 1];
        for (int k = -HALF_WIDTH * l; k <= HALF_WIDTH * l; k++) {
            double x = (double) k / l;
            double sinc = k == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
            double w = 0.42 + 0.5 * Math.cos(Math.PI * k / (HALF_WIDTH * l + 1))
                    + 0.08 * Math.cos(2 * Math.PI * k / (HALF_WIDTH * l + 1));
            h[k + HALF_WIDTH * l] = sinc * w;
        }
        byte[] out = new byte[frames * l * 4];
        for (int m = 0; m < frames * l; m++) {
            int n0 = Math.floorDiv(m, l);
            for (int ch = 0; ch < 2; ch++) {
                double acc = 0;
                for (int n = n0 - HALF_WIDTH + 1; n <= n0 + HALF_WIDTH; n++) {
                    if (n < 0 || n >= frames) {
                        continue;
                    }
                    int k = m - n * l;
                    if (Math.abs(k) > HALF_WIDTH * l) {
                        continue;
                    }
                    int i = n * 4 + ch * 2;
                    short s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                    acc += s * h[k + HALF_WIDTH * l];
                }
                int v = (int) Math.max(-32768, Math.min(32767, Math.round(acc)));
                out[m * 4 + ch * 2] = (byte) v;
                out[m * 4 + ch * 2 + 1] = (byte) (v >> 8);
            }
        }
        return out;
    }
}
