package org.kendar.dlna.transcoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds minimal MP4 files (structure only, the access units are filler) to test the demuxer decisions. */
final class Mp4Synth {

    int units = 10;
    int unitDuration = 1024;
    int lastUnitDuration = 1024;
    int rate = 44100;
    int movieTimescale = 1000;
    String sampleEntry = "mp4a";
    int objectTypeIndication = 0x40;
    byte[] asc = {0x12, 0x10};   // AAC-LC, 44.1 kHz, stereo
    byte[] alacCookie;
    /** Edit list entries {segmentDuration, mediaTime}; null = no edit list. */
    long[][] edits;
    String smpb;
    String handler = "soun";
    boolean extraVideoTrack;
    boolean fragmented;

    static byte[] box(String type, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            body.writeBytes(p);
        }
        byte[] b = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(be32(b.length + 8));
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(b);
        return out.toByteArray();
    }

    static byte[] be32(long v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    static byte[] be16(int v) {
        return new byte[] {(byte) (v >> 8), (byte) v};
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private byte[] esds() {
        byte[] dsi = concat(new byte[] {0x05, (byte) asc.length}, asc);
        byte[] dcd = concat(new byte[] {0x04, (byte) (13 + dsi.length), (byte) objectTypeIndication, 0x15, 0, 0, 0},
                be32(0), be32(0), dsi);
        byte[] es = concat(new byte[] {0x03, (byte) (3 + dcd.length), 0, 1, 0}, dcd);
        return box("esds", be32(0), es);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private byte[] entry() {
        byte[] child = alacCookie != null ? box("alac", be32(0), alacCookie) : esds();
        byte[] head = concat(new byte[6], be16(1), be16(0), be16(0), be32(0), be16(2), be16(16), be16(0), be16(0),
                be32((long) rate << 16));
        return box(sampleEntry, head, child);
    }

    private byte[] audioTrack(long mdatOffset) {
        byte[] mdhd = box("mdhd", be32(0), be32(0), be32(0), be32(rate), be32((long) units * unitDuration), be32(0));
        byte[] hdlr = box("hdlr", be32(0), be32(0), ascii(handler), new byte[13]);
        byte[] stsd = box("stsd", be32(0), be32(1), entry());
        byte[] stts;
        if (lastUnitDuration != unitDuration && units > 1) {
            stts = box("stts", be32(0), be32(2), be32(units - 1), be32(unitDuration), be32(1),
                    be32(lastUnitDuration));
        } else {
            stts = box("stts", be32(0), be32(1), be32(units), be32(unitDuration));
        }
        byte[] stsc = box("stsc", be32(0), be32(1), be32(1), be32(units), be32(1));
        byte[] stsz = box("stsz", be32(0), be32(4), be32(units));
        byte[] stco = box("stco", be32(0), be32(1), be32(mdatOffset));
        byte[] stbl = box("stbl", stsd, stts, stsc, stsz, stco);
        byte[] mdia = box("mdia", mdhd, hdlr, box("minf", stbl));
        if (edits == null) {
            return box("trak", mdia);
        }
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        e.writeBytes(be32(0));
        e.writeBytes(be32(edits.length));
        for (long[] edit : edits) {
            e.writeBytes(be32(edit[0]));
            e.writeBytes(be32(edit[1]));
            e.writeBytes(be32(0x10000));
        }
        return box("trak", box("edts", box("elst", e.toByteArray())), mdia);
    }

    byte[] build() {
        byte[] ftyp = box("ftyp", ascii("M4A "), be32(0));
        byte[] mvhd = box("mvhd", be32(0), be32(0), be32(0), be32(movieTimescale), be32(0));
        // the size of moov does not depend on the chunk offset, so build twice
        byte[] moov = moov(mvhd, 0);
        long mdatOffset = ftyp.length + moov.length + 8;
        moov = moov(mvhd, mdatOffset);
        byte[] mdat = box("mdat", new byte[units * 4]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ftyp);
        out.writeBytes(moov);
        out.writeBytes(mdat);
        if (fragmented) {
            out.writeBytes(box("moof", box("mfhd", be32(0), be32(1))));
        }
        return out.toByteArray();
    }

    private byte[] moov(byte[] mvhd, long mdatOffset) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.writeBytes(mvhd);
        m.writeBytes(audioTrack(mdatOffset));
        if (fragmented) {
            m.writeBytes(box("mvex", box("trex", be32(0), be32(1))));
        }
        if (extraVideoTrack) {
            m.writeBytes(box("trak", box("mdia", box("hdlr", be32(0), be32(0), ascii("vide"), new byte[13]))));
        }
        if (smpb != null) {
            byte[] data = box("data", be32(1), be32(0), ascii(smpb));
            byte[] item = box("----", box("mean", be32(0), ascii("com.apple.iTunes")),
                    box("name", be32(0), ascii("iTunSMPB")), data);
            m.writeBytes(box("udta", box("meta", be32(0), box("ilst", item))));
        }
        return box("moov", m.toByteArray());
    }

    Path write(Path file) throws IOException {
        Files.write(file, build());
        return file;
    }
}
