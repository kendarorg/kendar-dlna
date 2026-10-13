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
    private final int fb;
    private boolean closed;

    /** Exact Content-Length of the whole stream. */
    public static long length(long samples) {
        return samples * 4;
    }

    /** Exact Content-Length for {@code bits} (16 or 24) per sample. */
    public static long length(long samples, int bits) {
        return samples * (bits > 16 ? 6 : 4);
    }

    public static String mimeType(int sampleRate, int bits) {
        return "audio/L" + (bits > 16 ? 24 : 16) + ";rate=" + sampleRate + ";channels=2";
    }

    /** MIME type with the parameters L16 needs, e.g. {@code audio/L16;rate=44100;channels=2}. */
    public static String mimeType(int sampleRate) {
        return "audio/L16;rate=" + sampleRate + ";channels=2";
    }

    public LpcmStream(AudioDecoder decoder, long byteOffset) throws IOException {
        this.decoder = decoder;
        this.fb = decoder.frameBytes();
        long length = decoder.totalSamples() * fb;
        long offset = Math.max(0, Math.min(byteOffset, length));
        long startFrame = offset / fb;
        this.skip = offset % fb;
        this.left = length - startFrame * fb;
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
            int want = (int) Math.min(CHUNK / fb * fb, left);
            int n = decoder.read(buf, 0, want);
            if (n <= 0) {
                return false;
            }
            if (fb == 6) {
                for (int i = 0; i + 2 < n; i += 3) {
                    byte t = buf[i];
                    buf[i] = buf[i + 2];
                    buf[i + 2] = t;
                }
            } else {
                for (int i = 0; i + 1 < n; i += 2) {
                    byte t = buf[i];
                    buf[i] = buf[i + 1];
                    buf[i + 1] = t;
                }
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
