package org.kendar.dlna.personal;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading and writing playlist files: M3U / M3U8 (with {@code #EXTINF}) and PLS. Pure text handling, no catalog.
 */
public final class PlaylistFiles {

    /** One entry of a playlist file: the location as written, and the title when the file has one. */
    public record Entry(String location, String title) {
    }

    /** A written file. */
    public record Output(String fileName, String contentType, byte[] body) {
    }

    /** A track to write. */
    public record Item(String location, String title, long durationMillis) {
    }

    public enum Format {
        M3U, M3U8, PLS;

        public static Format parse(String s) {
            if (s == null || s.isBlank()) {
                return M3U8;
            }
            return switch (s.strip().toLowerCase(Locale.ROOT)) {
                case "m3u" -> M3U;
                case "m3u8" -> M3U8;
                case "pls" -> PLS;
                default -> throw new IllegalArgumentException("format must be m3u, m3u8 or pls");
            };
        }
    }

    private PlaylistFiles() {
    }

    // ---- reading ----

    /** Decodes the bytes: UTF-8 (with or without BOM), else ISO-8859-1 (old M3U files). */
    public static String decode(byte[] data) {
        int off = data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF
                ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, off, data.length - off))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(data, off, data.length - off, StandardCharsets.ISO_8859_1);
        }
    }

    /** Parses M3U or PLS content (told apart by the {@code [playlist]} header). */
    public static List<Entry> parse(byte[] data) {
        String text = decode(data);
        String first = text.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        return first.equalsIgnoreCase("[playlist]") ? parsePls(text) : parseM3u(text);
    }

    private static final Pattern EXTINF = Pattern.compile("^#EXTINF:\\s*-?\\d+(?:\\.\\d+)?[^,]*,(.*)$",
            Pattern.CASE_INSENSITIVE);

    static List<Entry> parseM3u(String text) {
        List<Entry> out = new ArrayList<>();
        String title = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                Matcher m = EXTINF.matcher(line);
                if (m.matches()) {
                    title = m.group(1).strip();
                }
                continue;
            }
            out.add(new Entry(line, title == null || title.isEmpty() ? null : title));
            title = null;
        }
        return out;
    }

    private static final Pattern PLS_KEY = Pattern.compile("^(File|Title)(\\d+)\\s*=(.*)$", Pattern.CASE_INSENSITIVE);

    static List<Entry> parsePls(String text) {
        Map<Integer, String> files = new TreeMap<>();
        Map<Integer, String> titles = new HashMap<>();
        for (String raw : text.split("\\R")) {
            Matcher m = PLS_KEY.matcher(raw.strip());
            if (!m.matches()) {
                continue;
            }
            int n = Integer.parseInt(m.group(2));
            String v = m.group(3).strip();
            if (m.group(1).equalsIgnoreCase("File")) {
                files.put(n, v);
            } else {
                titles.put(n, v);
            }
        }
        List<Entry> out = new ArrayList<>();
        files.forEach((n, f) -> {
            if (!f.isEmpty()) {
                out.add(new Entry(f, titles.getOrDefault(n, "").isEmpty() ? null : titles.get(n)));
            }
        });
        return out;
    }

    /**
     * The location as a list of path segments to look for in the library: URL form decoded, back slashes turned into
     * slashes, drive letters, {@code ./} and {@code ../} dropped. Empty for streams ({@code http://}) and blanks.
     */
    public static List<String> segments(String location) {
        String s = location.strip();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file://")) {
            s = s.substring(7);
            try {
                s = URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // keep as written
            }
        } else if (lower.matches("^[a-z][a-z0-9+.-]*://.*")) {
            return List.of();
        }
        s = s.replace('\\', '/');
        if (s.matches("^/?[A-Za-z]:/.*")) {
            s = s.substring(s.indexOf(':') + 1);
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split("/")) {
            if (!p.isEmpty() && !p.equals(".") && !p.equals("..")) {
                out.add(p);
            }
        }
        return out;
    }

    // ---- writing ----

    public static Output write(Format format, String name, List<Item> items) {
        String base = fileSafe(name);
        StringBuilder sb = new StringBuilder();
        if (format == Format.PLS) {
            sb.append("[playlist]\r\n");
            int n = 0;
            for (Item it : items) {
                n++;
                sb.append("File").append(n).append('=').append(it.location()).append("\r\n");
                sb.append("Title").append(n).append('=').append(oneLine(it.title())).append("\r\n");
                sb.append("Length").append(n).append('=').append(it.durationMillis() > 0
                        ? Math.round(it.durationMillis() / 1000.0) : -1).append("\r\n");
            }
            sb.append("NumberOfEntries=").append(n).append("\r\nVersion=2\r\n");
            return new Output(base + ".pls", "audio/x-scpls; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        sb.append("#EXTM3U\r\n");
        for (Item it : items) {
            sb.append("#EXTINF:").append(it.durationMillis() > 0 ? Math.round(it.durationMillis() / 1000.0) : -1)
                    .append(',').append(oneLine(it.title())).append("\r\n").append(it.location()).append("\r\n");
        }
        // M3U8 is UTF-8 by definition; plain M3U is written in UTF-8 as well, readers sniff it (decode())
        return new Output(base + (format == Format.M3U ? ".m3u" : ".m3u8"),
                format == Format.M3U ? "audio/x-mpegurl; charset=utf-8" : "audio/mpegurl; charset=utf-8",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").strip();
    }

    private static String fileSafe(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").strip();
        return s.isEmpty() ? "playlist" : s;
    }
}
