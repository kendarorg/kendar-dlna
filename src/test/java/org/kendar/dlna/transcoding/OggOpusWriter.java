package org.kendar.dlna.transcoding;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only Ogg Opus muxer around the Concentus encoder (one packet per page, so every page is a seek point). */
final class OggOpusWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int serial = 0x4b454e44;
    private int seq;

    static byte[] encode(short[] pcm48kInterleaved, int channels, Map<String, String> tags, int packetsPerPage) {
        try {
            OpusEncoder enc = new OpusEncoder(48000, channels, OpusApplication.OPUS_APPLICATION_AUDIO);
            enc.setBitrate(96_000);
            int preSkip = enc.getLookahead();
            OggOpusWriter w = new OggOpusWriter();
            w.writeHeader(channels, preSkip);
            w.writeTags(tags);
            int frame = 960;
            int totalFrames = pcm48kInterleaved.length / channels;
            // pad with the pre-skip worth of trailing silence plus a frame so the decoder flushes all real audio
            int packets = (totalFrames + preSkip + frame - 1) / frame;
            short[] in = Arrays.copyOf(pcm48kInterleaved, packets * frame * channels);
            byte[] buf = new byte[4000];
            ByteArrayOutputStream page = new ByteArrayOutputStream();
            java.util.List<byte[]> pending = new java.util.ArrayList<>();
            for (int p = 0; p < packets; p++) {
                int n = enc.encode(in, p * frame * channels, frame, buf, 0, buf.length);
                pending.add(Arrays.copyOf(buf, n));
                boolean last = p == packets - 1;
                if (pending.size() == packetsPerPage || last) {
                    long granule = last ? (long) totalFrames + preSkip : (long) (p + 1) * frame;
                    w.writePage(pending, granule, last ? 4 : 0);
                    pending.clear();
                }
            }
            page.close();
            return w.out.toByteArray();
        } catch (OpusException | java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeHeader(int channels, int preSkip) {
        byte[] h = new byte[19];
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 8);
        h[8] = 1;
        h[9] = (byte) channels;
        h[10] = (byte) preSkip;
        h[11] = (byte) (preSkip >> 8);
        h[12] = (byte) 0x80; // input rate 48000 = 0xBB80
        h[13] = (byte) 0xBB;
        writePage(java.util.List.of(h), 0, 2);
    }

    private void writeTags(Map<String, String> tags) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
        byte[] vendor = "kendar-dlna spike".getBytes(StandardCharsets.UTF_8);
        le32(t, vendor.length);
        t.writeBytes(vendor);
        le32(t, tags.size());
        for (Map.Entry<String, String> e : tags.entrySet()) {
            byte[] c = (e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8);
            le32(t, c.length);
            t.writeBytes(c);
        }
        writePage(java.util.List.of(t.toByteArray()), 0, 0);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >> 8);
        o.write(v >> 16);
        o.write(v >> 24);
    }

    private void writePage(java.util.List<byte[]> packets, long granule, int type) {
        ByteArrayOutputStream lacing = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : packets) {
            int len = p.length;
            while (len >= 255) {
                lacing.write(255);
                len -= 255;
            }
            lacing.write(len);
            body.writeBytes(p);
        }
        byte[] seg = lacing.toByteArray();
        if (seg.length > 255) {
            throw new IllegalStateException("too many segments for one page");
        }
        ByteArrayOutputStream pg = new ByteArrayOutputStream();
        pg.writeBytes("OggS".getBytes(StandardCharsets.US_ASCII));
        pg.write(0);
        pg.write(type);
        for (int i = 0; i < 8; i++) {
            pg.write((int) (granule >> (8 * i)));
        }
        le32(pg, serial);
        le32(pg, seq++);
        le32(pg, 0);
        pg.write(seg.length);
        pg.writeBytes(seg);
        pg.writeBytes(body.toByteArray());
        byte[] page = pg.toByteArray();
        int crc = OggCrc.crc(page, 0, page.length);
        page[22] = (byte) crc;
        page[23] = (byte) (crc >> 8);
        page[24] = (byte) (crc >> 16);
        page[25] = (byte) (crc >> 24);
        out.writeBytes(page);
    }

    static Map<String, String> tags(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
