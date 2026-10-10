package org.kendar.dlna.transcoding;

/** Small PCM conversion helpers shared by the decoders. */
final class StereoPcm {

    private StereoPcm() {
    }

    /** Writes one stereo frame (little-endian) at {@code pos}. */
    static void put(byte[] out, int pos, int left, int right) {
        out[pos] = (byte) left;
        out[pos + 1] = (byte) (left >> 8);
        out[pos + 2] = (byte) right;
        out[pos + 3] = (byte) (right >> 8);
    }

    static int clamp16(int v) {
        return v > 32767 ? 32767 : Math.max(v, -32768);
    }

    /** Reduces a signed sample of {@code bits} significant bits (right-aligned) to 16 bits with rounding. */
    static int to16(int v, int bits) {
        if (bits == 16) {
            return v;
        }
        if (bits < 16) {
            return v << (16 - bits);
        }
        int shift = bits - 16;
        return clamp16((int) (((long) v + (1L << (shift - 1))) >> shift));
    }

    /**
     * Converts {@code frames} frames of interleaved little-endian PCM with {@code channels} channels of
     * {@code bytesPerSample} bytes each (signed, {@code bits} significant bits) to stereo s16le.
     *
     * @return the number of bytes written to {@code out} ({@code frames * 4})
     */
    static int toStereo16(byte[] in, int inOff, int frames, int channels, int bytesPerSample, int bits, byte[] out,
            int outOff) {
        int frameBytes = channels * bytesPerSample;
        for (int f = 0; f < frames; f++) {
            int base = inOff + f * frameBytes;
            int l = sample(in, base, bytesPerSample, bits);
            int r = channels > 1 ? sample(in, base + bytesPerSample, bytesPerSample, bits) : l;
            put(out, outOff + f * 4, l, r);
        }
        return frames * 4;
    }

    private static int sample(byte[] b, int pos, int bytes, int bits) {
        int v = switch (bytes) {
            case 1 -> b[pos];
            case 2 -> (b[pos] & 0xff) | (b[pos + 1] << 8);
            case 3 -> (b[pos] & 0xff) | (b[pos + 1] & 0xff) << 8 | b[pos + 2] << 16;
            default -> (b[pos] & 0xff) | (b[pos + 1] & 0xff) << 8 | (b[pos + 2] & 0xff) << 16 | b[pos + 3] << 24;
        };
        return to16(v, bytes == 4 ? 32 : bits);
    }
}
