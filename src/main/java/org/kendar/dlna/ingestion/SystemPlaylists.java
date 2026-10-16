package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.shared.Normalizer;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.shared.StableIds;

/**
 * Finds {@code .m3u}, {@code .m3u8} and {@code .pls} files anywhere in the library roots and turns them into
 * read-only system playlists. Entries are resolved against the playlist's own directory (relative paths) or the
 * roots (absolute paths inside one); only entries that exist on disk are kept, and the catalog drops the ones it
 * does not know ({@link Playlist#system()}). Hidden (dot) directories are skipped, symbolic links are not followed.
 */
public final class SystemPlaylists {

    private static final Logger LOG = Logger.getLogger(SystemPlaylists.class.getName());
    static final Set<String> EXTENSIONS = Set.of("m3u", "m3u8", "pls");
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final int MAX_ENTRIES = 200_000;

    private SystemPlaylists() {
    }

    /** All system playlists of the roots, by display name. */
    public static List<Playlist> discover(Map<String, Path> roots) {
        List<Playlist> out = new ArrayList<>();
        for (Map.Entry<String, Path> root : roots.entrySet()) {
            Path dir = root.getValue();
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        return !d.equals(dir) && d.getFileName().toString().startsWith(".")
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        String name = f.getFileName().toString();
                        if (a.isRegularFile() && !name.startsWith(".") && EXTENSIONS.contains(extension(name))) {
                            read(roots, root.getKey(), dir, f, a.size()).ifPresent(out::add);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                LOG.warning("Cannot look for playlists in " + dir + ": " + e);
            }
        }
        out.sort(Comparator.comparing(Playlist::name, Normalizer.NATURAL).thenComparing(Playlist::key));
        return out;
    }

    private static Optional<Playlist> read(Map<String, Path> roots, String rootName, Path rootDir, Path file,
            long size) {
        if (size > MAX_FILE_BYTES) {
            LOG.warning("Playlist file too large, skipped: " + file);
            return Optional.empty();
        }
        String fileName = file.getFileName().toString();
        String ext = extension(fileName);
        List<String> entries;
        try {
            entries = parse(ext, decode(Files.readAllBytes(file)));
        } catch (IOException | RuntimeException e) {
            LOG.warning("Cannot read playlist " + file + ": " + e);
            return Optional.empty();
        }
        List<String> songs = new ArrayList<>();
        for (String entry : entries) {
            if (songs.size() >= MAX_ENTRIES) {
                break;
            }
            // duplicates are kept: a playlist may repeat a song
            resolve(roots, file.getParent(), entry).ifPresent(ref -> songs.add(ref.qualified()));
        }
        if (songs.isEmpty()) {
            return Optional.empty();
        }
        String qualified;
        try {
            qualified = RootRef.parse(rootName + "/" + rootDir.relativize(file).toString().replace('\\', '/')).qualified();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String name = fileName.substring(0, fileName.length() - ext.length() - 1);
        return Optional.of(new Playlist(Playlist.SYSTEM_PREFIX + StableIds.id("sysp", qualified), name, songs, true));
    }

    /** UTF-8 (with or without BOM) when the bytes are valid UTF-8, else windows-1252 (the .m3u convention). */
    static String decode(byte[] bytes) {
        int skip = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** The raw file entries of a playlist text, in order (comments, titles and stream URLs excluded). */
    static List<String> parse(String ext, String text) {
        List<String> out = new ArrayList<>();
        boolean pls = ext.equals("pls");
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (pls) {
                int eq = line.indexOf('=');
                if (eq < 0 || !line.substring(0, eq).strip().toLowerCase(Locale.ROOT).matches("file\\d+")) {
                    continue;
                }
                line = line.substring(eq + 1).strip();
            }
            boolean url = line.matches("(?i)^[a-z][a-z0-9+.-]+://.*");
            if (line.isEmpty() || (url && !line.regionMatches(true, 0, "file://", 0, 7))) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /**
     * Maps an entry to a library file that exists on disk: relative to the playlist's directory, or an absolute
     * path (or {@code file://} URL) inside one of the roots.
     */
    static Optional<RootRef> resolve(Map<String, Path> roots, Path playlistDir, String entry) {
        String e = entry;
        if (e.regionMatches(true, 0, "file://", 0, 7)) {
            e = e.substring(7);
            if (e.matches("^/[A-Za-z]:.*")) {
                e = e.substring(1);
            }
            try {
                e = URLDecoder.decode(e.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        Path p;
        try {
            Path given = Path.of(e.replace('\\', '/'));
            p = (given.isAbsolute() ? given : playlistDir.resolve(given)).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return Optional.empty();   // invalid path characters
        }
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        for (Map.Entry<String, Path> root : roots.entrySet()) {
            Path base = root.getValue().toAbsolutePath().normalize();
            if (p.startsWith(base)) {
                String rel = base.relativize(p).toString().replace('\\', '/');
                try {
                    return Optional.of(RootRef.parse(root.getKey() + "/" + rel));
                } catch (IllegalArgumentException ex) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static String extension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
