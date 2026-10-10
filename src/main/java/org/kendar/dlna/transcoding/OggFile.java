package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Page index of a single-stream Ogg file (pages located by reading only the headers) and packet reassembly across
 * lacing and continued pages. Used by the Vorbis decoder, whose seek restarts at a page boundary.
 */
final class OggFile implements AutoCloseable {

    record Page(long pos, int flags, int serial, long granule, int segCount, int bodyLen) {
        boolean continued() {
            return (flags & 1) != 0;
        }
    }

    /** Reassembles packets from consecutive pages. */
    static final class Assembler {
        private byte[] partial = new byte[0];

        List<byte[]> feed(boolean continued, int[] lacing, byte[] body) {
            if (!continued) {
                partial = new byte[0];
            }
            List<byte[]> out = new ArrayList<>();
            int off = 0;
            for (int seg : lacing) {
                byte[] merged = Arrays.copyOf(partial, partial.length + seg);
                System.arraycopy(body, off, merged, partial.length, seg);
                partial = merged;
                off += seg;
                if (seg < 255) {
                    out.add(partial);
                    partial = new byte[0];
                }
            }
            return out;
        }
    }

    private final RandomAccessFile file;
    private final List<Page> pages = new ArrayList<>();

    OggFile(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "r");
        try {
            scan();
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    private void scan() throws IOException {
        long pos = 0;
        long len = file.length();
        byte[] hdr = new byte[27];
        while (pos + 27 <= len) {
            file.seek(pos);
            file.readFully(hdr);
            if (hdr[0] != 'O' || hdr[1] != 'g' || hdr[2] != 'g' || hdr[3] != 'S') {
                break;   // trailing garbage: keep the pages found so far
            }
            int segs = hdr[26] & 0xff;
            byte[] table = new byte[segs];
            file.readFully(table);
            int body = 0;
            for (byte b : table) {
                body += b & 0xff;
            }
            long granule = 0;
            for (int i = 7; i >= 0; i--) {
                granule = granule << 8 | (hdr[6 + i] & 0xff);
            }
            int serial = (hdr[14] & 0xff) | (hdr[15] & 0xff) << 8 | (hdr[16] & 0xff) << 16 | (hdr[17] & 0xff) << 24;
            if (pos + 27 + segs + body > len) {
                break;   // truncated last page
            }
            pages.add(new Page(pos, hdr[5] & 0xff, serial, granule, segs, body));
            pos += 27 + segs + body;
        }
        if (pages.isEmpty()) {
            throw new IOException("Not an Ogg file");
        }
    }

    List<Page> pages() {
        return pages;
    }

    int[] lacing(Page p) throws IOException {
        byte[] t = new byte[p.segCount()];
        file.seek(p.pos() + 27);
        file.readFully(t);
        int[] r = new int[t.length];
        for (int i = 0; i < t.length; i++) {
            r[i] = t[i] & 0xff;
        }
        return r;
    }

    byte[] body(Page p) throws IOException {
        byte[] b = new byte[p.bodyLen()];
        file.seek(p.pos() + 27 + p.segCount());
        file.readFully(b);
        return b;
    }

    /** Packets completed on the page by a fresh assembler (only meaningful for pages that are not continued). */
    List<byte[]> packets(Page p) throws IOException {
        return new Assembler().feed(p.continued(), lacing(p), body(p));
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
