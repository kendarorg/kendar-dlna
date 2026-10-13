package org.kendar.dlna.transcoding;

import java.io.IOException;

/** Presents a stereo s16le decoder as stereo s24le (6-byte frames): the extra low byte is zero. */
final class PadTo24Decoder implements AudioDecoder {

    private final AudioDecoder inner;
    private byte[] tmp = new byte[0];

    PadTo24Decoder(AudioDecoder inner) {
        this.inner = inner;
    }

    @Override
    public int sampleRate() {
        return inner.sampleRate();
    }

    @Override
    public int frameBytes() {
        return 6;
    }

    @Override
    public long totalSamples() {
        return inner.totalSamples();
    }

    @Override
    public void seek(long frame) throws IOException {
        inner.seek(frame);
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int frames = len / 6;
        if (frames <= 0) {
            return 0;
        }
        if (tmp.length < frames * 4) {
            tmp = new byte[frames * 4];
        }
        int n = inner.read(tmp, 0, frames * 4);
        if (n < 0) {
            return -1;
        }
        int got = n / 4;
        for (int i = 0; i < got; i++) {
            int s = i * 4;
            int d = off + i * 6;
            buf[d] = 0;
            buf[d + 1] = tmp[s];
            buf[d + 2] = tmp[s + 1];
            buf[d + 3] = 0;
            buf[d + 4] = tmp[s + 2];
            buf[d + 5] = tmp[s + 3];
        }
        return got * 6;
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }
}
