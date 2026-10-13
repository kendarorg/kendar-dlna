package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.RootRef;

/**
 * Full filesystem scan of the configured roots into a {@link Snapshot}. Hidden files and directories (leading
 * dot) are skipped; symbolic links are not followed. Never fails on a bad file: it is recorded as unsupported.
 */
public class LibraryScanner {

    private static final Logger LOG = Logger.getLogger(LibraryScanner.class.getName());
    private static final int TAG_PARALLELISM = 8;
    static final String SETTINGS_FILE = "settings.json";

    private final TagReader tagReader;
    private final Clock clock;
    private final boolean virtualAlbums;

    public LibraryScanner(TagReader tagReader) {
        this(tagReader, Clock.systemUTC());
    }

    public LibraryScanner(TagReader tagReader, Clock clock) {
        this(tagReader, clock, true);
    }

    /**
     * @param virtualAlbums split files described by a cue sheet or embedded chapters into virtual albums (needs
     *                      transcoding to play them; when false such files stay whole files)
     */
    public LibraryScanner(TagReader tagReader, Clock clock, boolean virtualAlbums) {
        this.tagReader = tagReader;
        this.clock = clock;
        this.virtualAlbums = virtualAlbums;
    }

    /** @param roots root name to directory, in display order */
    public Snapshot scan(Map<String, Path> roots, long updateId) {
        return scan(roots, null, updateId);
    }

    /**
     * Full scan without a previous snapshot.
     *
     * @param roots        root name to directory, in display order
     * @param playlistsDir directory with the playlist JSON files (null or missing = no playlists)
     */
    public Snapshot scan(Map<String, Path> roots, Path playlistsDir, long updateId) {
        return run(roots, playlistsDir, null, null, new ScanProgress(), updateId, false);
    }

    /**
     * Incremental scan: tracks whose mtime and size equal the ones in {@code previous} are taken over without
     * re-reading their tags; directories, settings, art and playlists are always re-read (cheap). The result is a
     * staged candidate: nothing is committed here. Its update ids are those of {@code previous}.
     *
     * @param scope    null = the whole library; else only this directory's subtree is re-walked and everything
     *                 outside it is taken over from {@code previous}
     * @param progress receives counters and errors while scanning
     * @throws IllegalArgumentException when the scope names an unknown root, or a directory that neither exists in
     *                                  {@code previous} nor has a parent there (rescan the whole library instead)
     */
    public Snapshot rescan(Map<String, Path> roots, Path playlistsDir, Snapshot previous, RootRef scope,
            ScanProgress progress) {
        return rescan(roots, playlistsDir, previous, scope, progress, false);
    }

    /**
     * Incremental scan that can ignore the mtime + size reuse.
     *
     * @param force re-read the tags of every file in scope even when it is unchanged; the first-seen timestamp
     *              ({@code addedAt}) of known tracks is kept
     */
    public Snapshot rescan(Map<String, Path> roots, Path playlistsDir, Snapshot previous, RootRef scope,
            ScanProgress progress, boolean force) {
        return run(roots, playlistsDir, previous, scope, progress, previous.updateId(), force)
                .withUpdates(previous.updateId(), clock.instant(), previous.containerUpdateIds());
    }

    private Snapshot run(Map<String, Path> roots, Path playlistsDir, Snapshot previous, RootRef requestedScope,
            ScanProgress progress, long updateId, boolean force) {
        RootRef scope = requestedScope != null && previous != null
                && previous.folder(requestedScope).map(Folder::isVirtual).orElse(false)
                ? requestedScope.parent() : requestedScope;   // a virtual album is rebuilt with its directory
        List<Folder> folders = new ArrayList<>();
        List<UnsupportedFile> unsupported = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();

        if (scope == null) {
            for (Map.Entry<String, Path> root : roots.entrySet()) {
                Path dir = root.getValue();
                if (!Files.isDirectory(dir)) {
                    LOG.warning("Library root '" + root.getKey() + "' is not a directory: " + dir);
                    progress.error("Library root '" + root.getKey() + "' is not a directory: " + dir);
                    continue;
                }
                walk(root.getKey(), dir, dir, folders, unsupported, pending, previous, tracks, progress, force);
            }
        } else {
            Path rootDir = roots.get(scope.root());
            if (rootDir == null || previous == null) {
                throw new IllegalArgumentException("Cannot rescan unknown root: " + scope);
            }
            if (previous.folder(scope).isEmpty()
                    && (scope.isRoot() || previous.folder(scope.parent()).isEmpty())) {
                throw new IllegalArgumentException("Unknown directory, rescan the whole library: " + scope);
            }
            previous.allFolders().stream().filter(f -> !inScope(f.ref(), scope)).forEach(folders::add);
            previous.tracks().stream().filter(t -> !inScope(t.ref(), scope)).forEach(tracks::add);
            previous.unsupported().stream().filter(u -> !inScope(u.ref(), scope)).forEach(unsupported::add);
            Path start = rootDir.resolve(scope.rel()).normalize();
            if (Files.isDirectory(start)) {
                walk(scope.root(), rootDir, start, folders, unsupported, pending, previous, tracks, progress, force);
            }
        }

        Semaphore permits = new Semaphore(TAG_PARALLELISM);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Read>> futures = new ArrayList<>();
            for (Pending p : pending) {
                futures.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        TrackMeta meta = tagReader.read(p.file, p.format);
                        Format actual = meta.format() != null ? meta.format() : p.format;
                        Optional<EmbeddedSections.Found> emb = virtualAlbums && !p.skipSplit && p.cue == null
                                ? EmbeddedSections.read(p.file, actual) : Optional.empty();
                        List<AbcFile.Tune> tunes = actual == Format.ABC ? AbcFile.parse(p.file) : null;
                        return new Read(meta, emb.orElse(null), tunes);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (int i = 0; i < pending.size(); i++) {
                Pending p = pending.get(i);
                try {
                    Read read = futures.get(i).get();
                    TrackMeta m = read.meta();
                    progress.read();
                    TrackTags wholeTags = m.tags();
                    if (virtualAlbums && !p.skipSplit) {
                        VirtualAlbums.Result v = split(p, read);
                        if (v != null && v.virtual()) {
                            folders.add(v.folder());
                            tracks.addAll(v.tracks());
                            if (!p.keepWhole) {
                                continue;
                            }
                        } else if (v != null && v.wholeTags() != null) {
                            wholeTags = v.wholeTags();
                        }
                    }
                    tracks.add(new Track(p.ref, p.mtime, p.size, m.format() != null ? m.format() : p.format,
                            ListJson.apply(wholeTags, p.entry), m.durationMillis(),
                            m.bitrateKbps(), m.sampleRate(), m.channels(), m.embeddedArt(), p.unchanged != null && p.unchanged.renderedMillis() > 0
                                    ? p.unchanged.sampleCount() : m.sampleCount(),
                            p.unchanged == null ? 0 : p.unchanged.renderedMillis(), p.addedAt));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof TagReader.TagReadException tre && tre.unsupported()) {
                        LOG.info("Unsupported audio file " + p.ref + ": " + cause.getMessage());
                        unsupported.add(new UnsupportedFile(p.ref, p.size, cause.getMessage()));
                    } else {
                        LOG.warning("Unreadable audio file " + p.ref + ": " + cause.getMessage());
                        progress.error("Unreadable audio file " + p.ref + ": " + cause.getMessage());
                        unsupported.add(new UnsupportedFile(p.ref, p.size, "unreadable: " + cause.getMessage()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Scan interrupted", e);
                }
            }
        }
        LOG.info("Scan complete: " + folders.size() + " folders, " + tracks.size() + " tracks ("
                + progress.tracksReused() + " reused), " + unsupported.size() + " unsupported");
        List<Playlist> playlists = loadPlaylists(playlistsDir);
        return new Snapshot(updateId, clock.instant(), folders, tracks, unsupported, playlists);
    }

    private static boolean inScope(RootRef ref, RootRef scope) {
        return ref.root().equals(scope.root()) && (scope.isRoot() || ref.rel().equals(scope.rel())
                || ref.rel().startsWith(scope.rel() + "/"));
    }

    /** Reads {@code *.json} files of the (flat) playlists directory; broken files are logged and skipped. */
    static List<Playlist> loadPlaylists(Path dir) {
        List<Playlist> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && !isHidden(p)) {
                    files.add(p);
                }
            }
        } catch (IOException e) {
            LOG.warning("Cannot list playlists in " + dir + ": " + e);
        }
        files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString(), Normalizer.NATURAL));
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Path f : files) {
            PlaylistParser.Result r = PlaylistParser.parseFile(f);
            if (r.playlist() == null) {
                LOG.warning("Invalid playlist " + f.getFileName() + ": " + r.error());
            } else if (keys.add(r.playlist().key())) {
                out.add(r.playlist());
            }
        }
        out.sort(java.util.Comparator.comparing(Playlist::name, Normalizer.NATURAL));
        return out;
    }

    private record Pending(RootRef ref, Path file, Format format, long mtime, long size, long addedAt,
            Track unchanged, ListJson.Entry entry, CueDirectory.Match cue, boolean skipSplit, boolean keepWhole, Folder dir,
            ListJson.Result list) {
    }

    /** Result of reading one file: its tags and the sections embedded in it (null = none). */
    private record Read(TrackMeta meta, EmbeddedSections.Found embedded, List<AbcFile.Tune> tunes) {
    }

    /**
     * The list.json of a directory being walked and whether it differs from the one of the previous scan, the
     * directory itself (its settings) and the cue sheets of the directory by audio file key.
     */
    private record DirList(DirectoryLists lists, boolean changed, Folder folder, Map<String, CueDirectory.Match> cues) {
    }

    private Map<String, CueDirectory.Match> readCues(Path dir) {
        return virtualAlbums ? CueDirectory.read(dir) : new java.util.HashMap<>();
    }

    private VirtualAlbums.Result split(Pending p, Read read) {
        if (p.format == Format.ABC) {
            return read.tunes() == null ? null : AbcAlbums.build(new VirtualAlbums.Source(p.ref, p.mtime, p.size,
                    p.addedAt, p.format, read.meta()), read.tunes(), p.list, p.dir);
        }
        CueSheet sheet;
        CueSheet.CueFile entry;
        String origin;
        String hash;
        String cueFile;
        if (p.cue != null) {
            sheet = p.cue.sheet();
            entry = p.cue.file();
            origin = "cue";
            hash = "cue:" + p.cue.hash();
            cueFile = p.cue.cueName();
        } else if (read.embedded() != null) {
            sheet = read.embedded().sheet();
            entry = sheet.files().get(0);
            origin = read.embedded().origin();
            hash = "emb:" + origin;
            cueFile = null;
        } else {
            return null;
        }
        Format actual = read.meta().format() != null ? read.meta().format() : p.format;
        VirtualAlbums.Result r = VirtualAlbums.build(new VirtualAlbums.Source(p.ref, p.mtime, p.size, p.addedAt,
                actual, read.meta()), sheet, entry, origin, hash, cueFile, p.list, p.dir);
        for (String w : r.warnings()) {
            LOG.warning("Sections of " + p.ref + ": " + w);
        }
        return r;
    }

    private void walk(String rootName, Path rootDir, Path start, List<Folder> folders,
            List<UnsupportedFile> unsupported, List<Pending> pending, Snapshot previous, List<Track> reused,
            ScanProgress progress, boolean force) {
        Map<Path, DirList> lists = new java.util.HashMap<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(rootDir) && !dir.equals(start) && isHidden(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    progress.directory();
                    RootRef dirRef = refOf(rootName, rootDir, dir);
                    DirectoryLists all = DirectoryLists.read(dir);
                    ListJson.Result list = all.directory();
                    if (list.error() != null) {
                        LOG.warning("Invalid " + ListJson.FILE_NAME + " in " + dirRef + ": " + list.error());
                    }
                    if (!list.warnings().isEmpty()) {
                        LOG.warning(ListJson.FILE_NAME + " in " + dirRef + ": " + String.join("; ", list.warnings()));
                    }
                    if (!all.problems().isEmpty()) {
                        LOG.warning("Sections files in " + dirRef + ": " + String.join("; ", all.problems()));
                    }
                    Folder folder = readFolder(dirRef, dir, attrs).withList(all.hash(), list.error());
                    Folder oldFolder = previous == null ? null : previous.folder(dirRef).orElse(null);
                    boolean listChanged = oldFolder != null
                            ? !java.util.Objects.equals(oldFolder.listHash(), all.hash()) : all.hash() != null;
                    lists.put(dir, new DirList(all, listChanged, folder, readCues(dir)));
                    folders.add(folder);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || isHidden(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    Optional<Format> format = Format.fromFileName(name);
                    if (format.isEmpty()) {
                        Optional<String> reason = knownUnsupported(name);
                        if (reason.isPresent()) {
                            unsupported.add(new UnsupportedFile(refOf(rootName, rootDir, file), attrs.size(),
                                    reason.get()));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    RootRef ref = refOf(rootName, rootDir, file);
                    progress.audioFile();
                    if (!tagReader.supports(format.get())) {
                        unsupported.add(new UnsupportedFile(ref, attrs.size(),
                                tagReader.unsupportedReason(format.get())));
                    } else {
                        long mtime = attrs.lastModifiedTime().toMillis();
                        DirList dl = lists.get(file.getParent());
                        ListJson.Result fileList = dl == null ? null : dl.lists().forFile(name);
                        ListJson.Entry entry = fileList == null ? null : fileList.find(name).orElse(null);
                        boolean listChanged = dl != null && dl.changed();
                        Track old = previous == null ? null : previous.track(ref).orElse(null);
                        CueDirectory.Match cue = dl == null ? null : dl.cues().get(ListJson.key(name));
                        boolean keepWhole = dl != null && dl.folder().settings().keepWholeFiles();
                        Folder oldAlbum = !virtualAlbums || previous == null ? null
                                : previous.folder(ref).filter(Folder::isVirtual).orElse(null);
                        boolean splitReuse = false;
                        if (oldAlbum != null && !force && !listChanged && oldAlbum.split().sourceMtime() == mtime
                                && oldAlbum.split().sourceSize() == attrs.size()) {
                            String h = oldAlbum.split().hash();
                            splitReuse = cue != null ? h.equals("cue:" + cue.hash()) : h.startsWith("emb:") || h.startsWith("abc:");
                        }
                        if (splitReuse) {
                            folders.add(VirtualAlbums.refresh(oldAlbum, dl.folder()));
                            for (Child c : previous.children(ref)) {
                                if (c instanceof Child.TrackChild tc) {
                                    reused.add(tc.track());
                                }
                            }
                            progress.reused();
                            if (!keepWhole) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        boolean sameFile = old != null && old.mtime() == mtime && old.size() == attrs.size()
                                && Format.sameFileKind(old.format(), format.get());
                        if (!force && !listChanged && sameFile && (cue == null || splitReuse)) {
                            reused.add(old);
                            progress.reused();
                        } else {
                            long added = old == null && oldAlbum != null && !previous.children(ref).isEmpty()
                                    && previous.children(ref).get(0) instanceof Child.TrackChild tc
                                    && tc.track().addedAt() > 0 ? tc.track().addedAt()
                                    : addedAt(old, previous == null, attrs);
                            pending.add(new Pending(ref, file, format.get(), mtime, attrs.size(), added,
                                    sameFile ? old : null, entry, cue, splitReuse, keepWhole,
                                    dl == null ? null : dl.folder(), fileList));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.warning("Cannot access " + file + ": " + exc);
                    progress.error("Cannot access " + file + ": " + exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warning("Scan of root '" + rootName + "' failed: " + e);
            progress.error("Scan of root '" + rootName + "' failed: " + e);
        }
    }

    /**
     * First-seen timestamp of a file: kept for known tracks; on the very first scan the file creation time (else
     * mtime) so the initial order is sensible; otherwise now.
     */
    private long addedAt(Track old, boolean firstScan, BasicFileAttributes attrs) {
        if (old != null && old.addedAt() > 0) {
            return old.addedAt();
        }
        if (old != null || firstScan) {
            long created = attrs.creationTime().toMillis();
            return created > 0 ? created : attrs.lastModifiedTime().toMillis();
        }
        return clock.millis();
    }

    private static RootRef refOf(String rootName, Path rootDir, Path path) {
        String rel = rootDir.relativize(path).toString();
        return new RootRef(rootName, rel);
    }

    private static boolean isHidden(Path p) {
        Path name = p.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    /** Audio-looking extensions we have no decoder for at all. */
    private static Optional<String> knownUnsupported(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "alac" ->
                    Optional.of("no decoder for ." + ext);
            default -> Optional.empty();
        };
    }

    private Folder readFolder(RootRef ref, Path dir, BasicFileAttributes attrs) {
        boolean front = false;
        boolean rear = false;
        Path settingsFile = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (org.kendar.dlna.shared.ArtNames.isFront(n)) {
                    front = true;
                } else if (org.kendar.dlna.shared.ArtNames.isRear(n)) {
                    rear = true;
                } else if (n.equals(SETTINGS_FILE) && Files.isRegularFile(p)) {
                    settingsFile = p;
                }
            }
        } catch (IOException e) {
            LOG.warning("Cannot list " + dir + ": " + e);
        }
        SettingsParser.Result settings = settingsFile == null ? new SettingsParser.Result(null, null)
                : SettingsParser.parseFile(settingsFile);
        if (settings.error() != null) {
            LOG.warning("Invalid " + SETTINGS_FILE + " in " + ref + ": " + settings.error());
        }
        return new Folder(ref, attrs.lastModifiedTime().toMillis(), settings.settings(), settings.error(), front,
                rear);
    }
}
