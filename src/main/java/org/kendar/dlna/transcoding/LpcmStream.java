package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.InputStream;

/**
 * Linear PCM (L16: signed 16-bit big-endian, stereo, at the source sample rate) as an {@link InputStream}: the
 * fallback for renderers that cannot play the MP3 output. The length is exactly {@code samples * 4} bytes and
 * seeking is byte exact.
 */
public final class LpcmStream extends InputStream {

    private static final int CHUNK = 16 * 1024;

    private final AudioDecoder decoder;
    private final byte[] buf = new byte[CHUNK];
    private int pos;
    private int limit;
    private long left;
    private long skip;
    private boolean closed;

    /** Exact Content-Length of the whole stream. */
    public static long length(long samples) {
        return samples * 4;
    }

    /** MIME type with the parameters L16 needs, e.g. {@code audio/L16;rate=44100;channels=2}. */
    public static String mimeType(int sampleRate) {
        return "audio/L16;rate=" + sampleRate + ";channels=2";
    }

    public LpcmStream(AudioDecoder decoder, long byteOffset) throws IOException {
        this.decoder = decoder;
        long length = length(decoder.totalSamples());
        long offset = Math.max(0, Math.min(byteOffset, length));
        long startFrame = offset / 4;
        this.skip = offset % 4;
        this.left = length - startFrame * 4;
        try {
            decoder.seek(startFrame);
        } catch (IOException | RuntimeException e) {
            decoder.close();
            throw e;
        }
    }

    private boolean fill() throws IOException {
        while (pos >= limit) {
            if (left <= 0 || closed) {
                return false;
            }
            int want = (int) Math.min(CHUNK, left);
            int n = decoder.read(buf, 0, want);
            if (n <= 0) {
                return false;
            }
            for (int i = 0; i + 1 < n; i += 2) {
                byte t = buf[i];
                buf[i] = buf[i + 1];
                buf[i + 1] = t;
            }
            left -= n;
            pos = 0;
            limit = n;
            if (skip > 0) {
                int s = (int) Math.min(skip, n);
                pos = s;
                skip -= s;
            }
        }
        return true;
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return buf[pos++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }
        int n = Math.min(len, limit - pos);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            decoder.close();
        }
    }
}
