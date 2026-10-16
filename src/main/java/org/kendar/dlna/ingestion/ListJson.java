package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kendar.dlna.catalog.TrackTags;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * A directory's {@code list.json}: an array of {@code {filename, author, partOf, title, transcriber}} objects that
 * give per-track metadata to files that carry little or no tags (typically MIDI). Parsing is tolerant and never
 * throws: a UTF-8/UTF-16 BOM is skipped, text that is not UTF-8 is read as Windows-1252, comments, trailing commas
 * and single quotes are accepted, non-string values are converted, entries that are not usable are skipped with a
 * warning, and a file that cannot be parsed at all yields no entries plus an {@link Result#error() error} (browsing
 * must not fail). File names match case-insensitively; when a name appears twice the first entry wins.
 *
 * <p>Merge rule ({@link #apply}): a non-blank {@code title} replaces the tag title (it is what DLNA, the REST API,
 * the web player and search show), {@code author} replaces the artist, {@code partOf} the album, and
 * {@code transcriber} is stored as the extra {@code transcriber} tag. Blank or missing fields leave the embedded
 * tags untouched. So list.json wins over embedded tags field by field.
 *
 * <p>Sections: an entry about one virtual track of a cue / chapter split file names the source file in
 * {@code filename} and the section with {@code index} (the {@code n} of {@code file#n}) and/or {@code start}
 * ({@code mm:ss.fff}). Lookup for section n: the entry with index n (an entry whose filename is literally
 * {@code file#n} is the same thing), else the entry whose {@code start} is the closest one within
 * {@link #START_TOLERANCE_MILLIS}; when both are given the index wins. Whole-file entries never match sections and
 * vice versa; the first of several entries with the same identity wins.
 */
public final class ListJson {

    public static final String FILE_NAME = "list.json";

    /** How far (milliseconds) an entry's {@code start} may be from a section's real start and still match it. */
    public static final long START_TOLERANCE_MILLIS = 500;

    /**
     * One entry; every field except {@code filename} may be null (= not given). {@code filename} is the file the
     * entry is about. {@code index} (1-based track number, the {@code n} of the virtual track {@code file#n}) and
     * {@code start} (position in the file, {@code mm:ss.fff}) optionally narrow it to one section of a cue /
     * chapter split file; they are locators, not metadata. With neither, the entry is about the whole file.
     */
    public record Entry(String filename, String author, String partOf, String title, String transcriber,
            Integer index, String start) {

        /** A whole-file entry (no section locator). */
        public Entry(String filename, String author, String partOf, String title, String transcriber) {
            this(filename, author, partOf, title, transcriber, null, null);
        }

        /** True when the entry carries no metadata at all (the locators do not count). */
        public boolean isBlank() {
            return author == null && partOf == null && title == null && transcriber == null;
        }

        /** True when the entry is about one section of its file. */
        public boolean isSection() {
            return index != null || start != null;
        }

        /** {@code start} in milliseconds, or -1 when absent or malformed. */
        public long startMillis() {
            return parseTime(start);
        }

        /**
         * Identity of the entry: the file key, plus {@code #n} for an index, else {@code @ms} for a start. An
         * entry whose {@code filename} is already {@code disc.flac#2} has the same key as {@code disc.flac} with
         * index 2.
         */
        public String matchKey() {
            String k = key(filename);
            if (index != null) {
                return k + "#" + index;
            }
            return start != null ? k + "@" + startMillis() : k;
        }
    }

    /**
     * @param entries  usable entries in file order (duplicates removed)
     * @param warnings skipped or repaired entries
     * @param error    non-null when the whole file is unusable (then {@code entries} is empty)
     * @param hash     identifies the file content (null = no file)
     */
    public record Result(List<Entry> entries, List<String> warnings, String error, String hash) {

        public static final Result NONE = new Result(List.of(), List.of(), null, null);

        public Result {
            entries = List.copyOf(entries);
            warnings = List.copyOf(warnings);
        }

        public boolean exists() {
            return hash != null;
        }

        /** Entries by match key. */
        public Map<String, Entry> index() {
            Map<String, Entry> m = new LinkedHashMap<>();
            for (Entry e : entries) {
                m.put(e.matchKey(), e);
            }
            return m;
        }

        public Optional<Entry> find(String fileName) {
            String k = key(fileName);
            return entries.stream().filter(e -> e.matchKey().equals(k)).findFirst();
        }

        /**
         * The entry for section {@code n} of {@code fileName}, which starts at {@code startMillis}: first the entry
         * with index {@code n} (or named {@code fileName#n}), else the entry whose {@code start} is the closest one
         * within {@link #START_TOLERANCE_MILLIS}.
         */
        public Optional<Entry> findSection(String fileName, int n, long startMillis) {
            Optional<Entry> byIndex = find(fileName + "#" + n);
            if (byIndex.isPresent()) {
                return byIndex;
            }
            String k = key(fileName);
            Entry best = null;
            long bestDelta = Long.MAX_VALUE;
            for (Entry e : entries) {
                if (e.index() != null || e.start() == null || !key(e.filename()).equals(k)) {
                    continue;
                }
                long delta = Math.abs(e.startMillis() - startMillis);
                if (delta <= START_TOLERANCE_MILLIS && delta < bestDelta) {
                    best = e;
                    bestDelta = delta;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_YAML_COMMENTS,
                    JsonReadFeature.ALLOW_TRAILING_COMMA, JsonReadFeature.ALLOW_SINGLE_QUOTES,
                    JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .build();

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private ListJson() {
    }

    /** The match key of a file name: Unicode NFC and case folded. */
    public static String key(String fileName) {
        return Normalizer.normalize(fileName == null ? "" : fileName.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
    }

    /** The list.json of {@code dir} (any letter case), or empty. */
    public static Optional<Path> locate(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        Path exact = dir.resolve(FILE_NAME);
        if (Files.isRegularFile(exact)) {
            return Optional.of(exact);
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (p.getFileName().toString().equalsIgnoreCase(FILE_NAME) && Files.isRegularFile(p)) {
                    return Optional.of(p);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Parses the list.json of a directory; {@link Result#NONE} when it has none. */
    public static Result forDirectory(Path dir) {
        return locate(dir).map(ListJson::parseFile).orElse(Result.NONE);
    }

    public static Result parseFile(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            return new Result(List.of(), List.of(), "cannot read file: " + e.getMessage(), hash(new byte[0]));
        }
        return parse(bytes);
    }

    public static Result parse(byte[] bytes) {
        return parse(bytes, null);
    }

    /**
     * Like {@link #parse(byte[])}; an entry without a {@code filename} is about {@code defaultFilename} when that is
     * given (a per-file sidecar, see {@link SidecarJson}), and a bare object is then one entry.
     */
    public static Result parse(byte[] bytes, String defaultFilename) {
        String hash = hash(bytes);
        String text = decode(bytes);
        List<String> warnings = new ArrayList<>();
        JsonNode root;
        try {
            root = text.isBlank() ? null : LENIENT.readTree(text);
        } catch (IOException | RuntimeException e) {
            return new Result(List.of(), warnings, "malformed JSON: " + firstLine(e.getMessage()), hash);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new Result(List.of(), warnings, null, hash);   // empty file
        }
        List<JsonNode> items = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(items::add);
        } else if (root.isObject() && root.has("filename")) {
            items.add(root);   // a single bare entry
        } else if (root.isObject() && root.path("tracks").isArray()) {
            root.get("tracks").forEach(items::add);
        } else if (root.isObject() && defaultFilename != null) {
            items.add(root);   // a sidecar holding one whole-file entry without a file name
        } else {
            return new Result(List.of(), warnings, "list.json must be a JSON array of entries", hash);
        }
        Map<String, Entry> byKey = new LinkedHashMap<>();
        int n = 0;
        for (JsonNode item : items) {
            n++;
            if (!item.isObject()) {
                warnings.add("entry " + n + " is not an object, skipped");
                continue;
            }
            String name = baseName(text(item, "filename"));
            if (name == null) {
                name = defaultFilename;
            }
            if (name == null) {
                warnings.add("entry " + n + " has no filename, skipped");
                continue;
            }
            Integer index = null;
            String rawIndex = text(item, "index");
            if (rawIndex != null) {
                try {
                    index = Integer.valueOf(rawIndex);
                } catch (NumberFormatException ex) {
                    index = 0;
                }
                if (index < 1) {
                    warnings.add("entry " + n + " ('" + name + "') has an invalid index '" + rawIndex + "', skipped");
                    continue;
                }
            }
            String start = text(item, "start");
            if (start != null) {
                long ms = parseTime(start);
                if (ms < 0) {
                    warnings.add("entry " + n + " ('" + name + "') has an invalid start '" + start + "', skipped");
                    continue;
                }
                start = formatTime(ms);
            }
            Entry e = new Entry(name, text(item, "author"), text(item, "partOf"), text(item, "title"),
                    text(item, "transcriber"), index, start);
            String label = name + (index != null ? "#" + index : start != null ? " @" + start : "");
            if (byKey.putIfAbsent(e.matchKey(), e) != null) {
                warnings.add("duplicate entry for '" + label + "' ignored (the first one wins)");
            }
        }
        return new Result(new ArrayList<>(byKey.values()), warnings, null, hash);
    }

    private static final Pattern TIME = Pattern.compile("^(\\d{1,9}(?::\\d{1,2}){0,2})(?:[.,](\\d{1,9}))?$");

    /**
     * Parses {@code [[h:]m:]s[.fff]} (so {@code 03:27.500}, {@code 1:02:03.250} and {@code 12.5} are accepted) into
     * milliseconds; -1 when malformed.
     */
    public static long parseTime(String s) {
        if (s == null) {
            return -1;
        }
        Matcher m = TIME.matcher(s.strip());
        if (!m.matches()) {
            return -1;
        }
        long secs = 0;
        for (String part : m.group(1).split(":")) {
            secs = secs * 60 + Long.parseLong(part);
        }
        long frac = 0;
        if (m.group(2) != null) {
            frac = Long.parseLong((m.group(2) + "00").substring(0, 3));
        }
        return secs * 1000 + frac;
    }

    /** {@code mm:ss.fff} (minutes are not limited to 59). */
    public static String formatTime(long millis) {
        long ms = Math.max(0, millis);
        return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000);
    }

    private static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        int off = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, b.length - off))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(b, off, b.length - off, WINDOWS_1252);   // legacy files
        }
    }

    private static String text(JsonNode o, String field) {
        JsonNode v = o.get(field);
        if (v == null) {
            // field names are matched case-insensitively too ("Title", "PartOf")
            for (var it = o.fieldNames(); it.hasNext();) {
                String f = it.next();
                if (f.equalsIgnoreCase(field)) {
                    v = o.get(f);
                    break;
                }
            }
        }
        if (v == null || v.isNull() || v.isContainerNode()) {
            return null;
        }
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static String baseName(String name) {
        if (name == null) {
            return null;
        }
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String b = cut >= 0 ? name.substring(cut + 1) : name;
        return b.isBlank() ? null : b.strip();
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    /** Short content hash used to notice changes of the file. */
    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The tags with the entry's fields merged over them (see the class comment). */
    public static TrackTags apply(TrackTags tags, Entry entry) {
        if (entry == null || entry.isBlank()) {
            return tags;
        }
        String artist = entry.author() != null ? entry.author() : tags.artist();
        return new TrackTags(entry.title() != null ? entry.title() : tags.title(), artist,
                entry.partOf() != null ? entry.partOf() : tags.album(), tags.albumArtist(), tags.genre(),
                tags.year(), tags.trackNo(), tags.discNo(), tags.discTotal(),
                entry.author() != null ? List.of() : tags.artists(), tags.albumArtists(), tags.genres(),
                tags.composer(), tags.composers(),
                entry.transcriber() != null ? entry.transcriber() : tags.transcriber(), tags.extras());
    }

    /** Merges the entry of the file's sidecar, else of its directory list.json (read from disk) over {@code tags}. */
    public static TrackTags applyFromDisk(Path file, TrackTags tags) {
        Path dir = file.toAbsolutePath().getParent();
        Result sidecar = SidecarJson.read(file);
        Result r = sidecar.entries().isEmpty() ? forDirectory(dir) : sidecar;
        return apply(tags, r.find(file.getFileName().toString()).orElse(null));
    }
}
