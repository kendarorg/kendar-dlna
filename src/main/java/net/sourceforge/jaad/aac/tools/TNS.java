package net.sourceforge.jaad.aac.tools;

import net.sourceforge.jaad.aac.AACException;
import net.sourceforge.jaad.aac.SampleRate;
import net.sourceforge.jaad.aac.syntax.BitStream;
import net.sourceforge.jaad.aac.syntax.ICSInfo;
import net.sourceforge.jaad.aac.syntax.ICStream;

/**
 * Temporal Noise Shaping.
 *
 * <p>PATCHED COPY of {@code net.sourceforge.jaad.aac.tools.TNS} from JAAD 0.9.4
 * ({@code com.tianscar.javasound:jaad}, Apache License 2.0, JAAD itself is public domain). The original
 * {@code process} method is an empty stub ("TNS unavailable"), which makes every frame that uses TNS (most
 * encoders use it on transients) decode with audible errors. The bitstream parsing is unchanged; {@link #process}
 * implements the all-pole synthesis filter of ISO/IEC 14496-3 4.6.9 as in FAAD2/ffmpeg (verified against ffmpeg's
 * decoder by {@code AacDecoderTest}). build.gradle repackages
 * the JAAD jar without the original class, so this one is the only one (see NOTICE).
 */
public class TNS implements TNSTables {

    private static final int TNS_MAX_ORDER = 20;
    private static final int[] SHORT_BITS = {1, 4, 3}, LONG_BITS = {2, 6, 5};
    /** Maximum number of scale factor bands TNS covers: {long window, short window} per sampling frequency index. */
    private static final int[][] MAX_BANDS = {{31, 9}, {31, 9}, {34, 10}, {40, 14}, {42, 14}, {51, 14}, {46, 14},
        {46, 14}, {42, 14}, {42, 14}, {42, 14}, {39, 14}, {39, 14}};

    //bitstream
    private final int[] nFilt;
    private final int[][] length;
    private final int[][] order;
    private final boolean[][] direction;
    private final float[][][] coef;

    public TNS() {
        nFilt = new int[8];
        length = new int[8][4];
        direction = new boolean[8][4];
        order = new int[8][4];
        coef = new float[8][4][TNS_MAX_ORDER];
    }

    public void decode(BitStream in, ICSInfo info) {
        final int windowCount = info.getWindowCount();
        final int[] bits = info.isEightShortFrame() ? SHORT_BITS : LONG_BITS;

        for (int w = 0; w < windowCount; w++) {
            if ((nFilt[w] = in.readBits(bits[0])) != 0) {
                int coefRes = in.readBit();

                for (int filt = 0; filt < nFilt[w]; filt++) {
                    length[w][filt] = in.readBits(bits[1]);

                    if ((order[w][filt] = in.readBits(bits[2])) > 20) {
                        throw new AACException("TNS filter out of range: " + order[w][filt]);
                    } else if (order[w][filt] != 0) {
                        direction[w][filt] = in.readBool();
                        int coefCompress = in.readBit();
                        int coefLen = coefRes + 3 - coefCompress;
                        int tmp = 2 * coefCompress + coefRes;

                        for (int i = 0; i < order[w][filt]; i++) {
                            coef[w][filt][i] = TNS_TABLES[tmp][in.readBits(coefLen)];
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies the TNS filters to the spectrum of one channel.
     *
     * @param decode false: the decoder's all-pole filter; true: the encoder's all-zero filter (only used for LTP,
     *               which this server does not decode)
     */
    public void process(ICStream ics, float[] spec, SampleRate sf, boolean decode) {
        ICSInfo info = ics.getInfo();
        boolean shortWindows = info.isEightShortFrame();
        int windows = info.getWindowCount();
        int windowLength = shortWindows ? 128 : 1024;
        int sfIndex = Math.min(sf.getNominal().getIndex(), MAX_BANDS.length - 1);
        int maxBands = MAX_BANDS[sfIndex][shortWindows ? 1 : 0];
        int maxOrder = shortWindows ? 7 : 12;
        int[] swb = info.getSWBOffsets();
        int swbCount = info.getSWBCount();
        int swbMax = info.getSWBOffsetMax();
        float[] lpc = new float[TNS_MAX_ORDER + 1];

        for (int w = 0; w < windows; w++) {
            int bottom = swbCount;
            for (int f = 0; f < nFilt[w]; f++) {
                int top = bottom;
                bottom = Math.max(top - length[w][f], 0);
                int n = Math.min(order[w][f], maxOrder);
                if (n == 0) {
                    continue;
                }
                reflectionToLpc(coef[w][f], n, lpc);
                int start = Math.min(Math.min(bottom, maxBands), info.getMaxSFB());
                start = Math.min(swb[start], swbMax);
                int end = Math.min(Math.min(top, maxBands), info.getMaxSFB());
                end = Math.min(swb[end], swbMax);
                int size = end - start;
                if (size <= 0) {
                    continue;
                }
                int base = w * windowLength;
                if (decode) {
                    movingAverage(spec, base, start, size, direction[w][f], lpc, n);
                } else {
                    autoRegressive(spec, base, start, size, direction[w][f], lpc, n);
                }
            }
        }
    }

    /** Levinson step-up recursion: reflection coefficients to the prediction filter (lpc[0] = 1). */
    private static void reflectionToLpc(float[] k, int n, float[] lpc) {
        float[] tmp = new float[TNS_MAX_ORDER + 1];
        lpc[0] = 1f;
        for (int m = 1; m <= n; m++) {
            lpc[m] = -k[m - 1];   // the tables hold the negated reflection coefficients (as in ffmpeg)
            for (int i = 1; i < m; i++) {
                tmp[i] = lpc[i] + lpc[m] * lpc[m - i];
            }
            for (int i = 1; i < m; i++) {
                lpc[i] = tmp[i];
            }
        }
    }

    private static void autoRegressive(float[] spec, int base, int start, int size, boolean down, float[] lpc,
            int n) {
        float[] state = new float[n];
        int pos = down ? base + start + size - 1 : base + start;
        int inc = down ? -1 : 1;
        for (int i = 0; i < size; i++, pos += inc) {
            float y = spec[pos];
            for (int j = 0; j < n; j++) {
                y -= lpc[j + 1] * state[j];
            }
            for (int j = n - 1; j > 0; j--) {
                state[j] = state[j - 1];
            }
            state[0] = y;
            spec[pos] = y;
        }
    }

    private static void movingAverage(float[] spec, int base, int start, int size, boolean down, float[] lpc,
            int n) {
        float[] state = new float[n];
        int pos = down ? base + start + size - 1 : base + start;
        int inc = down ? -1 : 1;
        for (int i = 0; i < size; i++, pos += inc) {
            float in = spec[pos];
            float y = in;
            for (int j = 0; j < n; j++) {
                y += lpc[j + 1] * state[j];
            }
            for (int j = n - 1; j > 0; j--) {
                state[j] = state[j - 1];
            }
            state[0] = in;
            spec[pos] = y;
        }
    }
}
