package org.kendar.dlna.transcoding;

import java.util.ArrayList;
import java.util.List;

/** MP3 length math and a minimal frame-header scanner (MPEG-1 Layer III only, which is all the encoder emits). */
public final class Mp3Frames {

    private static final int[] BITRATES = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] RATES = {44100, 48000, 32000};

    private Mp3Frames() {
    }

    /** Frame size at 48 kHz: 144 * bitrate / 48000, an integer for every MPEG-1 L3 bitrate (no padding slots). */
    public static int frameSize(int bitrateKbps) {
        long num = 144L * bitrateKbps * 1000;
        if (num % Mp3Encoder.OUTPUT_RATE != 0) {
            throw new IllegalArgumentException("bitrate gives fractional frame size at 48 kHz: " + bitrateKbps);
        }
        return (int) (num / Mp3Encoder.OUTPUT_RATE);
    }

    /** Number of samples at 48 kHz for a source of {@code samples} at {@code rate} (rounded to nearest). */
    public static long samplesAt48k(long samples, int rate) {
        if (rate == Mp3Encoder.OUTPUT_RATE) {
            return samples;
        }
        return (samples * Mp3Encoder.OUTPUT_RATE + rate / 2) / rate;
    }

    public static long frameCount(long samplesAt48k) {
        return (samplesAt48k + Mp3Encoder.SAMPLES_PER_FRAME - 1) / Mp3Encoder.SAMPLES_PER_FRAME;
    }

    public static long contentLength(long samplesAt48k, int bitrateKbps) {
        return frameCount(samplesAt48k) * frameSize(bitrateKbps);
    }

    /** One parsed frame. */
    public record Frame(int offset, int length, int bitrateKbps, int sampleRate, boolean padding, boolean crc,
                        int mainDataBegin, boolean xingOrInfo) {
    }

    /** Result of {@link #scan}. */
    public record Scan(List<Frame> frames, int leadingBytes, int trailingBytes, boolean id3v2) {
        public boolean allSameLength() {
            return frames.stream().mapToInt(Frame::length).distinct().count() <= 1;
        }
    }

    /** Walks the stream strictly frame by frame from offset 0 (an ID3v2 tag is skipped but reported). */
    public static Scan scan(byte[] data) {
        int pos = 0;
        boolean id3 = false;
        if (data.length >= 10 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            id3 = true;
            pos = 10 + ((data[6] & 0x7f) << 21 | (data[7] & 0x7f) << 14 | (data[8] & 0x7f) << 7 | (data[9] & 0x7f));
        }
        int start = pos;
        List<Frame> frames = new ArrayList<>();
        while (pos + 4 <= data.length) {
            int h = (data[pos] & 0xff) << 24 | (data[pos + 1] & 0xff) << 16 | (data[pos + 2] & 0xff) << 8
                    | (data[pos + 3] & 0xff);
            if ((h & 0xFFE00000) != 0xFFE00000) {
                break;
            }
            int version = (h >> 19) & 3;
            int layer = (h >> 17) & 3;
            int brIdx = (h >> 12) & 15;
            int srIdx = (h >> 10) & 3;
            if (version != 3 || layer != 1 || BITRATES[brIdx] == 0 || srIdx == 3) {
                break;
            }
            int sr = RATES[srIdx];
            boolean pad = ((h >> 9) & 1) != 0;
            boolean crc = ((h >> 16) & 1) == 0;
            int len = 144 * BITRATES[brIdx] * 1000 / sr + (pad ? 1 : 0);
            if (pos + len > data.length) {
                break;
            }
            int sideOff = pos + 4 + (crc ? 2 : 0);
            int mdb = ((data[sideOff] & 0xff) << 1) | ((data[sideOff + 1] & 0xff) >> 7);
            boolean stereo = ((h >> 6) & 3) != 3;
            int tagOff = pos + 4 + (stereo ? 32 : 17);
            boolean xing = tagOff + 4 <= data.length && ((data[tagOff] == 'X' && data[tagOff + 1] == 'i'
                    && data[tagOff + 2] == 'n' && data[tagOff + 3] == 'g')
                    || (data[tagOff] == 'I' && data[tagOff + 1] == 'n' && data[tagOff + 2] == 'f'
                    && data[tagOff + 3] == 'o'));
            frames.add(new Frame(pos, len, BITRATES[brIdx], sr, pad, crc, mdb, xing));
            pos += len;
        }
        return new Scan(frames, start, data.length - pos, id3);
    }
}
