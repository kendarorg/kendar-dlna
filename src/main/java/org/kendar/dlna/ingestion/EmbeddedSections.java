package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.TagTextField;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.ingestion.CueSheet.CueFile;
import org.kendar.dlna.ingestion.CueSheet.CueTrack;

/**
 * Sections stored inside an audio file, read in pure Java and never throwing (a file without usable sections, or
 * with unreadable ones, yields empty):
 * <ul>
 *   <li>MP3: ID3v2.3/2.4 {@code CHAP} frames (titles from their {@code TIT2}, performer from {@code TPE1}); when a
 *       top-level {@code CTOC} exists only the chapters it lists are used. Chapters are ordered by start time.</li>
 *   <li>M4A/MP4: the Nero {@code chpl} atom ({@code moov/udta/chpl}). QuickTime text-track chapters are not read.</li>
 *   <li>FLAC and OGG: a Vorbis comment {@code CUESHEET} (full cue text, origin {@code tag-cue}), else
 *       {@code CHAPTERnnn=hh:mm:ss.mmm} with {@code CHAPTERnnnNAME} (origin {@code chapters}).</li>
 * </ul>
 * The native FLAC CUESHEET metadata block is not read.
 */
public final class EmbeddedSections {

    /** The sheet and where it came from ({@code tag-cue} or {@code chapters}). */
    public record Found(CueSheet sheet, String origin) {
    }

    private static final int MAX_TAG_BYTES = 32 * 1024 * 1024;
    private static final Pattern CHAPTER = Pattern.compile("^CHAPTER(\\d+)$");
    private static final Pattern CHAPTER_TIME = Pattern.compile("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,9}))?$");

    private EmbeddedSections() {
    }

    public static Optional<Found> read(Path file, Format format) {
        try {
            return switch (format) {
                case MP3 -> fromChapters(id3Chapters(file), "chapters");
                case AAC, ALAC -> fromChapters(mp4Chapters(file), "chapters");
                case FLAC, OGG -> vorbis(file);
                default -> Optional.empty();
            };
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---- common ----

    /** One chapter before it becomes a cue track. */
    record Chapter(long startMillis, String title, String performer) {
    }

    private static Optional<Found> fromChapters(List<Chapter> chapters, String origin) {
        if (chapters.size() < 2) {
            return Optional.empty();
        }
        List<Chapter> sorted = new ArrayList<>(chapters);
        sorted.sort(Comparator.comparingLong(Chapter::startMillis));
        List<CueTrack> tracks = new ArrayList<>();
        long last = -1;
        int n = 0;
        for (Chapter c : sorted) {
            if (c.startMillis() <= last) {
                continue;
            }
            last = c.startMillis();
            n++;
            tracks.add(new CueTrack(n, c.title(), c.performer(), null, null, -1, c.startMillis() * 1000L));
        }
        if (tracks.size() < 2) {
            return Optional.empty();
        }
        CueSheet sheet = new CueSheet(null, null, null, null, null, null, List.of(new CueFile(null, tracks)),
                List.of());
        return Optional.of(new Found(sheet, origin));
    }

    // ---- ID3v2 ----

    static List<Chapter> id3Chapters(Path file) throws IOException {
        byte[] tag = id3Tag(file);
        if (tag == null) {
            return List.of();
        }
        int major = tag[3] & 0xFF;
        int flags = tag[5] & 0xFF;
        int size = syncsafe(tag, 6);
        byte[] body = new byte[Math.min(size, tag.length - 10)];
        System.arraycopy(tag, 10, body, 0, body.length);
        if ((flags & 0x80) != 0) {
            body = deUnsync(body);
        }
        int pos = 0;
        if ((flags & 0x40) != 0 && body.length >= 4) {   // extended header
            int ext = major == 4 ? syncsafe(body, 0) : (int) be32(body, 0) + 4;
            pos = Math.max(0, Math.min(ext, body.length));
        }
        Map<String, Chapter> byId = new HashMap<>();
        List<String> topLevel = null;
        for (Frame f : frames(body, pos, body.length, major)) {
            if (f.id.equals("CHAP")) {
                int z = indexOf0(f.data, 0);
                if (z < 0 || f.data.length < z + 17) {
                    continue;
                }
                String id = new String(f.data, 0, z, StandardCharsets.ISO_8859_1);
                long start = be32(f.data, z + 1);
                int sub = z + 1 + 16;
                String title = null;
                String performer = null;
                for (Frame s : frames(f.data, sub, f.data.length, major)) {
                    if (s.id.equals("TIT2") && title == null) {
                        title = text(s.data);
                    } else if (s.id.equals("TPE1") && performer == null) {
                        performer = text(s.data);
                    }
                }
                byId.put(id, new Chapter(start, title, performer));
            } else if (f.id.equals("CTOC") && f.data.length > 3) {
                int z = indexOf0(f.data, 0);
                if (z > 0 && z + 2 < f.data.length && (f.data[z + 1] & 0x02) != 0) {
                    int count = f.data[z + 2] & 0xFF;
                    List<String> ids = new ArrayList<>();
                    int p = z + 3;
                    for (int i = 0; i < count && p < f.data.length; i++) {
                        int e = indexOf0(f.data, p);
                        if (e < 0) {
                            break;
                        }
                        ids.add(new String(f.data, p, e - p, StandardCharsets.ISO_8859_1));
                        p = e + 1;
                    }
                    topLevel = ids;
                }
            }
        }
        List<Chapter> out = new ArrayList<>();
        if (topLevel != null) {
            Set<String> seen = new LinkedHashSet<>(topLevel);
            for (String id : seen) {
                Chapter c = byId.get(id);
                if (c != null) {
                    out.add(c);
                }
            }
            if (out.size() >= 2) {
                return out;
            }
            out.clear();
        }
        out.addAll(byId.values());
        return out;
    }

    private static byte[] id3Tag(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[10];
            if (raf.length() < 10) {
                return null;
            }
            raf.readFully(head);
            if (head[0] != 'I' || head[1] != 'D' || head[2] != '3' || (head[3] & 0xFF) < 3 || (head[3] & 0xFF) > 4) {
                return null;
            }
            int size = syncsafe(head, 6);
            if (size <= 0 || size > MAX_TAG_BYTES || size + 10L > raf.length()) {
                return null;
            }
            byte[] all = new byte[size + 10];
            System.arraycopy(head, 0, all, 0, 10);
            raf.readFully(all, 10, size);
            return all;
        }
    }

    private record Frame(String id, byte[] data) {
    }

    private static List<Frame> frames(byte[] b, int from, int to, int major) {
        List<Frame> out = new ArrayList<>();
        int p = from;
        while (p + 10 <= to) {
            if (b[p] == 0) {
                break;
            }
            String id = new String(b, p, 4, StandardCharsets.ISO_8859_1);
            long size = major == 4 ? syncsafe(b, p + 4) : be32(b, p + 4);
            int flags2 = b[p + 9] & 0xFF;
            int data = p + 10;
            if (size < 0 || data + size > to) {
                break;
            }
            int len = (int) size;
            boolean unusable = false;
            byte[] content;
            if (major == 4) {
                boolean lengthIndicator = (flags2 & 0x01) != 0;
                boolean compressed = (flags2 & 0x08) != 0 || (b[p + 8] & 0x04) != 0;
                boolean encrypted = (flags2 & 0x04) != 0 || (b[p + 8] & 0x02) != 0;
                int skip = lengthIndicator ? 4 : 0;
                unusable = compressed || encrypted || skip > len;
                content = unusable ? new byte[0] : copy(b, data + skip, len - skip);
                if (!unusable && (flags2 & 0x02) != 0) {
                    content = deUnsync(content);
                }
            } else {
                unusable = (b[p + 8] & 0xC0) != 0;   // compression / encryption
                content = unusable ? new byte[0] : copy(b, data, len);
            }
            if (!unusable) {
                out.add(new Frame(id, content));
            }
            p = data + len;
        }
        return out;
    }

    private static String text(byte[] d) {
        if (d.length < 2) {
            return null;
        }
        int enc = d[0] & 0xFF;
        Charset cs = switch (enc) {
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> Charset.forName("windows-1252");
        };
        int end = d.length;
        // text ends at the first terminator (single byte, or two zero bytes for UTF-16)
        if (enc == 1 || enc == 2) {
            for (int i = 1; i + 1 < d.length; i += 2) {
                if (d[i] == 0 && d[i + 1] == 0) {
                    end = i;
                    break;
                }
            }
        } else {
            for (int i = 1; i < d.length; i++) {
                if (d[i] == 0) {
                    end = i;
                    break;
                }
            }
        }
        String s = new String(d, 1, Math.max(0, end - 1), cs).strip();
        return s.isEmpty() ? null : s;
    }

    private static byte[] deUnsync(byte[] b) {
        byte[] out = new byte[b.length];
        int n = 0;
        for (int i = 0; i < b.length; i++) {
            out[n++] = b[i];
            if ((b[i] & 0xFF) == 0xFF && i + 1 < b.length && b[i + 1] == 0) {
                i++;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static int syncsafe(byte[] b, int p) {
        return ((b[p] & 0x7F) << 21) | ((b[p + 1] & 0x7F) << 14) | ((b[p + 2] & 0x7F) << 7) | (b[p + 3] & 0x7F);
    }

    private static long be32(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static int indexOf0(byte[] b, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] copy(byte[] b, int from, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, from, out, 0, len);
        return out;
    }

    // ---- MP4 chpl ----

    static List<Chapter> mp4Chapters(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long pos = 0;
            long len = raf.length();
            while (pos + 8 <= len) {
                raf.seek(pos);
                long size = raf.readInt() & 0xFFFFFFFFL;
                byte[] type = new byte[4];
                raf.readFully(type);
                long header = 8;
                if (size == 1) {
                    size = raf.readLong();
                    header = 16;
                } else if (size == 0) {
                    size = len - pos;
                }
                if (size < header) {
                    return List.of();
                }
                if (new String(type, StandardCharsets.ISO_8859_1).equals("moov")) {
                    long payload = size - header;
                    if (payload > MAX_TAG_BYTES) {
                        return List.of();
                    }
                    byte[] moov = new byte[(int) payload];
                    raf.seek(pos + header);
                    raf.readFully(moov);
                    return chpl(moov);
                }
                pos += size;
            }
        }
        return List.of();
    }

    private static List<Chapter> chpl(byte[] moov) {
        byte[] udta = box(moov, "udta");
        byte[] chpl = udta == null ? null : box(udta, "chpl");
        if (chpl == null || chpl.length < 9) {
            return List.of();
        }
        int p = 0;
        int version = chpl[p] & 0xFF;
        p += 4;
        if (version != 0) {
            p += 4;
        }
        if (p >= chpl.length) {
            return List.of();
        }
        int count = chpl[p++] & 0xFF;
        List<Chapter> out = new ArrayList<>();
        for (int i = 0; i < count && p + 9 <= chpl.length; i++) {
            long units = 0;
            for (int k = 0; k < 8; k++) {
                units = (units << 8) | (chpl[p + k] & 0xFF);
            }
            p += 8;
            int tl = chpl[p++] & 0xFF;
            if (p + tl > chpl.length) {
                break;
            }
            String title = new String(chpl, p, tl, StandardCharsets.UTF_8).strip();
            p += tl;
            out.add(new Chapter(units / 10_000L, title.isEmpty() ? null : title, null));
        }
        return out;
    }

    /** Payload of the first child box of the given type, or null. */
    private static byte[] box(byte[] data, String type) {
        int p = 0;
        while (p + 8 <= data.length) {
            long size = be32(data, p);
            String t = new String(data, p + 4, 4, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1 && p + 16 <= data.length) {
                size = be32(data, p + 12);
                header = 16;
            } else if (size == 0) {
                size = data.length - p;
            }
            if (size < header || p + size > data.length) {
                return null;
            }
            if (t.equals(type)) {
                return copy(data, p + header, (int) size - header);
            }
            p += (int) size;
        }
        return null;
    }

    // ---- Vorbis comments (FLAC, OGG) ----

    private static Optional<Found> vorbis(Path file) throws IOException {
        Tag tag;
        try {
            AudioFile af = AudioFileIO.read(file.toFile());
            tag = af.getTag();
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (tag == null) {
            return Optional.empty();
        }
        String cue = null;
        Map<Integer, Long> starts = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        Iterator<TagField> it = tag.getFields();
        while (it.hasNext()) {
            TagField f = it.next();
            String id = f.getId() == null ? "" : f.getId().toUpperCase(Locale.ROOT);
            String value = f instanceof TagTextField tf ? tf.getContent() : null;
            if (value == null) {
                continue;
            }
            if (id.equals("CUESHEET") && cue == null) {
                cue = value;
                continue;
            }
            if (id.endsWith("NAME")) {
                Matcher m = CHAPTER.matcher(id.substring(0, id.length() - 4));
                if (m.matches()) {
                    names.put(Integer.parseInt(m.group(1)), value.strip());
                }
                continue;
            }
            Matcher m = CHAPTER.matcher(id);
            if (m.matches()) {
                long ms = chapterMillis(value.strip());
                if (ms >= 0) {
                    starts.put(Integer.parseInt(m.group(1)), ms);
                }
            }
        }
        if (cue != null && !cue.isBlank()) {
            CueSheet sheet = CueParser.parse(cue);
            boolean usable = sheet.files().stream().anyMatch(f -> f.tracks().size() >= 2);
            if (usable) {
                return Optional.of(new Found(sheet, "tag-cue"));
            }
        }
        List<Chapter> chapters = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : starts.entrySet()) {
            chapters.add(new Chapter(e.getValue(), names.get(e.getKey()), null));
        }
        return fromChapters(chapters, "chapters");
    }

    static long chapterMillis(String s) {
        Matcher m = CHAPTER_TIME.matcher(s);
        if (!m.matches()) {
            return -1;
        }
        long ms = 0;
        if (m.group(4) != null) {
            String frac = (m.group(4) + "000").substring(0, 3);
            ms = Long.parseLong(frac);
        }
        return ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3)))
                * 1000 + ms;
    }
}
