package org.kendar.dlna.artwork;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;

/**
 * Art resolution and thumbnails (plan section 5).
 * <ul>
 * <li>Item art: {@code front.jpg} of its directory, else the track's embedded art.</li>
 * <li>Folder art: {@code front.jpg} of the directory, else embedded art of its first track that has some
 * (direct tracks first, then sub-directories, depth first).</li>
 * <li>{@code rear.jpg} is only reachable through {@link #rear(String)} (web UI), never through DIDL.</li>
 * </ul>
 * Thumbnails (JPEG, at most {@value #THUMB_SIZE} px) are cached under the thumbs directory; the cache key
 * covers path, mtime and size of the source so a changed source regenerates the thumbnail.
 */
public final class ArtworkService {

    public static final int THUMB_SIZE = 160;
    public static final String FRONT = "front.jpg";
    public static final String REAR = "rear.jpg";

    private static final Logger LOG = Logger.getLogger(ArtworkService.class.getName());

    private final SnapshotHolder holder;
    private final ArtFiles files;
    private final EmbeddedArtSource embedded;
    private final Path thumbsDir;
    private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();

    public ArtworkService(SnapshotHolder holder, ArtFiles files, EmbeddedArtSource embedded, Path thumbsDir) {
        this.holder = holder;
        this.files = files;
        this.embedded = embedded;
        this.thumbsDir = thumbsDir;
    }

    // ---- existence (catalog only, no I/O) ----

    public boolean trackHasArt(Snapshot snap, Track track) {
        return snap.folder(track.parentRef()).map(Folder::hasFront).orElse(false) || track.embeddedArt();
    }

    public boolean folderHasArt(Snapshot snap, Folder folder) {
        return folder.hasFront() || firstEmbeddedTrack(snap, folder.ref()).isPresent();
    }

    private Optional<Track> firstEmbeddedTrack(Snapshot snap, RootRef dir) {
        for (Child c : snap.children(dir)) {
            if (c instanceof Child.TrackChild t && t.track().embeddedArt()) {
                return Optional.of(t.track());
            }
        }
        for (Child c : snap.children(dir)) {
            if (c instanceof Child.FolderChild f) {
                Optional<Track> found = firstEmbeddedTrack(snap, f.folder().ref());
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    // ---- serving ----

    public Optional<Art> trackArt(String trackId, boolean thumbnail) {
        return trackArt(trackId, thumbnail ? ArtSize.SMALL : null);
    }

    /** Track art in a web player size; null = original size. */
    public Optional<Art> trackArt(String trackId, ArtSize size) {
        Snapshot snap = holder.current();
        return snap.trackById(trackId).flatMap(t -> {
            Optional<Source> front = fileOf(snap, t.parentRef(), FRONT);
            return load(front.isPresent() ? front : embeddedOf(t), size);
        });
    }

    public Optional<Art> folderArt(String folderId, boolean thumbnail) {
        return folderArt(folderId, thumbnail ? ArtSize.SMALL : null);
    }

    /** Folder art in a web player size; null = original size. */
    public Optional<Art> folderArt(String folderId, ArtSize size) {
        Snapshot snap = holder.current();
        return snap.folderById(folderId).flatMap(f -> {
            Optional<Source> front = fileOf(snap, f.ref(), FRONT);
            if (front.isPresent()) {
                return load(front, size);
            }
            return load(firstEmbeddedTrack(snap, f.ref()).flatMap(this::embeddedOf), size);
        });
    }

    /** {@code rear.jpg} of a directory, original size (web UI only). */
    public Optional<Art> rear(String folderId) {
        Snapshot snap = holder.current();
        return load(snap.folderById(folderId).flatMap(f -> fileOf(snap, f.ref(), REAR)), null);
    }

    // ---- sources ----

    /** Identity (path), version (mtime/size) and the loader of one image source. */
    private record Source(String identity, String version, long lastModified, ThrowingBytes loader) {
    }

    @FunctionalInterface
    private interface ThrowingBytes {
        byte[] get() throws IOException;
    }

    private Optional<Source> fileOf(Snapshot snap, RootRef dir, String name) {
        Optional<Folder> folder = snap.folder(dir);
        boolean listed = folder.map(f -> name.equals(REAR) ? f.hasRear() : f.hasFront()).orElse(false);
        if (!listed) {
            return Optional.empty();
        }
        if (folder.get().isVirtual()) {
            dir = dir.parent();   // an album inside a file shares the front.jpg of the directory holding the file
        }
        Path p = files.resolve(dir.child(name));
        if (p == null) {
            return Optional.empty();
        }
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            if (!a.isRegularFile()) {
                return Optional.empty();
            }
            long mtime = a.lastModifiedTime().toMillis();
            return Optional.of(new Source("file:" + dir.child(name).qualified(), mtime + "/" + a.size(), mtime,
                    () -> Files.readAllBytes(p)));
        } catch (IOException e) {
            return Optional.empty();   // removed since the scan
        }
    }

    private Optional<Source> embeddedOf(Track t) {
        if (!t.embeddedArt()) {
            return Optional.empty();
        }
        return Optional.of(new Source("embedded:" + t.ref().qualified(), t.mtime() + "/" + t.size(), t.mtime(),
                () -> embedded.read(t.ref()).orElseThrow(() -> new IOException("No embedded art"))));
    }

    private Optional<Art> load(Optional<Source> source, ArtSize size) {
        boolean thumbnail = size != null;
        if (source.isEmpty()) {
            return Optional.empty();
        }
        Source s = source.get();
        String tag = hash(s.identity() + "|" + s.version() + (!thumbnail ? "" : size == ArtSize.SMALL ? "|tn" : "|tn" + size.pixels()));
        String etag = "\"" + tag + "\"";
        try {
            if (!thumbnail) {
                byte[] data = s.loader().get();
                return Optional.of(new Art(data, Thumbnailer.sniff(data), s.lastModified(), etag, false));
            }
            return Optional.of(new Art(thumbnailBytes(s, size), "image/jpeg", s.lastModified(), etag, true));
        } catch (IOException | RuntimeException e) {
            if (reportedFailures.add(s.identity() + "|" + s.version())) {
                LOG.warning("Cannot use artwork " + s.identity() + ": " + e);
            }
            return Optional.empty();
        }
    }

    private byte[] thumbnailBytes(Source s, ArtSize size) throws IOException {
        String idHash = hash(s.identity());
        String family = size == ArtSize.SMALL ? idHash + "-" : idHash + "~" + size.pixels() + "-";
        Path cached = thumbsDir.resolve(family + hash(s.version()) + ".jpg");
        if (Files.isRegularFile(cached)) {
            try {
                return Files.readAllBytes(cached);
            } catch (IOException e) {
                // fall through and regenerate
            }
        }
        byte[] thumb = Thumbnailer.jpeg(s.loader().get(), size.pixels());
        try {
            Files.createDirectories(thumbsDir);
            removeStale(family, cached);
            Path tmp = Files.createTempFile(thumbsDir, idHash, ".tmp");
            Files.write(tmp, thumb);
            Files.move(tmp, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.fine("Thumbnail cache not writable: " + e);   // serve uncached
        }
        return thumb;
    }

    private void removeStale(String family, Path keep) {
        try (Stream<Path> old = Files.list(thumbsDir)) {
            old.filter(p -> p.getFileName().toString().startsWith(family) && !p.equals(keep))
                    .forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // best effort
        }
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
