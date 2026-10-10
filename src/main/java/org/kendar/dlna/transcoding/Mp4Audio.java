package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ISO base media (MP4/M4A) demuxer for the first audio track: codec (AAC-LC or ALAC), decoder
 * configuration, sample table (offset, size, decode time of every access unit) and the gapless window.
 *
 * <p>The window (how many decoded samples are dropped at the start and how many are played) comes from, in this
 * order: a single edit list entry ({@code elst}, what ffmpeg and iTunes write), the iTunes {@code iTunSMPB}
 * comment (priming, padding, valid samples), else the whole decode time ({@code stts}) with nothing skipped.
 * Fragmented files, files with a video track, DRM'd tracks and other codecs are refused with a readable message
 * ({@link UnsupportedAudioException}).
 */
final class Mp4Audio {

    enum Codec {
        AAC, ALAC
    }

    private static final int MAX_MOOV = 256 << 20;

    final Path file;
    Codec codec;
    int sampleRate;
    int channels;
    int bitsPerSample;
    /** AudioSpecificConfig (AAC) or the 24 byte ALACSpecificConfig (ALAC). */
    byte[] config;
    /** AAC audio object type from the AudioSpecificConfig (2 = LC); 0 for ALAC. */
    int objectType;
    /** AAC channel configuration from the AudioSpecificConfig (1..7); 0 for ALAC. */
    int channelConfig;
    /** Offset and size of each access unit, and its decode time in samples (length count + 1). */
    long[] offsets = new long[0];
    int[] sizes = new int[0];
    long[] starts = new long[1];
    /** Decoded samples to drop at the start, and samples played after that. */
    long skip;
    long length;
    /** Whether {@link #skip} / {@link #length} came from an edit list or iTunSMPB (else they are the full stream). */
    boolean gapless;

    private Mp4Audio(Path file) {
        this.file = file;
    }

    int count() {
        return sizes.length;
    }

    /** Total decoded samples of all access units. */
    long decodedSamples() {
        return starts[starts.length - 1];
    }

    byte[] read(RandomAccessFile raf, int index) throws IOException {
        byte[] b = new byte[sizes[index]];
        raf.seek(offsets[index]);
        raf.readFully(b);
        return b;
    }

    static Mp4Audio open(Path file) throws IOException {
        Mp4Audio m = new Mp4Audio(file);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] moov = findMoov(raf);
            m.parseMoov(ByteBuffer.wrap(moov));
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException | NegativeArraySizeException e) {
            throw new IOException("Corrupt MP4 structure: " + file.getFileName(), e);
        }
        return m;
    }


    // ---- ADTS --------------------------------------------------------------------------------------------------

    /** Opens an MP4/M4A or a raw ADTS AAC stream (the first two bytes decide). */
    static Mp4Audio openAny(Path file) throws IOException {
        byte[] head = new byte[2];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.read(head) < 2) {
                throw new IOException("File too short: " + file.getFileName());
            }
        }
        boolean adts = (head[0] & 0xff) == 0xff && (head[1] & 0xf0) == 0xf0 && (head[1] & 0x06) == 0;
        boolean id3 = head[0] == 'I' && head[1] == 'D';
        return adts || id3 ? openAdts(file) : open(file);
    }

    /**
     * Scans the whole ADTS stream (header walk, a few thousand tiny reads) for the access units. An ID3v2 tag in
     * front is skipped, trailing junk (ID3v1, APE) ends the stream. Every frame must carry one raw data block of
     * AAC-LC with the same sample rate and channel configuration.
     */
    static Mp4Audio openAdts(Path file) throws IOException {
        Mp4Audio m = new Mp4Audio(file);
        long[] off = new long[1024];
        int[] sz = new int[1024];
        int n = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            long pos = 0;
            byte[] h = new byte[10];
            raf.readFully(h, 0, 3);
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
                raf.seek(3);
                raf.readFully(h, 0, 7);
                pos = 10 + ((h[3] & 0x7f) << 21 | (h[4] & 0x7f) << 14 | (h[5] & 0x7f) << 7 | (h[6] & 0x7f));
            }
            int sfi = -1;
            int cc = -1;
            byte[] buf = new byte[1 << 16];
            int bufLen = 0;
            long bufPos = -1;
            while (pos + 7 <= len) {
                if (bufPos < 0 || pos < bufPos || pos + 9 > bufPos + bufLen) {
                    raf.seek(pos);
                    bufLen = raf.read(buf);
                    bufPos = pos;
                }
                int at = (int) (pos - bufPos);
                int b1 = buf[at + 1] & 0xff;
                if ((buf[at] & 0xff) != 0xff || (b1 & 0xf6) != 0xf0) {
                    break;
                }
                boolean protectionAbsent = (b1 & 1) == 1;
                int profile = (buf[at + 2] & 0xc0) >> 6;
                int f = (buf[at + 2] & 0x3c) >> 2;
                int c = (buf[at + 2] & 1) << 2 | (buf[at + 3] & 0xc0) >> 6;
                int frameLength = (buf[at + 3] & 3) << 11 | (buf[at + 4] & 0xff) << 3 | (buf[at + 5] & 0xe0) >> 5;
                int blocks = buf[at + 6] & 3;
                int header = protectionAbsent ? 7 : 9;
                if (frameLength < header || pos + frameLength > len) {
                    break;
                }
                if (n == 0) {
                    if (profile != 1) {
                        throw new UnsupportedAudioException("AAC profile " + aacProfileName(profile + 1) + " is not supported, only AAC-LC");
                    }
                    if (f >= AAC_RATES.length) {
                        throw new IOException("Bad ADTS sample rate index");
                    }
                    if (c < 1 || c > 7) {
                        throw new UnsupportedAudioException(c == 0 ? "AAC channel layout defined in the stream is not supported"
                                : "unsupported AAC channel configuration " + c);
                    }
                    sfi = f;
                    cc = c;
                }
                if (f != sfi || c != cc || profile != 1) {
                    throw new UnsupportedAudioException("ADTS stream changes its audio parameters");
                }
                if (blocks != 0) {
                    throw new UnsupportedAudioException("ADTS frames with several raw data blocks");
                }
                if (n == off.length) {
                    off = java.util.Arrays.copyOf(off, n * 2);
                    sz = java.util.Arrays.copyOf(sz, n * 2);
                }
                off[n] = pos + header;
                sz[n] = frameLength - header;
                n++;
                pos += frameLength;
            }
            if (n == 0) {
                throw new IOException("No ADTS frames found");
            }
            m.codec = Codec.AAC;
            m.objectType = 2;
            m.channelConfig = cc;
            m.channels = cc == 7 ? 8 : cc;
            m.sampleRate = AAC_RATES[sfi];
            // AudioSpecificConfig: object type 2 (5 bits), sampling frequency index (4), channel configuration (4)
            int asc = 2 << 11 | sfi << 7 | cc << 3;
            m.config = new byte[] {(byte) (asc >> 8), (byte) asc};
        }
        m.offsets = java.util.Arrays.copyOf(off, n);
        m.sizes = java.util.Arrays.copyOf(sz, n);
        m.starts = new long[n + 1];
        for (int i = 0; i < n; i++) {
            m.starts[i + 1] = m.starts[i] + 1024;
        }
        m.skip = 0;
        m.length = m.starts[n];
        return m;
    }
    // ---- boxes -------------------------------------------------------------------------------------------------

    private static byte[] findMoov(RandomAccessFile raf) throws IOException {
        long pos = 0;
        long len = raf.length();
        byte[] hdr = new byte[16];
        boolean sawFtyp = false;
        while (pos + 8 <= len) {
            raf.seek(pos);
            raf.readFully(hdr, 0, 8);
            long size = ByteBuffer.wrap(hdr).getInt() & 0xffffffffL;
            String type = new String(hdr, 4, 4, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1) {
                raf.readFully(hdr, 8, 8);
                size = ByteBuffer.wrap(hdr, 8, 8).getLong();
                header = 16;
            } else if (size == 0) {
                size = len - pos;
            }
            if (size < header || pos + size > len) {
                if (type.equals("moov") || !sawFtyp) {
                    throw new IOException("Truncated or corrupt MP4 box '" + type + "'");
                }
                break;
            }
            if (pos == 0 && !type.equals("ftyp") && !type.equals("moov") && !type.equals("mdat")
                    && !type.equals("free") && !type.equals("skip") && !type.equals("wide")) {
                throw new IOException("Not an MP4/M4A file");
            }
            if (type.equals("ftyp")) {
                sawFtyp = true;
            }
            if (type.equals("moof")) {
                throw new UnsupportedAudioException("fragmented MP4 is not supported");
            }
            if (type.equals("moov")) {
                long body = size - header;
                if (body > MAX_MOOV) {
                    throw new UnsupportedAudioException("MP4 header too large");
                }
                byte[] b = new byte[(int) body];
                raf.seek(pos + header);
                raf.readFully(b);
                return b;
            }
            pos += size;
        }
        throw new IOException("No 'moov' box: not an MP4/M4A audio file");
    }

    /** A child box within {@code parent}'s remaining bytes: type, and a view of its payload. */
    private record Box(String type, ByteBuffer body) {
    }

    private static List<Box> children(ByteBuffer parent) {
        List<Box> out = new ArrayList<>();
        ByteBuffer p = parent.duplicate();
        while (p.remaining() >= 8) {
            long size = p.getInt() & 0xffffffffL;
            byte[] t = new byte[4];
            p.get(t);
            String type = new String(t, StandardCharsets.ISO_8859_1);
            long header = 8;
            if (size == 1) {
                if (p.remaining() < 8) {
                    break;
                }
                size = p.getLong();
                header = 16;
            } else if (size == 0) {
                size = p.remaining() + header;
            }
            long bodyLen = size - header;
            if (bodyLen < 0 || bodyLen > p.remaining()) {
                break;
            }
            ByteBuffer body = p.slice(p.position(), (int) bodyLen);
            out.add(new Box(type, body));
            p.position(p.position() + (int) bodyLen);
        }
        return out;
    }

    private static Box find(ByteBuffer parent, String type) {
        for (Box b : children(parent)) {
            if (b.type.equals(type)) {
                return b;
            }
        }
        return null;
    }

    // ---- moov --------------------------------------------------------------------------------------------------

    private void parseMoov(ByteBuffer moov) throws IOException {
        long movieTimescale = 1000;
        Box mvhd = find(moov, "mvhd");
        if (mvhd != null) {
            ByteBuffer b = mvhd.body;
            int version = b.get(0);
            movieTimescale = Integer.toUnsignedLong(b.getInt(version == 1 ? 20 : 12));
        }
        Box audio = null;
        for (Box trak : children(moov)) {
            if (!trak.type.equals("trak")) {
                continue;
            }
            Box mdia = find(trak.body, "mdia");
            Box hdlr = mdia == null ? null : find(mdia.body, "hdlr");
            if (hdlr == null) {
                continue;
            }
            String handler = new String(bytes(hdlr.body, 8, 4), StandardCharsets.ISO_8859_1);
            if (handler.equals("vide")) {
                throw new UnsupportedAudioException("MP4 with a video track is not an audio file");
            }
            if (handler.equals("soun") && audio == null) {
                audio = trak;
            }
        }
        if (audio == null) {
            throw new UnsupportedAudioException("no audio track in the file");
        }
        if (find(moov, "mvex") != null) {
            throw new UnsupportedAudioException("fragmented MP4 is not supported");
        }
        parseTrack(audio, moov, movieTimescale);
    }

    private void parseTrack(Box trak, ByteBuffer moov, long movieTimescale) throws IOException {
        Box mdia = find(trak.body, "mdia");
        Box mdhd = find(mdia.body, "mdhd");
        Box minf = find(mdia.body, "minf");
        Box stbl = minf == null ? null : find(minf.body, "stbl");
        if (mdhd == null || stbl == null) {
            throw new IOException("Incomplete MP4 audio track");
        }
        int mv = mdhd.body.get(0);
        long timescale = Integer.toUnsignedLong(mdhd.body.getInt(mv == 1 ? 20 : 12));

        Box stsd = find(stbl.body, "stsd");
        if (stsd == null) {
            throw new IOException("No sample description");
        }
        parseSampleEntry(stsd);
        if (timescale != sampleRate) {
            throw new UnsupportedAudioException("media time scale " + timescale + " differs from the sample rate " + sampleRate);
        }

        long[] sampleDurations = sampleTable(stbl);
        long[] st = new long[sizes.length + 1];
        for (int i = 0; i < sizes.length; i++) {
            st[i + 1] = st[i] + sampleDurations[i];
        }
        starts = st;
        if (codec == Codec.AAC) {
            for (int i = 0; i < sizes.length - 1; i++) {
                if (sampleDurations[i] != 1024) {
                    throw new UnsupportedAudioException("AAC access units that are not 1024 samples long");
                }
            }
        }
        window(trak, moov, movieTimescale, timescale);
    }

    private void parseSampleEntry(Box stsd) throws IOException {
        ByteBuffer b = stsd.body;
        int entries = b.getInt(4);
        if (entries < 1) {
            throw new IOException("Empty sample description");
        }
        int pos = 8;
        long size = Integer.toUnsignedLong(b.getInt(pos));
        String fourcc = new String(bytes(b, pos + 4, 4), StandardCharsets.ISO_8859_1);
        if (size < 36 || pos + size > b.limit()) {
            throw new IOException("Bad sample entry");
        }
        ByteBuffer entry = b.slice(pos + 8, (int) size - 8);
        switch (fourcc) {
            case "mp4a" -> codec = Codec.AAC;
            case "alac" -> codec = Codec.ALAC;
            case "enca", "drms", "drmi" -> throw new UnsupportedAudioException("DRM-protected audio");
            case "ac-3", "ec-3", "samr", "sawb", ".mp3", "mp3 " -> throw new UnsupportedAudioException("unsupported codec " + fourcc);
            default -> throw new UnsupportedAudioException("unsupported MP4 audio codec '" + fourcc.strip() + "'");
        }
        int version = entry.getShort(8) & 0xffff;
        channels = entry.getShort(16) & 0xffff;
        bitsPerSample = entry.getShort(18) & 0xffff;
        sampleRate = (entry.getInt(24) >>> 16);
        int childStart = 28;
        if (version == 1) {
            childStart += 16;
        } else if (version == 2) {
            throw new UnsupportedAudioException("QuickTime sound description version 2");
        }
        ByteBuffer kids = entry.slice(childStart, entry.limit() - childStart);
        if (codec == Codec.AAC) {
            Box esds = findDeep(kids, "esds");
            if (esds == null) {
                throw new IOException("AAC track without an esds box");
            }
            parseEsds(esds.body);
        } else {
            Box alac = findAlacCookie(kids);
            if (alac == null) {
                throw new IOException("ALAC track without its configuration box");
            }
            byte[] cookie = bytes(alac.body, 4, 24);
            ByteBuffer c = ByteBuffer.wrap(cookie);
            bitsPerSample = cookie[5] & 0xff;
            channels = cookie[9] & 0xff;
            sampleRate = c.getInt(20);
            config = cookie;
            if (c.getInt(0) <= 0 || c.getInt(0) > 65536) {
                throw new IOException("Bad ALAC frame length");
            }
        }
    }

    /** The {@code alac} configuration box inside the sample entry (possibly wrapped in {@code wave}). */
    private static Box findAlacCookie(ByteBuffer kids) {
        for (Box c : children(kids)) {
            if (c.type.equals("alac") && c.body.limit() >= 28) {
                return c;
            }
            if (c.type.equals("wave")) {
                Box in = findAlacCookie(c.body);
                if (in != null) {
                    return in;
                }
            }
        }
        return null;
    }

    private static Box findDeep(ByteBuffer kids, String type) {
        for (Box c : children(kids)) {
            if (c.type.equals(type)) {
                return c;
            }
            if (c.type.equals("wave")) {
                Box in = findDeep(c.body, type);
                if (in != null) {
                    return in;
                }
            }
        }
        return null;
    }

    private void parseEsds(ByteBuffer esds) throws IOException {
        ByteBuffer b = esds.slice(4, esds.limit() - 4);
        if (b.get() != 0x03) {
            throw new IOException("Bad esds");
        }
        descriptorLength(b);
        b.getShort();   // ES_ID
        int flags = b.get() & 0xff;
        if ((flags & 0x80) != 0) {
            b.getShort();
        }
        if ((flags & 0x40) != 0) {
            b.position(b.position() + (b.get() & 0xff));
        }
        if ((flags & 0x20) != 0) {
            b.getShort();
        }
        if (b.get() != 0x04) {
            throw new IOException("Bad esds: no decoder config");
        }
        descriptorLength(b);
        int objectTypeIndication = b.get() & 0xff;
        b.position(b.position() + 12);
        if (objectTypeIndication != 0x40 && objectTypeIndication != 0x66 && objectTypeIndication != 0x67
                && objectTypeIndication != 0x68) {
            throw new UnsupportedAudioException(objectTypeIndication == 0x6b || objectTypeIndication == 0x69
                    ? "MP3 inside an MP4 container is not supported"
                    : "unsupported MP4 audio object type 0x" + Integer.toHexString(objectTypeIndication));
        }
        if (b.get() != 0x05) {
            throw new IOException("Bad esds: no decoder specific info");
        }
        int n = descriptorLength(b);
        byte[] asc = new byte[n];
        b.get(asc);
        config = asc;
        parseAsc(asc);
    }

    private static int descriptorLength(ByteBuffer b) {
        int len = 0;
        for (int i = 0; i < 4; i++) {
            int x = b.get() & 0xff;
            len = len << 7 | (x & 0x7f);
            if ((x & 0x80) == 0) {
                break;
            }
        }
        return len;
    }

    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000,
        11025, 8000, 7350};

    private void parseAsc(byte[] asc) throws IOException {
        Bits in = new Bits(asc);
        int aot = in.read(5);
        if (aot == 31) {
            aot = 32 + in.read(6);
        }
        int sfi = in.read(4);
        int rate = sfi == 15 ? in.read(24) : (sfi < AAC_RATES.length ? AAC_RATES[sfi] : 0);
        int cc = in.read(4);
        // HE-AAC (aot 5 = SBR, 29 = SBR + parametric stereo): JAAD's SBR decoder fails on real streams
        // ("stream overrun" on Microsoft encoder output), so the files are refused rather than played wrongly.
        if (aot == 5 || aot == 29) {
            throw new UnsupportedAudioException("HE-AAC (SBR/PS) is not supported, only AAC-LC");
        }
        if (aot != 2) {
            throw new UnsupportedAudioException("AAC profile " + aacProfileName(aot) + " is not supported, only AAC-LC");
        }
        if (cc < 1 || cc > 7) {
            throw new UnsupportedAudioException(cc == 0 ? "AAC channel layout defined in the stream is not supported"
                    : "unsupported AAC channel configuration " + cc);
        }
        // backward-compatible SBR signalling after the core config (sync extension 0x2b7)
        if (in.left() >= 16 && in.peek(11) == 0x2b7) {
            in.read(11);
            int ext = in.read(5);
            if (ext == 5 && in.read(1) == 1) {
                throw new UnsupportedAudioException("HE-AAC (SBR) is not supported, only AAC-LC");
            }
        }
        objectType = aot;
        channelConfig = cc;
        sampleRate = rate;
        channels = cc == 7 ? 8 : cc == 6 ? 6 : cc == 5 ? 5 : cc == 4 ? 4 : cc;
        if (rate <= 0) {
            throw new IOException("Bad AAC sample rate");
        }
    }

    static String aacProfileName(int aot) {
        return switch (aot) {
            case 1 -> "Main";
            case 3 -> "SSR";
            case 4 -> "LTP";
            case 17 -> "ER-LC";
            case 22 -> "ER BSAC";
            case 23 -> "ER-LD";
            case 39 -> "ER-ELD";
            case 42 -> "USAC (xHE-AAC)";
            default -> "type " + aot;
        };
    }

    /** MSB-first bit reader for the AudioSpecificConfig. */
    private static final class Bits {
        private final byte[] data;
        private int pos;

        Bits(byte[] data) {
            this.data = data;
        }

        int left() {
            return data.length * 8 - pos;
        }

        int peek(int n) {
            int save = pos;
            int v = read(n);
            pos = save;
            return v;
        }

        int read(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (pos >= data.length * 8) {
                    throw new IndexOutOfBoundsException("config too short");
                }
                v = v << 1 | (data[pos >> 3] >> (7 - (pos & 7))) & 1;
                pos++;
            }
            return v;
        }
    }

    // ---- sample table ------------------------------------------------------------------------------------------

    /** Fills offsets/sizes and returns the duration of each access unit in media time units. */
    private long[] sampleTable(Box stbl) throws IOException {
        Box stsz = find(stbl.body, "stsz");
        Box stsc = find(stbl.body, "stsc");
        Box stts = find(stbl.body, "stts");
        Box stco = find(stbl.body, "stco");
        boolean wide = false;
        if (stco == null) {
            stco = find(stbl.body, "co64");
            wide = true;
        }
        if (stsz == null || stsc == null || stts == null || stco == null) {
            if (find(stbl.body, "stz2") != null) {
                throw new UnsupportedAudioException("compact sample size table (stz2)");
            }
            throw new IOException("Incomplete sample table");
        }
        ByteBuffer z = stsz.body;
        int fixed = z.getInt(4);
        int n = z.getInt(8);
        if (n < 0 || n > 50_000_000) {
            throw new IOException("Bad sample count");
        }
        int[] sz = new int[n];
        for (int i = 0; i < n; i++) {
            sz[i] = fixed != 0 ? fixed : z.getInt(12 + 4 * i);
            if (sz[i] < 0) {
                throw new IOException("Bad sample size");
            }
        }
        ByteBuffer co = stco.body;
        int chunks = co.getInt(4);
        ByteBuffer sc = stsc.body;
        int runs = sc.getInt(4);
        long[] off = new long[n];
        int sample = 0;
        for (int r = 0; r < runs && sample < n; r++) {
            int first = sc.getInt(8 + 12 * r);
            int per = sc.getInt(12 + 12 * r);
            int next = r + 1 < runs ? sc.getInt(8 + 12 * (r + 1)) : chunks + 1;
            for (int ch = first; ch < next && ch <= chunks && sample < n; ch++) {
                long pos = wide ? co.getLong(8 + 8 * (ch - 1)) : Integer.toUnsignedLong(co.getInt(8 + 4 * (ch - 1)));
                for (int k = 0; k < per && sample < n; k++) {
                    off[sample] = pos;
                    pos += sz[sample];
                    sample++;
                }
            }
        }
        if (sample != n) {
            throw new IOException("Sample table does not cover all samples");
        }
        offsets = off;
        sizes = sz;

        ByteBuffer t = stts.body;
        int entries = t.getInt(4);
        long[] dur = new long[n];
        int i = 0;
        for (int e = 0; e < entries && i < n; e++) {
            int count = t.getInt(8 + 8 * e);
            long delta = Integer.toUnsignedLong(t.getInt(12 + 8 * e));
            for (int k = 0; k < count && i < n; k++) {
                dur[i++] = delta;
            }
        }
        if (i != n) {
            throw new IOException("Time table does not cover all samples");
        }
        return dur;
    }

    // ---- gapless window ----------------------------------------------------------------------------------------

    private void window(Box trak, ByteBuffer moov, long movieTimescale, long mediaTimescale) {
        long total = decodedSamples();
        skip = 0;
        length = total;
        Box edts = find(trak.body, "edts");
        Box elst = edts == null ? null : find(edts.body, "elst");
        if (elst != null) {
            ByteBuffer e = elst.body;
            int version = e.get(0);
            int n = e.getInt(4);
            int step = version == 1 ? 20 : 12;
            int real = 0;
            long mediaTime = 0;
            long segment = 0;
            for (int i = 0; i < n; i++) {
                int at = 8 + step * i;
                long seg = version == 1 ? e.getLong(at) : Integer.toUnsignedLong(e.getInt(at));
                long mt = version == 1 ? e.getLong(at + 8) : e.getInt(at + 4);
                if (mt >= 0) {
                    real++;
                    mediaTime = mt;
                    segment = seg;
                }
            }
            if (real == 1 && mediaTime <= total) {
                skip = mediaTime;
                long play = segment * mediaTimescale / Math.max(1, movieTimescale);
                length = segment == 0 ? total - skip : Math.min(play, total - skip);
                gapless = true;
                return;
            }
        }
        long[] smpb = itunSmpb(moov);
        if (smpb != null && smpb[0] >= 0 && smpb[0] <= total) {
            skip = smpb[0];
            long valid = smpb[2] > 0 ? smpb[2] : total - smpb[0] - smpb[1];
            length = Math.max(0, Math.min(valid, total - skip));
            gapless = true;
        }
    }

    /** {priming, padding, valid samples} from the iTunSMPB comment, or null. */
    static long[] itunSmpb(ByteBuffer moov) {
        byte[] all = new byte[moov.limit()];
        moov.duplicate().position(0).get(all);
        byte[] key = "iTunSMPB".getBytes(StandardCharsets.ISO_8859_1);
        int at = indexOf(all, key, 0);
        if (at < 0) {
            return null;
        }
        byte[] data = "data".getBytes(StandardCharsets.ISO_8859_1);
        int d = indexOf(all, data, at + key.length);
        if (d < 4 || d - 4 < at) {
            return null;
        }
        long size = ByteBuffer.wrap(all, d - 4, 4).getInt() & 0xffffffffL;
        int textStart = d + 4 + 8;
        int textEnd = (int) Math.min(all.length, d - 4 + size);
        if (textEnd <= textStart) {
            return null;
        }
        String text = new String(all, textStart, textEnd - textStart, StandardCharsets.ISO_8859_1).strip();
        String[] f = text.split("\\s+");
        if (f.length < 4) {
            return null;
        }
        try {
            return new long[] {Long.parseLong(f[1], 16), Long.parseLong(f[2], 16), Long.parseLong(f[3], 16)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] bytes(ByteBuffer b, int at, int n) {
        byte[] out = new byte[n];
        b.get(at, out);
        return out;
    }
}
