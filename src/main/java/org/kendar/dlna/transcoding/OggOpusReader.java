package org.kendar.dlna.transcoding;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ogg Opus demuxer plus Concentus decoder (RFC 7845, single logical stream, channel mapping family 0).
 * Output is always 48 kHz interleaved 16-bit. The exact length is the final page granule minus the pre-skip.
 * Seeking is position accurate: decoding restarts at a page at least 200 ms earlier and the surplus is discarded
 * (Concentus converges to within ~20 LSB after 200 ms, to bit-exact after about 1 s).
 * Not thread-safe.
 */
public final class OggOpusReader implements AutoCloseable {

    public static final int RATE = 48_000;
    private static final int DEFAULT_PREROLL = 9600;
    private int preroll = DEFAULT_PREROLL;
    private static final int MAX_FRAME = 5760;

    private record Page(long pos, int flags, long granule, int bodyLen, int segCount) {
        boolean continued() {
            return (flags & 1) != 0;
        }
    }

    private final RandomAccessFile file;
    private final List<Page> pages = new ArrayList<>();
    private final int channels;
    private final int preSkip;
    private final String vendor;
    private final Map<String, List<String>> tags;
    private final long totalSamples;
    private int firstAudioPage;

    private OpusDecoder decoder;
    private int nextPage;
    private byte[] partial = new byte[0];
    private final Deque<byte[]> packets = new ArrayDeque<>();
    private long skip;
    private long remaining;
    private short[] pending = new short[0];
    private int pendingPos;

    public static OggOpusReader open(Path path) throws IOException {
        return new OggOpusReader(path);
    }

    private OggOpusReader(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "r");
        try {
            scanPages();
            if (pages.size() < 3) {
                throw new IOException("Not an Ogg Opus stream: too few pages");
            }
            List<byte[]> head = new ArrayList<>();
            int idx = 0;
            byte[] carry = new byte[0];
            // header packets: OpusHead (page 0) and OpusTags (one or more pages)
            while (head.size() < 2 && idx < pages.size()) {
                byte[] body = readBody(pages.get(idx));
                int[] lacing = readLacing(pages.get(idx));
                int p = 0;
                for (int seg : lacing) {
                    carry = concat(carry, Arrays.copyOfRange(body, p, p + seg));
                    p += seg;
                    if (seg < 255) {
                        head.add(carry);
                        carry = new byte[0];
                    }
                }
                idx++;
            }
            byte[] h = head.get(0);
            if (h.length < 19 || !"OpusHead".equals(new String(h, 0, 8, StandardCharsets.US_ASCII))) {
                throw new IOException("Not an Ogg Opus stream: no OpusHead");
            }
            if ((h[8] & 0xf0) != 0) {
                throw new IOException("Unsupported OpusHead version " + h[8]);
            }
            if (h[18] != 0) {
                throw new IOException("Unsupported Opus channel mapping family " + h[18]);
            }
            channels = h[9] & 0xff;
            preSkip = (h[10] & 0xff) | (h[11] & 0xff) << 8;
            byte[] t = head.get(1);
            Map<String, List<String>> parsed = new LinkedHashMap<>();
            String v = "";
            if (t.length >= 16 && "OpusTags".equals(new String(t, 0, 8, StandardCharsets.US_ASCII))) {
                int pos = 8;
                int vl = le32(t, pos);
                pos += 4;
                v = new String(t, pos, vl, StandardCharsets.UTF_8);
                pos += vl;
                int n = le32(t, pos);
                pos += 4;
                for (int i = 0; i < n && pos + 4 <= t.length; i++) {
                    int len = le32(t, pos);
                    pos += 4;
                    String c = new String(t, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    int eq = c.indexOf('=');
                    if (eq > 0) {
                        parsed.computeIfAbsent(c.substring(0, eq).toUpperCase(java.util.Locale.ROOT),
                                k -> new ArrayList<>()).add(c.substring(eq + 1));
                    }
                }
            }
            vendor = v;
            tags = parsed;
            firstAudioPage = idx;
            long last = -1;
            for (int i = pages.size() - 1; i >= 0; i--) {
                if (pages.get(i).granule() >= 0) {
                    last = pages.get(i).granule();
                    break;
                }
            }
            totalSamples = Math.max(0, last - preSkip);
            decoder = new OpusDecoder(RATE, channels);
            seek(0);
        } catch (IOException | RuntimeException | OpusException e) {
            file.close();
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("Cannot open Opus stream: " + e.getMessage(), e);
        }
    }

    private void scanPages() throws IOException {
        long pos = 0;
        long len = file.length();
        byte[] hdr = new byte[27];
        while (pos + 27 <= len) {
            file.seek(pos);
            file.readFully(hdr);
            if (hdr[0] != 'O' || hdr[1] != 'g' || hdr[2] != 'g' || hdr[3] != 'S') {
                throw new IOException("Ogg capture pattern lost at " + pos);
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
            pages.add(new Page(pos, hdr[5] & 0xff, granule, body, segs));
            pos += 27 + segs + body;
        }
    }

    private byte[] readBody(Page p) throws IOException {
        byte[] b = new byte[p.bodyLen()];
        file.seek(p.pos() + 27 + p.segCount());
        file.readFully(b);
        return b;
    }

    private int[] readLacing(Page p) throws IOException {
        byte[] t = new byte[p.segCount()];
        file.seek(p.pos() + 27);
        file.readFully(t);
        int[] r = new int[t.length];
        for (int i = 0; i < t.length; i++) {
            r[i] = t[i] & 0xff;
        }
        return r;
    }

    /** Pre-roll in samples decoded and discarded before a seek target (default 200 ms). */
    public void setPreroll(int samples) {
        this.preroll = samples;
    }

    /** Verifies the CRC of every page; returns the number of corrupt pages. */
    public int verifyCrc() throws IOException {
        int bad = 0;
        for (Page p : pages) {
            int total = 27 + p.segCount() + p.bodyLen();
            byte[] all = new byte[total];
            file.seek(p.pos());
            file.readFully(all);
            int stored = le32(all, 22);
            all[22] = all[23] = all[24] = all[25] = 0;
            if (OggCrc.crc(all, 0, total) != stored) {
                bad++;
            }
        }
        return bad;
    }

    public int channels() {
        return channels;
    }

    public int preSkip() {
        return preSkip;
    }

    /** Exact number of 48 kHz sample frames the decoder yields from position 0. */
    public long totalSamples() {
        return totalSamples;
    }

    public String vendor() {
        return vendor;
    }

    /** Vorbis-comment style tags, keys upper-cased. */
    public Map<String, List<String>> tags() {
        return tags;
    }

    /** Positions on the stream timeline (0 = first audible sample). */
    public void seek(long sample) throws IOException {
        long target = Math.max(0, Math.min(sample, totalSamples));
        long abs = target + preSkip;
        long startPos = 0;
        int start = firstAudioPage;
        for (int i = firstAudioPage; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.granule() < 0) {
                continue;
            }
            // pages after i must start a fresh packet, so the packets of i + 1 begin exactly at granule(i)
            boolean nextFresh = i + 1 < pages.size() && !pages.get(i + 1).continued();
            if (p.granule() <= abs - preroll && nextFresh) {
                start = i + 1;
                startPos = p.granule();
            } else if (p.granule() > abs - preroll) {
                break;
            }
        }
        decoder.resetState();
        nextPage = start;
        partial = new byte[0];
        packets.clear();
        pending = new short[0];
        pendingPos = 0;
        // frames to discard: everything between startPos and the target, including pre-skip at stream start
        skip = abs - startPos;
        remaining = totalSamples - target;
    }

    /**
     * Reads up to {@code frames} sample frames (interleaved) into {@code dst}; returns the number read or -1 at the
     * end of the stream.
     */
    public int read(short[] dst, int frames) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int want = (int) Math.min(frames, remaining);
        int got = 0;
        while (got < want) {
            if (pendingPos >= pending.length) {
                if (!decodeNext()) {
                    break;
                }
                continue;
            }
            int avail = (pending.length - pendingPos) / channels;
            int n = Math.min(avail, want - got);
            System.arraycopy(pending, pendingPos, dst, got * channels, n * channels);
            pendingPos += n * channels;
            got += n;
        }
        remaining -= got;
        return got == 0 ? -1 : got;
    }

    private boolean decodeNext() throws IOException {
        while (packets.isEmpty()) {
            if (nextPage >= pages.size()) {
                return false;
            }
            loadPage(pages.get(nextPage++));
        }
        byte[] pkt = packets.poll();
        short[] out = new short[MAX_FRAME * channels];
        int n;
        try {
            n = decoder.decode(pkt, 0, pkt.length, out, 0, MAX_FRAME, false);
        } catch (OpusException e) {
            throw new IOException("Opus decode error: " + e.getMessage(), e);
        }
        int from = 0;
        if (skip > 0) {
            int s = (int) Math.min(skip, n);
            skip -= s;
            from = s;
        }
        pending = Arrays.copyOfRange(out, from * channels, n * channels);
        pendingPos = 0;
        return true;
    }

    private void loadPage(Page p) throws IOException {
        byte[] body = readBody(p);
        int[] lacing = readLacing(p);
        if (!p.continued()) {
            partial = new byte[0];
        }
        int off = 0;
        for (int seg : lacing) {
            partial = concat(partial, Arrays.copyOfRange(body, off, off + seg));
            off += seg;
            if (seg < 255) {
                if (partial.length > 0) {
                    packets.add(partial);
                }
                partial = new byte[0];
            }
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        if (a.length == 0) {
            return b;
        }
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
