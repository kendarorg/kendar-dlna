package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.util.Arrays;

/**
 * Base of the decoders: enforces the contract of {@link AudioDecoder} that exactly {@code totalSamples} frames are
 * delivered from every position (silence pads a short stream, surplus is cut), which keeps the advertised length
 * exact even when a codec's own count is off by a block.
 */
abstract class FixedLengthDecoder implements AudioDecoder {

    private final long total;
    private final int frameBytes;
    private long position;

    FixedLengthDecoder(long totalSamples) {
        this(totalSamples, 4);
    }

    /** @param frameBytes 4 (stereo s16le) or 6 (stereo s24le) */
    FixedLengthDecoder(long totalSamples, int frameBytes) {
        this.total = totalSamples;
        this.frameBytes = frameBytes;
    }

    @Override
    public final int frameBytes() {
        return frameBytes;
    }

    /** Positions the underlying codec at {@code frame} (sample accurate). */
    protected abstract void doSeek(long frame) throws IOException;

    /** Reads stereo s16le from the codec; -1 at its end. May return fewer bytes than asked. */
    protected abstract int doRead(byte[] buf, int off, int len) throws IOException;

    @Override
    public final long totalSamples() {
        return total;
    }

    @Override
    public final void seek(long frame) throws IOException {
        position = Math.max(0, Math.min(frame, total));
        doSeek(position);
    }

    @Override
    public final int read(byte[] buf, int off, int len) throws IOException {
        long left = total - position;
        if (left <= 0) {
            return -1;
        }
        int want = (int) Math.min(len / frameBytes * frameBytes, left * frameBytes);
        if (want <= 0) {
            return 0;
        }
        int n = doRead(buf, off, want);
        if (n < 0) {
            n = 0;
        }
        n -= n % frameBytes;
        if (n == 0) {
            Arrays.fill(buf, off, off + want, (byte) 0);   // codec ended early: pad with silence
            n = want;
        }
        position += n / frameBytes;
        return n;
    }
}
