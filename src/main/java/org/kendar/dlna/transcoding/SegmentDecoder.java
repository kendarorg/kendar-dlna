package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.util.Arrays;

/**
 * Exposes the sample range {@code [startSample, endSample)} of another decoder as a decoder of its own: position 0
 * is {@code startSample} of the inner one, {@link #totalSamples()} is {@code endSample - startSample}, and exactly
 * that many frames are delivered from every position (silence pads a source that ends early). Used for the virtual
 * tracks of cue sheets and chapters, whose advertised length must be exact.
 */
public final class SegmentDecoder implements AudioDecoder {

    private final AudioDecoder inner;
    private final long start;
    private final long total;
    private long position;

    public SegmentDecoder(AudioDecoder inner, long startSample, long endSample) throws IOException {
        this.inner = inner;
        this.start = Math.max(0, startSample);
        this.total = Math.max(0, endSample - this.start);
        inner.seek(this.start);
    }

    @Override
    public int sampleRate() {
        return inner.sampleRate();
    }

    @Override
    public int frameBytes() {
        return inner.frameBytes();
    }

    @Override
    public long totalSamples() {
        return total;
    }

    @Override
    public void seek(long frame) throws IOException {
        position = Math.max(0, Math.min(frame, total));
        inner.seek(start + position);
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        long left = total - position;
        if (left <= 0) {
            return -1;
        }
        int fb = inner.frameBytes();
        int want = (int) Math.min(len / fb * fb, left * fb);
        if (want <= 0) {
            return 0;
        }
        int n = inner.read(buf, off, want);
        if (n < 0) {
            n = 0;
        }
        n -= n % fb;
        if (n == 0) {
            Arrays.fill(buf, off, off + want, (byte) 0);   // the source ended early: pad with silence
            n = want;
        }
        position += n / fb;
        return n;
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }
}
