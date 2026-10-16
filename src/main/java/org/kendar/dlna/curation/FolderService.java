package org.kendar.dlna.curation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.ingestion.CatalogService;
import org.kendar.dlna.ingestion.SettingsParser;
import org.kendar.dlna.shared.Json;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Directory edits: {@code settings.json} (validated, written atomically, catalog updated), album sync to and from
 * the tags of the directory's own tracks, and front/rear art upload.
 */
public final class FolderService {

    public static final String SETTINGS_FILE = "settings.json";
    public static final int MAX_ART_BYTES = 10 * 1024 * 1024;
    private static final int MAX_RANDOM = 100_000;
    static final String DELETED_DIR = ".deleted";
    static final int MAX_DISPLAY_NAME = 255;

    public enum ArtKind {
        FRONT("front.jpg"), REAR("rear.jpg");

        private final String fileName;

        ArtKind(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }

        public static Optional<ArtKind> parse(String s) {
            if (s == null) {
                return Optional.empty();
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "front" -> Optional.of(FRONT);
                case "rear" -> Optional.of(REAR);
                default -> Optional.empty();
            };
        }
    }

    /** One value found in the tags and the files that carry it. */
    public record ConflictValue(String value, List<String> files) {
    }

    /** A field whose value differs between the tracks of the directory. */
    public record SyncConflict(String field, List<ConflictValue> values) {
    }

    /** Result of "from tags": either the new settings, or the conflicts (and then nothing was written). */
    public record FromTagsOutcome(DirectorySettings settings, List<SyncConflict> conflicts) {
        public boolean ok() {
            return conflicts.isEmpty();
        }
    }

    /** Result of "to tags". {@code skipped} are tracks whose format cannot hold album tags (MIDI, Opus, raw .aac). */
    public record ToTagsOutcome(List<TagService.Result> results, List<String> skipped) {
    }

    private final CatalogService catalog;
    private final LibraryFiles files;
    private final TagService tags;
    private final ListService lists;

    public FolderService(CatalogService catalog, LibraryFiles files, TagService tags) {
        this.catalog = catalog;
        this.files = files;
        this.tags = tags;
        this.lists = new ListService(catalog, files, tags.reader());
    }

    /** The editor of the directories' {@code list.json} files. */
    public ListService lists() {
        return lists;
    }

    // ---- settings ----

    /**
     * Validates and stores the settings of a directory. {@code body} has the shape of {@code settings.json}
     * (unknown keys of an existing valid file are preserved).
     */
    public synchronized Folder saveSettings(RootRef ref, JsonNode body) {
        Folder folder = folder(ref);
        if (body == null || !body.isObject()) {
            throw new CurationException(CurationException.INVALID, "Settings must be a JSON object");
        }
        SettingsParser.Result parsed = SettingsParser.parse(body.toString());
        if (parsed.error() != null) {
            throw new CurationException(CurationException.INVALID, parsed.error());
        }
        DirectorySettings next = parsed.settings();
        // a client that does not know a field must not drop its stored value
        boolean keepWhole = body.has("keepWholeFiles") ? next.keepWholeFiles() : folder.settings().keepWholeFiles();
        Integer bucket = body.has("bucketThreshold") ? next.bucketThreshold() : folder.settings().bucketThreshold();
        boolean hidden = body.has("hidden") ? next.hidden() : folder.settings().hidden();
        List<String> ignore = body.has("ignore") ? next.ignore() : folder.settings().ignore();
        Integer subitems = body.has("subitems") ? next.subitems() : folder.settings().subitems();
        Integer ranges = body.has("ranges") ? next.ranges() : folder.settings().ranges();
        List<String> navigations = body.has("navigations") ? next.navigations() : folder.settings().navigations();
        if (keepWhole != next.keepWholeFiles() || !java.util.Objects.equals(bucket, next.bucketThreshold())
                || hidden != next.hidden() || !ignore.equals(next.ignore())
                || !java.util.Objects.equals(subitems, next.subitems())
                || !java.util.Objects.equals(ranges, next.ranges())
                || !java.util.Objects.equals(navigations, next.navigations())) {
            next = new DirectorySettings(next.sort(), next.order(), next.album(), next.random(), next.views(),
                    next.recent(), next.displayName(), keepWhole, bucket, hidden, ignore, subitems, ranges,
                    navigations);
        }
        validate(next);
        return store(folder, next);
    }

    static void validate(DirectorySettings s) {
        List<String> problems = new ArrayList<>();
        for (String name : s.order()) {
            if (name.isBlank() || name.contains("/") || name.contains("\\")) {
                problems.add("order entry is not a plain file or directory name: '" + name + "'");
            }
        }
        if (s.random() > MAX_RANDOM) {
            problems.add("random must be at most " + MAX_RANDOM);
        }
        if (s.recent() > MAX_RANDOM) {
            problems.add("recent must be at most " + MAX_RANDOM);
        }
        if (s.displayName() != null && s.displayName().length() > MAX_DISPLAY_NAME) {
            problems.add("displayName must be at most " + MAX_DISPLAY_NAME + " characters");
        }
        AlbumInfo a = s.album();
        range("album.year", a.year(), 0, 9999, problems);
        range("album.discNumber", a.discNumber(), 0, 999, problems);
        range("album.discTotal", a.discTotal(), 0, 999, problems);
        if (!problems.isEmpty()) {
            throw new CurationException(CurationException.INVALID, String.join("; ", problems), problems);
        }
    }

    private static void range(String name, Integer v, int min, int max, List<String> problems) {
        if (v != null && (v < min || v > max)) {
            problems.add(name + " must be between " + min + " and " + max);
        }
    }

    private Folder store(Folder folder, DirectorySettings settings) {
        Path dir = directory(folder.ref());
        Path file = dir.resolve(SETTINGS_FILE);
        try {
            JsonNode existing = null;
            if (Files.isRegularFile(file)) {
                try {
                    existing = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException e) {
                    existing = null;   // unreadable or malformed: replaced
                }
            }
            AtomicFiles.write(file, render(existing, settings).getBytes(StandardCharsets.UTF_8));
            long mtime = Files.getLastModifiedTime(dir).toMillis();
            Folder updated = new Folder(folder.ref(), mtime, settings, null, folder.hasFront(), folder.hasRear(),
                    folder.listHash(), folder.listError());
            replaceFolder(updated);
            return updated;
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot write " + SETTINGS_FILE + " of " + folder.ref() + ": " + e.getMessage(), null, e);
        }
    }

    /** The settings.json text for {@code settings}, keeping unknown keys of {@code existing}. */
    static String render(JsonNode existing, DirectorySettings s) throws IOException {
        ObjectNode out = existing != null && existing.isObject() ? (ObjectNode) existing.deepCopy()
                : Json.mapper().createObjectNode();
        out.put("sort", s.sort().name().toLowerCase(Locale.ROOT));
        if (s.order().isEmpty()) {
            out.remove("order");
        } else {
            ArrayNode order = out.putArray("order");
            s.order().forEach(order::add);
        }
        AlbumInfo a = s.album();
        if (a.isEmpty()) {
            out.remove("album");
        } else {
            ObjectNode album = out.putObject("album");
            putIfSet(album, "title", a.title());
            putIfSet(album, "albumArtist", a.albumArtist());
            putIfSet(album, "year", a.year());
            putIfSet(album, "genre", a.genre());
            putIfSet(album, "discNumber", a.discNumber());
            putIfSet(album, "discTotal", a.discTotal());
        }
        if (s.random() > 0) {
            out.put("random", s.random());
        } else {
            out.remove("random");
        }
        if (s.recent() > 0) {
            out.put("recent", s.recent());
        } else {
            out.remove("recent");
        }
        if (s.bucketThreshold() == null) {
            out.remove("bucketThreshold");
        } else {
            out.put("bucketThreshold", s.bucketThreshold());
        }
        if (s.subitems() == null) {
            out.remove("subitems");
        } else {
            out.put("subitems", s.subitems());
        }
        if (s.ranges() == null) {
            out.remove("ranges");
        } else {
            out.put("ranges", s.ranges());
        }
        if (s.navigations() == null) {
            out.remove("navigations");
        } else {
            ArrayNode navs = out.putArray("navigations");
            s.navigations().forEach(navs::add);
        }
        if (s.hidden()) {
            out.put("hidden", true);
        } else {
            out.remove("hidden");
        }
        if (s.ignore().isEmpty()) {
            out.remove("ignore");
        } else {
            ArrayNode ignore = out.putArray("ignore");
            s.ignore().forEach(ignore::add);
        }
        if (s.keepWholeFiles()) {
            out.put("keepWholeFiles", true);
        } else {
            out.remove("keepWholeFiles");
        }
        if (s.displayName() == null) {
            out.remove("displayName");
        } else {
            out.put("displayName", s.displayName());
        }
        if (s.views().isEmpty()) {
            out.remove("views");
        } else {
            ArrayNode views = out.putArray("views");
            s.views().forEach(v -> views.add(v.name().toLowerCase(Locale.ROOT)));
        }
        return Json.mapper().writeValueAsString(out) + "\n";
    }

    private static void putIfSet(ObjectNode o, String key, String v) {
        if (v != null) {
            o.put(key, v);
        }
    }

    private static void putIfSet(ObjectNode o, String key, Integer v) {
        if (v != null) {
            o.put(key, v);
        }
    }

    // ---- sync ----

    /** Writes the {@code album} fields of the directory into every track of the directory. */
    public synchronized ToTagsOutcome syncToTags(RootRef ref) {
        return syncToTags(ref, null);
    }

    /**
     * Writes the {@code album} fields into the chosen tracks of the directory.
     *
     * @param fileNames file names (as listed in the directory) to write; {@code null} = every editable track.
     *                  The admin UI always sends the explicit selection the user confirmed.
     */
    public synchronized ToTagsOutcome syncToTags(RootRef ref, java.util.Collection<String> fileNames) {
        Folder folder = folder(ref);
        AlbumInfo a = folder.settings().album();
        if (a.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "The directory has no album fields to apply");
        }
        TagEdit edit = new TagEdit(null, null, a.title(), a.albumArtist(), a.genre(), str(a.year()),
                null, str(a.discNumber()), str(a.discTotal()));
        List<Track> direct = directTracks(catalog.current(), ref);
        if (fileNames != null) {
            java.util.Set<String> known = new java.util.HashSet<>();
            direct.forEach(t -> known.add(t.fileName()));
            List<String> unknown = fileNames.stream().filter(n -> !known.contains(n)).toList();
            if (!unknown.isEmpty()) {
                throw new CurationException(CurationException.INVALID,
                        "Not tracks of this directory: " + String.join(", ", unknown));
            }
            if (fileNames.isEmpty()) {
                throw new CurationException(CurationException.INVALID, "No files selected");
            }
        }
        List<RootRef> editable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Track t : direct) {
            if (fileNames != null && !fileNames.contains(t.fileName())) {
                continue;
            }
            if (t.format().editableFields().contains("album")) {   // album-level fields: not MIDI, Opus, ADTS
                editable.add(t.ref());
            } else {
                skipped.add(t.fileName());
            }
        }
        if (editable.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "The directory has no tracks that can hold album tags "
                    + "(MIDI, Opus and raw .aac cannot)");
        }
        return new ToTagsOutcome(tags.write(editable, edit), skipped);
    }

    /**
     * Fills {@code album} from the tags of the directory's tracks. When the tracks disagree on a field nothing is
     * written and the conflicts are returned (field, the differing values, the files carrying each).
     */
    public synchronized FromTagsOutcome syncFromTags(RootRef ref) {
        return syncFromTags(ref, true);
    }

    /**
     * @param write false = preview only: the album that would be stored is returned, nothing is written (the UI
     *              fills its form with it and the user saves explicitly)
     */
    public synchronized FromTagsOutcome syncFromTags(RootRef ref, boolean write) {
        Folder folder = folder(ref);
        List<Track> tracks = directTracks(catalog.current(), ref);
        if (tracks.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "The directory has no tracks");
        }
        List<SyncConflict> conflicts = new ArrayList<>();
        String title = pick("album", tracks, t -> t.tags().album(), conflicts);
        String albumArtist = pick("albumArtist", tracks, t -> t.tags().albumArtist(), conflicts);
        String genre = pick("genre", tracks, t -> t.tags().genre(), conflicts);
        String year = pick("year", tracks, t -> str(t.tags().year()), conflicts);
        String disc = pick("discNumber", tracks, t -> str(t.tags().discNo()), conflicts);
        String discTotal = pick("discTotal", tracks, t -> str(t.tags().discTotal()), conflicts);
        if (!conflicts.isEmpty()) {
            return new FromTagsOutcome(folder.settings(), conflicts);
        }
        AlbumInfo album = new AlbumInfo(title, albumArtist, num(year), genre, num(disc), num(discTotal));
        DirectorySettings cur = folder.settings();
        DirectorySettings next = new DirectorySettings(cur.sort(), cur.order(), album, cur.random(), cur.views(),
                cur.recent(), cur.displayName(), cur.keepWholeFiles(), cur.bucketThreshold(), cur.hidden(),
                cur.ignore()).withExtrasOf(cur);
        if (write) {
            store(folder, next);
        }
        return new FromTagsOutcome(next, List.of());
    }

    private static String pick(String field, List<Track> tracks, java.util.function.Function<Track, String> get,
            List<SyncConflict> conflicts) {
        Map<String, ConflictValue> byKey = new LinkedHashMap<>();
        Map<String, List<String>> filesByKey = new LinkedHashMap<>();
        for (Track t : tracks) {
            String v = get.apply(t);
            if (v == null || v.isBlank()) {
                continue;
            }
            String key = Normalizer.normalize(v);
            byKey.putIfAbsent(key, new ConflictValue(v.strip(), null));
            filesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t.fileName());
        }
        if (byKey.size() > 1) {
            List<ConflictValue> values = new ArrayList<>();
            byKey.forEach((k, v) -> values.add(new ConflictValue(v.value(), List.copyOf(filesByKey.get(k)))));
            conflicts.add(new SyncConflict(field, values));
            return null;
        }
        return byKey.isEmpty() ? null : byKey.values().iterator().next().value();
    }

    private static String str(Integer i) {
        return i == null ? null : Integer.toString(i);
    }

    private static Integer num(String s) {
        return s == null ? null : Integer.valueOf(s);
    }

    private static List<Track> directTracks(Snapshot snap, RootRef folder) {
        List<Track> out = new ArrayList<>();
        for (Child c : snap.children(folder)) {
            if (c instanceof Child.TrackChild t) {
                out.add(t.track());
            }
        }
        return out;
    }

    // ---- art ----

    /**
     * Stores an uploaded picture as {@code front.jpg} / {@code rear.jpg}. The data must be a decodable image
     * (JPEG is stored as it is, other formats are converted).
     */
    public synchronized Folder saveArt(RootRef ref, ArtKind kind, byte[] data) {
        Folder folder = folder(ref);
        if (data == null || data.length == 0) {
            throw new CurationException(CurationException.INVALID, "Empty upload");
        }
        if (data.length > MAX_ART_BYTES) {
            throw new CurationException(CurationException.INVALID,
                    "Image is larger than " + MAX_ART_BYTES / 1024 / 1024 + " MB");
        }
        byte[] jpeg = toJpeg(data);
        Path target = directory(ref).resolve(kind.fileName());
        try {
            AtomicFiles.write(target, jpeg);
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot write " + kind.fileName() + ": " + e.getMessage(), null, e);
        }
        Folder updated = new Folder(folder.ref(), folder.mtime(), folder.settings(), folder.settingsError(),
                kind == ArtKind.FRONT || folder.hasFront(), kind == ArtKind.REAR || folder.hasRear(),
                folder.listHash(), folder.listError());
        replaceFolder(updated);
        return updated;
    }

    /** The art file of the directory when it exists. */
    public Optional<Path> artFile(RootRef ref, ArtKind kind) {
        Folder folder = folder(ref);
        boolean present = kind == ArtKind.FRONT ? folder.hasFront() : folder.hasRear();
        if (!present) {
            return Optional.empty();
        }
        Path dir = directory(ref);
        java.util.List<String> names = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(c -> names.add(c.getFileName().toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
        Optional<String> best = kind == ArtKind.FRONT ? org.kendar.dlna.shared.ArtNames.pickFront(names)
                : org.kendar.dlna.shared.ArtNames.pickRear(names);
        return best.map(dir::resolve).filter(Files::isRegularFile);
    }

    static byte[] toJpeg(byte[] data) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            img = null;
        }
        if (img == null) {
            throw new CurationException(CurationException.INVALID, "The file is not a readable image");
        }
        boolean isJpeg = data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF;
        if (isJpeg) {
            return data;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(img, 0, 0, java.awt.Color.WHITE, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(rgb, "jpg", out)) {
                throw new CurationException(CurationException.INVALID, "Cannot convert the image to JPEG");
            }
        } catch (IOException e) {
            throw new CurationException(CurationException.INVALID, "Cannot convert the image to JPEG", null, e);
        }
        return out.toByteArray();
    }

    // ---- export ----

    /** The settings.json content of every directory that has non-default settings, keyed by {@code root/path}. */
    public synchronized ObjectNode exportSettings() {
        ObjectNode out = Json.mapper().createObjectNode();
        for (Folder f : catalog.current().allFolders()) {
            if (f.settingsError() != null || f.settings().equals(DirectorySettings.DEFAULT)) {
                continue;
            }
            try {
                out.set(f.ref().qualified(), Json.mapper().readTree(render(null, f.settings())));
            } catch (IOException e) {
                throw new CurationException(CurationException.IO_ERROR,
                        "Cannot export settings of " + f.ref() + ": " + e.getMessage(), null, e);
            }
        }
        return out;
    }

    // ---- rename ----

    /**
     * Renames one audio file of the library (same directory, same extension). The sidecar, the {@code list.json}
     * entries and the directory's {@code order} follow, and the catalog is updated in place (cue / chapter tracks
     * of the file included). Returns the new location.
     */
    public synchronized RootRef renameFile(RootRef ref, String newName) {
        Track old = catalog.current().track(ref)
                .orElseThrow(() -> new CurationException(CurationException.NOT_FOUND, "Unknown track: " + ref));
        if (old.isSegment()) {
            throw new CurationException(CurationException.NOT_EDITABLE,
                    "A cue sheet or chapter track is not a file of its own, rename the file: " + ref);
        }
        String oldName = ref.name();
        String name = validName(newName);
        if (!extension(name).equalsIgnoreCase(extension(oldName))) {
            throw new CurationException(CurationException.INVALID,
                    "The extension must stay '" + extension(oldName) + "'");
        }
        if (name.equals(oldName)) {
            return ref;
        }
        RootRef target = ref.parent().child(name);
        Path from = files.resolve(ref);
        Path to = files.resolve(target);
        if (from == null || to == null || !Files.isRegularFile(from)) {
            throw new CurationException(CurationException.NOT_FOUND, "File is missing on disk: " + ref);
        }
        boolean caseOnly = name.equalsIgnoreCase(oldName);
        if (!caseOnly && (Files.exists(to) || catalog.current().track(target).isPresent())) {
            throw new CurationException(CurationException.CONFLICT, "A file named '" + name + "' already exists");
        }
        try {
            Optional<Path> sidecar = org.kendar.dlna.ingestion.SidecarJson.locate(from);
            Files.move(from, to);
            if (sidecar.isPresent()) {
                Files.move(sidecar.get(), org.kendar.dlna.ingestion.SidecarJson.path(to));
            }
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot rename " + ref + ": " + e.getMessage(), null, e);
        }
        renameInListJson(to.toAbsolutePath().getParent(), oldName, name);
        renameInOrder(ref.parent(), oldName, name);
        String oldRel = ref.rel();
        String newRel = target.rel();
        catalog.update(cur -> {
            List<Track> tracks = new ArrayList<>(cur.tracks());
            tracks.replaceAll(t -> {
                String rel = t.ref().rel();
                if (!t.ref().root().equals(ref.root()) || !(rel.equals(oldRel) || rel.startsWith(oldRel + "#"))) {
                    return t;
                }
                RootRef moved = new RootRef(ref.root(), newRel + rel.substring(oldRel.length()));
                return new Track(moved, t.mtime(), t.size(), t.format(), t.tags(), t.durationMillis(),
                        t.bitrateKbps(), t.sampleRate(), t.channels(), t.embeddedArt(), t.sampleCount(),
                        t.renderedMillis(), t.addedAt(), t.segment());
            });
            return CatalogEdits.withTracks(cur, tracks);
        });
        return target;
    }

    /** A plain file or directory name (NFC, no separators or characters Windows rejects); throws otherwise. */
    static String validName(String raw) {
        String name = raw == null ? "" : java.text.Normalizer.normalize(raw.strip(), java.text.Normalizer.Form.NFC);
        if (name.isEmpty() || name.length() > MAX_DISPLAY_NAME || name.startsWith(".") || name.endsWith(".")
                || name.chars().anyMatch(c -> c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0)) {
            throw new CurationException(CurationException.INVALID, "Invalid name: " + raw);
        }
        return name;
    }

    // ---- upload, delete, directories ----

    /** Stores an uploaded audio file in a directory (never overwrites); the caller rescans the directory. */
    public synchronized RootRef uploadFile(RootRef dir, String fileName, byte[] data) {
        directory(dir);
        String name = validName(fileName);
        if (org.kendar.dlna.catalog.Format.fromFileName(name).isEmpty()) {
            throw new CurationException(CurationException.INVALID, "Not a supported audio file: " + name);
        }
        if (data == null || data.length == 0) {
            throw new CurationException(CurationException.INVALID, "Empty upload");
        }
        RootRef target = dir.child(name);
        Path to = files.resolve(target);
        if (to == null || Files.exists(to)) {
            throw new CurationException(CurationException.CONFLICT, "A file named '" + name + "' already exists");
        }
        try {
            AtomicFiles.write(to, data);
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot write " + target + ": " + e.getMessage(), null, e);
        }
        return target;
    }

    /** Deletes an audio file and its sidecar, and drops it (and its cue / chapter tracks) from the catalog. */
    public synchronized void deleteFile(RootRef ref) {
        Track t = catalog.current().track(ref)
                .orElseThrow(() -> new CurationException(CurationException.NOT_FOUND, "Unknown track: " + ref));
        if (t.isSegment()) {
            throw new CurationException(CurationException.NOT_EDITABLE,
                    "A cue sheet or chapter track is not a file of its own, delete the file: " + ref);
        }
        Path file = files.resolve(ref);
        if (file == null || !Files.isRegularFile(file)) {
            throw new CurationException(CurationException.NOT_FOUND, "File is missing on disk: " + ref);
        }
        try {
            org.kendar.dlna.ingestion.SidecarJson.locate(file).ifPresent(side -> {
                try {
                    moveToDeleted(side);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            moveToDeleted(file);
        } catch (IOException | java.io.UncheckedIOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot delete " + ref + ": " + e.getMessage(), null, e);
        }
        String rel = ref.rel();
        catalog.update(cur -> {
            List<Track> tracks = new ArrayList<>(cur.tracks());
            tracks.removeIf(x -> x.ref().root().equals(ref.root())
                    && (x.ref().rel().equals(rel) || x.ref().rel().startsWith(rel + "#")));
            return CatalogEdits.withTracks(cur, tracks);
        });
    }

    /** Creates a sub-directory; the caller rescans the parent so it enters the catalog. */
    public synchronized RootRef createDirectory(RootRef parent, String name) {
        directory(parent);
        String clean = validName(name);
        RootRef target = parent.child(clean);
        Path to = files.resolve(target);
        if (to == null || Files.exists(to)) {
            throw new CurationException(CurationException.CONFLICT, "'" + clean + "' already exists");
        }
        try {
            Files.createDirectory(to);
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot create " + target + ": " + e.getMessage(), null, e);
        }
        return target;
    }

    /**
     * What a recursive delete of {@code ref} would remove: catalog tracks (songs, cue/chapter segments count as
     * songs), every other file on disk (art, lists, settings, unscanned files) and sub-directories. The trash
     * directory is not counted.
     */
    public record DeletePreview(int tracks, int otherFiles, int folders) {
    }

    public synchronized DeletePreview previewDelete(RootRef ref) {
        if (ref.isRoot()) {
            throw new CurationException(CurationException.INVALID, "A library root cannot be removed");
        }
        Path dir = directory(ref);
        List<Track> subtree = catalog.current().subtreeTracks(ref);
        java.util.Set<String> trackFiles = new java.util.HashSet<>();
        for (Track t : subtree) {
            String rel = t.ref().rel();
            int hash = rel.indexOf('#');
            trackFiles.add(hash < 0 ? rel : rel.substring(0, hash));
        }
        int[] counts = new int[2];
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                    if (!d.equals(dir)) {
                        if (d.getFileName().toString().equals(DELETED_DIR)) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                        counts[1]++;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                    counts[0]++;
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot read " + ref + ": " + e.getMessage(), null, e);
        }
        return new DeletePreview(subtree.size(), Math.max(0, counts[0] - trackFiles.size()), counts[1]);
    }

    /**
     * Removes a directory. A non-empty one needs {@code recursive} (everything below it is deleted); a library root
     * cannot be removed. Returns the parent, which the caller rescans.
     */
    public synchronized RootRef deleteDirectory(RootRef ref, boolean recursive) {
        if (ref.isRoot()) {
            throw new CurationException(CurationException.INVALID, "A library root cannot be removed");
        }
        Path dir = directory(ref);
        try (java.util.stream.Stream<Path> kids = Files.list(dir)) {
            if (!recursive && kids.anyMatch(k -> !k.getFileName().toString().equals(DELETED_DIR))) {
                throw new CurationException(CurationException.CONFLICT, "The directory is not empty");
            }
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot read " + ref + ": " + e.getMessage(), null, e);
        }
        try {
            moveToDeleted(dir);
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot remove " + ref + ": " + e.getMessage(), null, e);
        }
        return ref.parent();
    }

    /**
     * "Deletes" a file or directory by moving it into the {@value #DELETED_DIR} directory of its parent. Dot
     * directories are never scanned, so nothing in there reaches a user or a DLNA client; the admin can restore
     * or purge it on disk. A clashing name gets a timestamp suffix.
     */
    private static void moveToDeleted(Path p) throws IOException {
        Path bin = p.getParent().resolve(DELETED_DIR);
        Files.createDirectories(bin);
        String name = p.getFileName().toString();
        Path target = bin.resolve(name);
        if (Files.exists(target)) {
            target = bin.resolve(name + "." + System.currentTimeMillis());
            for (int i = 1; Files.exists(target); i++) {
                target = bin.resolve(name + "." + System.currentTimeMillis() + "-" + i);
            }
        }
        Files.move(p, target);
    }

    private static String extension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i);
    }

    private void renameInListJson(Path dir, String oldName, String newName) {
        try {
            Path list = org.kendar.dlna.ingestion.ListJson.locate(dir).orElse(null);
            if (list == null) {
                return;
            }
            String oldKey = org.kendar.dlna.ingestion.ListJson.key(oldName);
            List<org.kendar.dlna.ingestion.ListJson.Entry> entries = new ArrayList<>(
                    org.kendar.dlna.ingestion.ListJson.parseFile(list).entries());
            boolean changed = false;
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                String fn = e.filename() == null ? "" : e.filename();
                String k = org.kendar.dlna.ingestion.ListJson.key(fn);
                if (k.equals(oldKey) || k.startsWith(oldKey + "#")) {
                    entries.set(i, new org.kendar.dlna.ingestion.ListJson.Entry(
                            newName + fn.substring(Math.min(fn.length(), oldName.length())), e.author(), e.partOf(),
                            e.title(), e.transcriber(), e.index(), e.start()));
                    changed = true;
                }
            }
            if (changed) {
                AtomicFiles.write(list, ListService.render(entries).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "File renamed, but list.json could not be updated: " + e.getMessage(), null, e);
        }
    }

    private void renameInOrder(RootRef dirRef, String oldName, String newName) {
        Folder folder = catalog.current().folder(dirRef).orElse(null);
        if (folder == null || !folder.settings().order().contains(oldName)) {
            return;
        }
        DirectorySettings s = folder.settings();
        List<String> order = new ArrayList<>(s.order());
        order.replaceAll(n -> n.equals(oldName) ? newName : n);
        store(folder, new DirectorySettings(s.sort(), order, s.album(), s.random(), s.views(), s.recent(),
                s.displayName(), s.keepWholeFiles(), s.bucketThreshold(), s.hidden(), s.ignore()).withExtrasOf(s));
    }

    // ---- helpers ----

    private Folder folder(RootRef ref) {
        return catalog.current().folder(ref)
                .orElseThrow(() -> new CurationException(CurationException.NOT_FOUND, "Unknown directory: " + ref));
    }

    private Path directory(RootRef ref) {
        Path dir = files.resolve(ref);
        if (dir == null || !Files.isDirectory(dir)) {
            throw new CurationException(CurationException.NOT_FOUND, "Directory is missing on disk: " + ref);
        }
        return dir;
    }

    private void replaceFolder(Folder updated) {
        catalog.update(cur -> {
            List<Folder> folders = new ArrayList<>(cur.allFolders());
            folders.replaceAll(f -> f.ref().equals(updated.ref()) ? updated : f);
            return CatalogEdits.withFolders(cur, folders);
        });
    }
}
