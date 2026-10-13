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

    /** Writes one stereo frame of 24-bit samples (little-endian, 6 bytes) at {@code pos}. */
    static void put24(byte[] out, int pos, int left, int right) {
        out[pos] = (byte) left;
        out[pos + 1] = (byte) (left >> 8);
        out[pos + 2] = (byte) (left >> 16);
        out[pos + 3] = (byte) right;
        out[pos + 4] = (byte) (right >> 8);
        out[pos + 5] = (byte) (right >> 16);
    }

    /** A signed sample of {@code bits} significant bits scaled to 24 bits (reduced with rounding when wider). */
    static int to24(int v, int bits) {
        if (bits == 24) {
            return v;
        }
        if (bits < 24) {
            return v << (24 - bits);
        }
        int shift = bits - 24;
        long r = ((long) v + (1L << (shift - 1))) >> shift;
        return (int) Math.max(-8_388_608L, Math.min(8_388_607L, r));
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

    /** Like {@link #toStereo16} but to stereo s24le (6 bytes per frame). */
    static int toStereo24(byte[] in, int inOff, int frames, int channels, int bytesPerSample, int bits, byte[] out,
            int outOff) {
        int frameBytes = channels * bytesPerSample;
        for (int f = 0; f < frames; f++) {
            int base = inOff + f * frameBytes;
            int l = raw(in, base, bytesPerSample);
            int r = channels > 1 ? raw(in, base + bytesPerSample, bytesPerSample) : l;
            int b = bytesPerSample == 4 ? 32 : bits;
            put24(out, outOff + f * 6, to24(l, b), to24(r, b));
        }
        return frames * 6;
    }

    private static int sample(byte[] b, int pos, int bytes, int bits) {
        return to16(raw(b, pos, bytes), bytes == 4 ? 32 : bits);
    }

    private static int raw(byte[] b, int pos, int bytes) {
        return switch (bytes) {
            case 1 -> b[pos];
            case 2 -> (b[pos] & 0xff) | (b[pos + 1] << 8);
            case 3 -> (b[pos] & 0xff) | (b[pos + 1] & 0xff) << 8 | b[pos + 2] << 16;
            default -> (b[pos] & 0xff) | (b[pos + 1] & 0xff) << 8 | (b[pos + 2] & 0xff) << 16 | b[pos + 3] << 24;
        };
    }
}
