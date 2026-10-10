package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Decoder of Apple Lossless frames (mono or stereo, 16 or 24 bit), written from the public description of the
 * format (adaptive Golomb-Rice coded residuals, adaptive FIR prediction, mid/side mixing) and the structure of
 * Apple's open-source decoder and of Peter McQuillan's BSD-licensed Java port (credited in NOTICE). It is verified
 * bit-exact against ffmpeg's decoder by the unit tests. One instance per stream; not thread-safe.
 */
final class AlacFrames {

    private static final int RICE_THRESHOLD = 8;

    private final int frameLength;
    private final int bitDepth;
    private final int historyMult;
    private final int initialHistory;
    private final int kModifier;
    private final int channels;

    private final int[][] error;
    private final int[][] extra;
    private final int[] coefs = new int[32];

    private byte[] data;
    private int bitPos;

    /** @param cookie the 24 byte ALACSpecificConfig */
    AlacFrames(byte[] cookie) throws IOException {
        ByteBuffer c = ByteBuffer.wrap(cookie);
        frameLength = c.getInt(0);
        bitDepth = cookie[5] & 0xff;
        historyMult = cookie[6] & 0xff;
        initialHistory = cookie[7] & 0xff;
        kModifier = cookie[8] & 0xff;
        channels = cookie[9] & 0xff;
        if (frameLength <= 0 || frameLength > 1 << 16) {
            throw new IOException("Bad ALAC frame length " + frameLength);
        }
        if (bitDepth != 16 && bitDepth != 24) {
            throw new UnsupportedAudioException("ALAC with " + bitDepth + " bits per sample is not supported (16 or 24)");
        }
        if (channels < 1 || channels > 2) {
            throw new UnsupportedAudioException("ALAC with " + channels + " channels is not supported (mono or stereo)");
        }
        error = new int[2][frameLength];
        extra = new int[2][frameLength];
    }

    int channels() {
        return channels;
    }

    int bitDepth() {
        return bitDepth;
    }

    int frameLength() {
        return frameLength;
    }

    /**
     * Decodes one frame.
     *
     * @param out per channel sample arrays of at least {@link #frameLength()} entries
     * @return the number of samples per channel
     */
    int decode(byte[] frame, int[][] out) throws IOException {
        data = frame;
        bitPos = 0;
        try {
            int tag = bits(3);
            if (tag > 1) {
                throw new IOException("Unsupported ALAC element " + tag);
            }
            int n = tag + 1;
            if (n > channels) {
                throw new IOException("ALAC element has more channels than the stream");
            }
            int samples = element(n, out);
            if (n < channels) {
                System.arraycopy(out[0], 0, out[1], 0, samples);
            }
            return samples;
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("Corrupt ALAC frame", e);
        }
    }

    private int element(int n, int[][] out) throws IOException {
        bits(4);    // element instance tag
        bits(12);   // unused
        boolean hasSize = bits(1) != 0;
        int uncompressedBytes = bits(2);
        boolean notCompressed = bits(1) != 0;
        int samples = frameLength;
        if (hasSize) {
            samples = bits(32);
            if (samples < 0 || samples > frameLength) {
                throw new IOException("Bad ALAC sample count " + samples);
            }
        }
        int sampleBits = bitDepth - uncompressedBytes * 8 + (n - 1);
        int shift = 0;
        int weight = 0;
        if (notCompressed) {
            for (int i = 0; i < samples; i++) {
                for (int c = 0; c < n; c++) {
                    int v = bits(bitDepth);
                    out[c][i] = bitDepth == 32 ? v : v << (32 - bitDepth) >> (32 - bitDepth);
                }
            }
            return samples;
        }
        shift = bits(8);
        weight = bits(8);
        int[] type = new int[2];
        int[] quant = new int[2];
        int[] mod = new int[2];
        int[][] table = new int[2][32];
        int[] order = new int[2];
        for (int c = 0; c < n; c++) {
            type[c] = bits(4);
            quant[c] = bits(4);
            mod[c] = bits(3);
            order[c] = bits(5);
            for (int i = 0; i < order[c]; i++) {
                table[c][i] = (short) bits(16);
            }
        }
        if (uncompressedBytes != 0) {
            for (int i = 0; i < samples; i++) {
                for (int c = 0; c < n; c++) {
                    extra[c][i] = bits(uncompressedBytes * 8);
                }
            }
        }
        for (int c = 0; c < n; c++) {
            if (type[c] != 0) {
                throw new IOException("Unsupported ALAC prediction type " + type[c]);
            }
            riceDecode(error[c], samples, sampleBits, mod[c] * (historyMult / 4));
            predict(error[c], samples, sampleBits, table[c], order[c], quant[c]);
        }
        if (n == 1) {
            for (int i = 0; i < samples; i++) {
                out[0][i] = uncompressedBytes == 0 ? error[0][i]
                        : error[0][i] << (uncompressedBytes * 8) | extra[0][i] & mask(uncompressedBytes);
            }
            return samples;
        }
        int m = mask(uncompressedBytes);
        for (int i = 0; i < samples; i++) {
            int a = error[0][i];
            int b = error[1][i];
            int left;
            int right;
            if (weight != 0) {
                right = a - ((b * weight) >> shift);
                left = right + b;
            } else {
                left = a;
                right = b;
            }
            if (uncompressedBytes != 0) {
                left = left << (uncompressedBytes * 8) | extra[0][i] & m;
                right = right << (uncompressedBytes * 8) | extra[1][i] & m;
            }
            out[0][i] = left;
            out[1][i] = right;
        }
        return samples;
    }

    private static int mask(int uncompressedBytes) {
        return uncompressedBytes == 0 ? 0 : uncompressedBytes >= 4 ? -1 : (1 << (uncompressedBytes * 8)) - 1;
    }

    // ---- entropy coding ----------------------------------------------------------------------------------------

    private void riceDecode(int[] out, int size, int sampleBits, int historyMultiplier) {
        int history = initialHistory;
        int count = 0;
        int signModifier = 0;
        int kbMask = (1 << kModifier) - 1;
        while (count < size) {
            int k = 31 - Integer.numberOfLeadingZeros((history >> 9) + 3);
            k = Math.min(k, kModifier);
            int value = decodeValue(sampleBits, k, -1);
            value += signModifier;
            int result = (value + 1) / 2;
            if ((value & 1) != 0) {
                result = -result;
            }
            out[count] = result;
            signModifier = 0;
            history += value * historyMultiplier - ((history * historyMultiplier) >> 9);
            if (value > 0xFFFF) {
                history = 0xFFFF;
            }
            if (history < 128 && count + 1 < size) {
                signModifier = 1;
                int zk = Integer.numberOfLeadingZeros(history) + ((history + 16) / 64) - 24;
                int block = decodeValue(16, zk, kbMask);
                if (block > 0) {
                    int end = Math.min(size, count + 1 + block);
                    for (int j = count + 1; j < end; j++) {
                        out[j] = 0;
                    }
                    count += block;
                }
                if (block > 0xFFFF) {
                    signModifier = 0;
                }
                history = 0;
            }
            count++;
        }
    }

    private int decodeValue(int sampleBits, int k, int rangeMask) {
        int x = 0;
        while (x <= RICE_THRESHOLD && bits(1) != 0) {
            x++;
        }
        if (x > RICE_THRESHOLD) {
            return bits(sampleBits) & (-1 >>> (32 - sampleBits));
        }
        if (k != 1) {
            int extraBits = bits(k);
            x *= ((1 << k) - 1) & rangeMask;
            if (extraBits > 1) {
                x += extraBits - 1;
            } else {
                bitPos--;
            }
        }
        return x;
    }

    /** Adaptive FIR in place (the residual array becomes the decoded samples), coefficients adapt as they go. */
    private void predict(int[] buf, int size, int sampleBits, int[] table, int order, int quant) {
        if (order == 0 || size <= 1) {
            return;
        }
        int sh = 32 - sampleBits;
        if (order == 31) {
            for (int i = 0; i < size - 1; i++) {
                buf[i + 1] = (buf[i] + buf[i + 1]) << sh >> sh;
            }
            return;
        }
        System.arraycopy(table, 0, coefs, 0, order);
        for (int i = 0; i < order && i + 1 < size; i++) {
            buf[i + 1] = (buf[i] + buf[i + 1]) << sh >> sh;
        }
        for (int i = order + 1; i < size; i++) {
            int base = i - order - 1;
            int sum = 0;
            int bb = buf[base];
            for (int j = 0; j < order; j++) {
                sum += (buf[base + order - j] - bb) * coefs[j];
            }
            int errorValue = buf[i];
            int outValue = ((1 << (quant - 1)) + sum >> quant) + bb + errorValue;
            buf[i] = outValue << sh >> sh;
            if (errorValue > 0) {
                for (int p = order - 1; p >= 0 && errorValue > 0; p--) {
                    int val = bb - buf[base + order - p];
                    int sign = Integer.signum(val);
                    coefs[p] -= sign;
                    val *= sign;
                    errorValue -= (val >> quant) * (order - p);
                }
            } else if (errorValue < 0) {
                for (int p = order - 1; p >= 0 && errorValue < 0; p--) {
                    int val = bb - buf[base + order - p];
                    int sign = -Integer.signum(val);
                    coefs[p] -= sign;
                    val *= sign;
                    errorValue -= (val >> quant) * (order - p);
                }
            }
        }
    }

    // ---- bits --------------------------------------------------------------------------------------------------

    /** Reads {@code n} (0..32) bits, MSB first; reading past the end yields zero bits. */
    private int bits(int n) {
        if (n == 0) {
            return 0;
        }
        long v = 0;
        int left = n;
        while (left > 0) {
            int byteIdx = bitPos >> 3;
            int avail = 8 - (bitPos & 7);
            int take = Math.min(avail, left);
            int b = byteIdx < data.length ? data[byteIdx] & 0xff : 0;
            v = v << take | (b >> (avail - take)) & ((1 << take) - 1);
            bitPos += take;
            left -= take;
        }
        return (int) v;
    }
}
