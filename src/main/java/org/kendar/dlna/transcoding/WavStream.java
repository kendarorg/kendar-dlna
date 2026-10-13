package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A WAV file (RIFF, PCM, stereo, little-endian, 16 or 24 bit) as an {@link InputStream}: a 44-byte header and the
 * samples at the source rate. The length is exactly {@code 44 + samples * frameBytes} and seeking is byte exact.
 */
public final class WavStream extends InputStream {

    private static final int HEADER = 44;
    private static final int CHUNK = 16 * 1024;

    private final AudioDecoder decoder;
    private final byte[] header;
    private final byte[] buf = new byte[CHUNK];
    private final int fb;
    private int headerPos;
    private int pos;
    private int limit;
    private long left;
    private long skip;
    private boolean closed;

    /** Exact Content-Length of the whole stream. */
    public static long length(long samples, int bits) {
        return HEADER + samples * (bits > 16 ? 6 : 4);
    }

    public static String mimeType() {
        return "audio/wav";
    }

    public WavStream(AudioDecoder decoder, long byteOffset) throws IOException {
        this.decoder = decoder;
        this.fb = decoder.frameBytes();
        long data = decoder.totalSamples() * fb;
        this.header = header(decoder.sampleRate(), fb == 6 ? 24 : 16, data);
        long total = HEADER + data;
        long offset = Math.max(0, Math.min(byteOffset, total));
        long dataOffset = Math.max(0, offset - HEADER);
        this.headerPos = (int) Math.min(offset, HEADER);
        long startFrame = dataOffset / fb;
        this.skip = dataOffset % fb;
        this.left = data - startFrame * fb;
        try {
            decoder.seek(startFrame);
        } catch (IOException | RuntimeException e) {
            decoder.close();
            throw e;
        }
    }

    static byte[] header(int rate, int bits, long dataBytes) {
        int blockAlign = 2 * bits / 8;
        ByteBuffer b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R', 'I', 'F', 'F'});
        b.putInt((int) Math.min(0xFFFFFFFFL, 36 + dataBytes));
        b.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 2);
        b.putInt(rate);
        b.putInt(rate * blockAlign);
        b.putShort((short) blockAlign);
        b.putShort((short) bits);
        b.put(new byte[] {'d', 'a', 't', 'a'});
        b.putInt((int) Math.min(0xFFFFFFFFL, dataBytes));
        return b.array();
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
        if (headerPos < HEADER) {
            return header[headerPos++] & 0xff;
        }
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
        if (headerPos < HEADER) {
            int n = Math.min(len, HEADER - headerPos);
            System.arraycopy(header, headerPos, b, off, n);
            headerPos += n;
            return n;
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
