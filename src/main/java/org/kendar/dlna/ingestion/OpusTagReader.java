package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.TrackTags;

/**
 * Tags and exact length of Ogg Opus files (RFC 7845), which jaudiotagger cannot read. Parses {@code OpusHead} and
 * {@code OpusTags} from the first pages and takes the length from the last page granule minus the pre-skip.
 * Embedded pictures are not extracted (art comes from {@code front.jpg}).
 */
public final class OpusTagReader implements TagReader {

    private static final int MAX_TAGS_BYTES = 8 << 20;
    private static final int RATE = 48_000;

    @Override
    public boolean supports(Format format) {
        return format == Format.OPUS;
    }

    @Override
    public TrackMeta read(Path file, Format format) throws TagReadException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            List<byte[]> headers = firstPackets(raf);
            byte[] head = headers.get(0);
            if (head.length < 19 || !"OpusHead".equals(new String(head, 0, 8, StandardCharsets.US_ASCII))) {
                throw new IOException("not an Ogg Opus stream");
            }
            int channels = head[9] & 0xff;
            int preSkip = (head[10] & 0xff) | (head[11] & 0xff) << 8;
            long last = lastGranule(raf);
            long samples = Math.max(0, last - preSkip);
            long millis = samples * 1000 / RATE;
            int bitrate = millis > 0 ? (int) (raf.length() * 8 / millis) : 0;
            TrackTags tags = headers.size() > 1 ? parseTags(headers.get(1)) : TrackTags.EMPTY;
            return new TrackMeta(tags, millis, bitrate, RATE, channels, false, samples);
        } catch (IOException | RuntimeException e) {
            throw new TagReadException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The first two packets (OpusHead, OpusTags), reassembled across lacing and pages. */
    private static List<byte[]> firstPackets(RandomAccessFile raf) throws IOException {
        List<byte[]> packets = new ArrayList<>();
        byte[] partial = new byte[0];
        long pos = 0;
        byte[] hdr = new byte[27];
        while (packets.size() < 2 && pos + 27 <= raf.length()) {
            raf.seek(pos);
            raf.readFully(hdr);
            if (hdr[0] != 'O' || hdr[1] != 'g' || hdr[2] != 'g' || hdr[3] != 'S') {
                throw new IOException("Ogg capture pattern lost at " + pos);
            }
            int segs = hdr[26] & 0xff;
            byte[] table = new byte[segs];
            raf.readFully(table);
            int body = 0;
            for (byte b : table) {
                body += b & 0xff;
            }
            byte[] data = new byte[body];
            raf.readFully(data);
            boolean continued = (hdr[5] & 1) != 0;
            if (!continued) {
                partial = new byte[0];
            }
            int off = 0;
            for (byte t : table) {
                int seg = t & 0xff;
                if (partial.length + seg > MAX_TAGS_BYTES) {
                    throw new IOException("tag packet too large");
                }
                partial = Arrays.copyOf(partial, partial.length + seg);
                System.arraycopy(data, off, partial, partial.length - seg, seg);
                off += seg;
                if (seg < 255) {
                    packets.add(partial);
                    partial = new byte[0];
                    if (packets.size() == 2) {
                        break;
                    }
                }
            }
            pos += 27 + segs + body;
        }
        if (packets.isEmpty()) {
            throw new IOException("no Ogg packets");
        }
        return packets;
    }

    /** Granule position of the last page that has one. */
    private static long lastGranule(RandomAccessFile raf) throws IOException {
        long len = raf.length();
        int window = (int) Math.min(len, 128 * 1024);
        byte[] tail = new byte[window];
        raf.seek(len - window);
        raf.readFully(tail);
        for (int i = window - 27; i >= 0; i--) {
            if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S' && tail[i + 4] == 0) {
                long granule = 0;
                for (int b = 7; b >= 0; b--) {
                    granule = granule << 8 | (tail[i + 6 + b] & 0xff);
                }
                if (granule >= 0) {
                    return granule;
                }
            }
        }
        throw new IOException("no page with a granule position");
    }

    /** Parses an {@code OpusTags} packet into the catalog tags. */
    static TrackTags parseTags(byte[] t) {
        Map<String, List<String>> kv = new LinkedHashMap<>();
        if (t.length >= 16 && "OpusTags".equals(new String(t, 0, 8, StandardCharsets.US_ASCII))) {
            int pos = 8;
            int vendor = le32(t, pos);
            pos += 4 + Math.max(0, vendor);
            int n = pos + 4 <= t.length ? le32(t, pos) : 0;
            pos += 4;
            for (int i = 0; i < n && pos + 4 <= t.length; i++) {
                int len = le32(t, pos);
                pos += 4;
                if (len < 0 || pos + len > t.length) {
                    break;
                }
                String c = new String(t, pos, len, StandardCharsets.UTF_8);
                pos += len;
                int eq = c.indexOf('=');
                if (eq > 0) {
                    kv.computeIfAbsent(c.substring(0, eq).toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(c.substring(eq + 1));
                }
            }
        }
        List<String> artists = values(kv, "ARTIST");
        List<String> albumArtists = values(kv, "ALBUMARTIST");
        List<String> genres = values(kv, "GENRE");
        List<String> composers = values(kv, "COMPOSER");
        Integer discTotal = leadingInt(first(kv, "DISCTOTAL"));
        if (discTotal == null) {
            discTotal = leadingInt(first(kv, "TOTALDISCS"));
        }
        String disc = first(kv, "DISCNUMBER");
        if (discTotal == null && disc != null && disc.contains("/")) {
            discTotal = leadingInt(disc.substring(disc.indexOf('/') + 1));
        }
        return new TrackTags(first(kv, "TITLE"), first(kv, "ARTIST"), first(kv, "ALBUM"), first(kv, "ALBUMARTIST"),
                first(kv, "GENRE"), leadingInt(first(kv, "DATE")), leadingInt(first(kv, "TRACKNUMBER")),
                leadingInt(disc), discTotal, artists.size() > 1 ? artists : null,
                albumArtists.size() > 1 ? albumArtists : null, genres.size() > 1 ? genres : null,
                first(kv, "COMPOSER"), composers.size() > 1 ? composers : null);
    }

    private static String first(Map<String, List<String>> kv, String key) {
        for (String v : kv.getOrDefault(key, List.of())) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static List<String> values(Map<String, List<String>> kv, String key) {
        List<String> out = new ArrayList<>();
        for (String v : kv.getOrDefault(key, List.of())) {
            if (v != null && !v.isBlank() && !out.contains(v.strip())) {
                out.add(v.strip());
            }
        }
        return out;
    }

    private static Integer leadingInt(String s) {
        return JAudioTaggerReader.leadingInt(s);
    }

    private static int le32(byte[] b, int o) {
        if (o + 4 > b.length) {
            return -1;
        }
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
}
