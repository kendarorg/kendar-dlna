package org.kendar.dlna.curation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.ingestion.CatalogService;
import org.kendar.dlna.ingestion.DirectoryLists;
import org.kendar.dlna.ingestion.ListGenerator;
import org.kendar.dlna.ingestion.ListJson;
import org.kendar.dlna.ingestion.SidecarJson;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.ingestion.TrackMeta;
import org.kendar.dlna.shared.Json;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads and edits a directory's {@code list.json} (see {@link ListJson}): validated, written atomically, with an
 * optimistic-concurrency {@code version} (the content hash the editor was based on; a stale one is a conflict).
 * After a write the direct tracks of the directory are re-read and the new entries merged, so the change is
 * effective at once; an externally edited file is picked up by the next scan.
 */
public final class ListService {

    private static final Logger LOG = Logger.getLogger(ListService.class.getName());

    public static final int MAX_ENTRIES = 20_000;
    public static final int MAX_FIELD = 500;
    /** The {@code version} of a directory that has no list.json yet. */
    public static final String NO_FILE = "none";

    /** What the editor needs: the entries, the directory's audio files and which of them have no entry. */
    public record View(boolean exists, String version, List<ListJson.Entry> entries, List<String> files,
            List<String> missing, List<String> unknown, List<String> warnings, String error,
            List<String> sidecars) {
    }

    /**
     * The sections list of one audio file ({@code <file>.json}): {@code split} is true when the catalog holds the
     * file as a cue / chapter virtual album.
     */
    public record FileView(boolean exists, String version, String fileName, String sidecar, boolean split,
            List<ListJson.Entry> entries, List<String> warnings, String error) {
    }

    private final CatalogService catalog;
    private final LibraryFiles files;
    private final TagReader reader;

    public ListService(CatalogService catalog, LibraryFiles files, TagReader reader) {
        this.catalog = catalog;
        this.files = files;
        this.reader = reader;
    }

    public synchronized View read(RootRef ref) {
        Folder folder = folder(ref);
        Path dir = directory(ref);
        return view(folder, ListJson.forDirectory(dir));
    }

    /**
     * Preview only: the entries that the cue sheets and embedded chapters of the directory's audio files would give
     * (see {@link ListGenerator}). Nothing is written and the current list.json is not consulted.
     */
    public synchronized ListGenerator.Result generate(RootRef ref) {
        folder(ref);
        return ListGenerator.generate(ref, directory(ref), reader);
    }

    /**
     * Replaces the entries of the directory's list.json. Entries without any metadata are dropped; an empty result
     * deletes the file. File names are matched case-insensitively against the directory and stored with the
     * on-disk spelling.
     *
     * @param expectedVersion the {@code version} of the last read ({@code null} = overwrite whatever is there)
     */
    public synchronized View save(RootRef ref, List<ListJson.Entry> entries, String expectedVersion) {
        Folder folder = folder(ref);
        Path dir = directory(ref);
        Path existing = ListJson.locate(dir).orElse(null);
        ListJson.Result current = existing == null ? ListJson.Result.NONE : ListJson.parseFile(existing);
        String version = current.hash() == null ? NO_FILE : current.hash();
        if (expectedVersion != null && !expectedVersion.equals(version)) {
            throw new CurationException(CurationException.CONFLICT,
                    "list.json changed since it was loaded; reload it and apply the changes again",
                    Map.of("version", version));
        }
        List<ListJson.Entry> clean = validate(dir, entries, current);
        Path target = existing != null ? existing : dir.resolve(ListJson.FILE_NAME);
        try {
            if (clean.isEmpty()) {
                Files.deleteIfExists(target);
            } else {
                AtomicFiles.write(target, render(clean).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot write " + ListJson.FILE_NAME + " of " + ref + ": " + e.getMessage(), null, e);
        }
        ListJson.Result written = clean.isEmpty() ? ListJson.Result.NONE : ListJson.parseFile(target);
        apply(folder, dir, DirectoryLists.read(dir), null);
        return view(catalog.current().folder(ref).orElse(folder), written);
    }

    // ---- per-file sections list (<file>.json) ----

    public synchronized FileView readFile(RootRef ref) {
        Path file = audioFile(ref);
        return fileView(ref, file, SidecarJson.read(file));
    }

    /** Preview only: the entries the file's cue sheet / chapters give (see {@link ListGenerator}). */
    public synchronized ListGenerator.Result generateFile(RootRef ref) {
        Path file = audioFile(ref);
        return ListGenerator.generateFile(ref.parent(), file.getParent(), file.getFileName().toString(), reader);
    }

    /**
     * Replaces the entries of the file's sidecar; same rules as {@link #save}, but every entry is about this file
     * (a blank {@code filename} means the file, another one is invalid). An empty result deletes the sidecar.
     */
    public synchronized FileView saveFile(RootRef ref, List<ListJson.Entry> entries, String expectedVersion) {
        Path file = audioFile(ref);
        Path dir = file.getParent();
        String name = file.getFileName().toString();
        Path existing = SidecarJson.locate(file).orElse(null);
        ListJson.Result current = existing == null ? ListJson.Result.NONE : SidecarJson.parseFile(existing, name);
        String version = current.hash() == null ? NO_FILE : current.hash();
        if (expectedVersion != null && !expectedVersion.equals(version)) {
            throw new CurationException(CurationException.CONFLICT,
                    name + SidecarJson.SUFFIX + " changed since it was loaded; reload it and apply the changes again",
                    Map.of("version", version));
        }
        if (entries == null) {
            throw new CurationException(CurationException.INVALID, "entries are required");
        }
        List<String> problems = new ArrayList<>();
        List<ListJson.Entry> mine = new ArrayList<>();
        int n = 0;
        for (ListJson.Entry e : entries) {
            n++;
            if (e == null) {
                continue;
            }
            String given = blank(e.filename());
            if (given != null && !ListJson.key(given).equals(ListJson.key(name))) {
                problems.add("entry " + n + ": a sections file describes only '" + name + "', not '" + given + "'");
                continue;
            }
            mine.add(new ListJson.Entry(name, e.author(), e.partOf(), e.title(), e.transcriber(), e.index(),
                    e.start()));
        }
        if (!problems.isEmpty()) {
            throw new CurationException(CurationException.INVALID, String.join("; ", problems), problems);
        }
        List<ListJson.Entry> clean = validate(dir, mine, current);
        Path target = existing != null ? existing : SidecarJson.path(file);
        try {
            if (clean.isEmpty()) {
                Files.deleteIfExists(target);
            } else {
                AtomicFiles.write(target, render(clean).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot write " + target.getFileName() + " of " + ref + ": " + e.getMessage(), null, e);
        }
        apply(folder(ref.parent()), dir, DirectoryLists.read(dir), Set.of(ListJson.key(name)));
        return fileView(ref, file, SidecarJson.read(file));
    }

    private FileView fileView(RootRef ref, Path file, ListJson.Result sc) {
        boolean split = catalog.current().folder(ref).map(Folder::isVirtual).orElse(false);
        return new FileView(sc.exists(), sc.hash() == null ? NO_FILE : sc.hash(), file.getFileName().toString(),
                SidecarJson.locate(file).map(p -> p.getFileName().toString())
                        .orElse(file.getFileName().toString() + SidecarJson.SUFFIX),
                split, sc.entries(), sc.warnings(), sc.error());
    }

    /** The audio file {@code ref} of the catalog (a track or a cue / chapter split file), resolved on disk. */
    private Path audioFile(RootRef ref) {
        if (ref.isRoot()) {
            throw new CurationException(CurationException.NOT_FOUND, "Unknown file: " + ref);
        }
        boolean known = catalog.current().track(ref).isPresent()
                || catalog.current().folder(ref).map(Folder::isVirtual).orElse(false);
        Path file = known ? files.resolve(ref) : null;
        if (file == null || !Files.isRegularFile(file) || Format.fromFileName(ref.name()).isEmpty()) {
            throw new CurationException(CurationException.NOT_FOUND, "Unknown audio file: " + ref);
        }
        return file;
    }

    // ---- validation and rendering ----

    private List<ListJson.Entry> validate(Path dir, List<ListJson.Entry> entries, ListJson.Result current) {
        if (entries == null) {
            throw new CurationException(CurationException.INVALID, "entries are required");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new CurationException(CurationException.INVALID, "At most " + MAX_ENTRIES + " entries");
        }
        Map<String, String> onDisk = filesOnDisk(dir);
        Set<String> alreadyListed = new HashSet<>(current.index().keySet());
        List<String> problems = new ArrayList<>();
        Map<String, ListJson.Entry> out = new LinkedHashMap<>();
        int n = 0;
        for (ListJson.Entry e : entries) {
            n++;
            String name = e == null || e.filename() == null ? "" : e.filename().strip();
            if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
                problems.add("entry " + n + ": filename must be a plain file name");
                continue;
            }
            for (String[] f : new String[][] { { "author", e.author() }, { "partOf", e.partOf() },
                    { "title", e.title() }, { "transcriber", e.transcriber() } }) {
                if (f[1] != null && f[1].length() > MAX_FIELD) {
                    problems.add("entry " + n + ": " + f[0] + " must be at most " + MAX_FIELD + " characters");
                }
            }
            if (e.index() != null && e.index() < 1) {
                problems.add("entry " + n + ": index must be 1 or more");
                continue;
            }
            String start = blank(e.start());
            if (start != null) {
                long ms = ListJson.parseTime(start);
                if (ms < 0) {
                    problems.add("entry " + n + ": start must look like mm:ss.fff");
                    continue;
                }
                start = ListJson.formatTime(ms);
            }
            String key = ListJson.key(name);
            String actual = onDisk.get(key);
            if (actual == null) {
                // an orphan that is already in the file stays editable (also a legacy "disc.flac#2" name)
                if (!alreadyListed.contains(key) && current.entries().stream()
                        .noneMatch(c -> ListJson.key(c.filename()).equals(key))) {
                    problems.add("entry " + n + ": no file '" + name + "' in this directory");
                    continue;
                }
                actual = name;
            }
            ListJson.Entry norm = new ListJson.Entry(actual, blank(e.author()), blank(e.partOf()), blank(e.title()),
                    blank(e.transcriber()), e.index(), start);
            String id = norm.matchKey();
            if (out.containsKey(id)) {
                problems.add("entry " + n + ": duplicate entry for '" + name + "'"
                        + (norm.index() != null ? " index " + norm.index() : start != null ? " start " + start : ""));
                continue;
            }
            if (!norm.isBlank()) {
                out.put(id, norm);
            }
        }
        if (!problems.isEmpty()) {
            throw new CurationException(CurationException.INVALID, String.join("; ", problems), problems);
        }
        return new ArrayList<>(out.values());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Map<String, String> filesOnDisk(Path dir) {
        Map<String, String> m = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    m.putIfAbsent(ListJson.key(p.getFileName().toString()), p.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR, "Cannot list " + dir + ": " + e.getMessage(), null,
                    e);
        }
        return m;
    }

    /** The file text: an array of objects with all five fields, 4-space indentation, like hand-written files. */
    static String render(List<ListJson.Entry> entries) throws IOException {
        ArrayNode arr = Json.mapper().createArrayNode();
        for (ListJson.Entry e : entries) {
            ObjectNode o = arr.addObject();
            o.put("filename", e.filename());
            if (e.index() != null) {
                o.put("index", e.index());
            }
            if (e.start() != null) {
                o.put("start", e.start());
            }
            o.put("author", e.author() == null ? "" : e.author());
            o.put("partOf", e.partOf() == null ? "" : e.partOf());
            o.put("title", e.title() == null ? "" : e.title());
            o.put("transcriber", e.transcriber() == null ? "" : e.transcriber());
        }
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter().withSeparators(Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        pp.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n").withIndent("    "));
        pp.indentObjectsWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n").withIndent("    "));
        return Json.mapper().writer(pp).writeValueAsString(arr) + "\n";
    }

    // ---- catalog ----

    private View view(Folder folder, ListJson.Result list) {
        Path dir = directory(folder.ref());
        List<Track> tracks = directTracks(folder.ref());
        Map<String, ListJson.Entry> index = list.index();
        List<String> names = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<String> trackKeys = new HashSet<>();
        for (Track t : tracks) {
            names.add(t.fileName());
            trackKeys.add(ListJson.key(t.fileName()));
            if (!index.containsKey(ListJson.key(t.fileName()))) {
                missing.add(t.fileName());
            }
        }
        // files split into virtual albums by a cue sheet / chapters are not tracks, but entries may address them
        Set<String> sourceKeys = new HashSet<>();
        for (Child c : catalog.current().children(folder.ref())) {
            if (c instanceof Child.FolderChild f && f.folder().isVirtual()) {
                names.add(f.folder().ref().name());
                sourceKeys.add(ListJson.key(f.folder().ref().name()));
            }
        }
        names.sort(Normalizer::compareNatural);
        List<String> unknown = list.entries().stream().map(ListJson.Entry::filename)
                .filter(n -> !trackKeys.contains(ListJson.key(n)) && !sourceKeys.contains(ListJson.key(n))
                        && !sourceKeys.contains(ListJson.key(n.replaceFirst("#\\d+$", ""))))
                .toList();
        return new View(list.exists(), list.hash() == null ? NO_FILE : list.hash(), list.entries(), names, missing,
                unknown, list.warnings(), list.error(), DirectoryLists.read(dir).sidecarFiles());
    }

    private List<Track> directTracks(RootRef ref) {
        List<Track> out = new ArrayList<>();
        for (Child c : catalog.current().children(ref)) {
            if (c instanceof Child.TrackChild t) {
                out.add(t.track());
            }
        }
        out.sort((a, b) -> Normalizer.compareNatural(a.fileName(), b.fileName()));
        return out;
    }

    /** Re-reads the embedded tags of the directory's tracks, merges {@code list} and commits the result. */
    private void apply(Folder folder, Path dir, DirectoryLists lists, Set<String> only) {
        Map<RootRef, Track> updated = new HashMap<>();
        for (Track t : directTracks(folder.ref())) {
            if (only != null && !only.contains(ListJson.key(t.fileName()))) {
                continue;
            }
            Path file = files.resolve(t.ref());
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                TrackMeta meta = reader.read(file, t.format());
                var tags = ListJson.apply(meta.tags(), lists.forFile(t.fileName()).find(t.fileName()).orElse(null));
                if (!tags.equals(t.tags())) {
                    updated.put(t.ref(), new Track(t.ref(), t.mtime(), t.size(), t.format(), tags,
                            t.durationMillis(), t.bitrateKbps(), t.sampleRate(), t.channels(), t.embeddedArt(),
                            t.sampleCount(), t.renderedMillis(), t.addedAt()));
                }
            } catch (TagReader.TagReadException | RuntimeException e) {
                LOG.warning("Cannot re-read " + t.ref() + " after a list.json change (rescan to refresh): " + e);
            }
        }
        // the sections of cue / chapter split files: rebuild them with the new entries, keep everything else
        Map<RootRef, Folder> albums = new HashMap<>();
        Map<String, org.kendar.dlna.ingestion.CueDirectory.Match> cues = null;
        for (Child c : catalog.current().children(folder.ref())) {
            if (!(c instanceof Child.FolderChild fc) || !fc.folder().isVirtual()
                    || only != null && !only.contains(ListJson.key(fc.folder().ref().name()))) {
                continue;
            }
            try {
                if (cues == null) {
                    cues = org.kendar.dlna.ingestion.CueDirectory.read(dir);
                }
                var built = ListGenerator.build(folder.ref(), dir, fc.folder().ref().name(), reader, cues,
                        lists.forFile(fc.folder().ref().name()), folder);
                if (built.isPresent() && built.get().virtual()) {
                    albums.put(fc.folder().ref(), org.kendar.dlna.ingestion.VirtualAlbums.refresh(fc.folder(),
                            folder, built.get().folder().settings().displayName()));
                    for (Track bt : built.get().tracks()) {
                        catalog.current().track(bt.ref()).ifPresent(old -> {
                            if (!bt.tags().equals(old.tags())) {
                                updated.put(old.ref(), new Track(old.ref(), old.mtime(), old.size(), old.format(),
                                        bt.tags(), old.durationMillis(), old.bitrateKbps(), old.sampleRate(),
                                        old.channels(), old.embeddedArt(), old.sampleCount(), old.renderedMillis(),
                                        old.addedAt(), old.segment()));
                            }
                        });
                    }
                }
            } catch (TagReader.TagReadException | java.io.IOException | RuntimeException e) {
                LOG.warning("Cannot re-read the sections of " + fc.folder().ref()
                        + " after a list.json change (rescan to refresh): " + e);
            }
        }
        catalog.update(cur -> {
            List<Track> tracks = new ArrayList<>(cur.tracks());
            tracks.replaceAll(t -> updated.getOrDefault(t.ref(), t));
            List<Folder> folders = new ArrayList<>(cur.allFolders());
            folders.replaceAll(f -> f.ref().equals(folder.ref()) ? f.withList(lists.hash(), lists.directory().error()) : albums.getOrDefault(f.ref(), f));
            return new org.kendar.dlna.catalog.Snapshot(cur.updateId(), cur.scannedAt(), folders, tracks,
                    cur.unsupported(), cur.playlistDefinitions(), cur.containerUpdateIds());
        });
    }

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
}
