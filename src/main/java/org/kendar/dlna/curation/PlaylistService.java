package org.kendar.dlna.curation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.ingestion.CatalogService;
import org.kendar.dlna.shared.Json;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Playlist editing: create, rename, delete, add, reorder/remove (replace the song list) and fix a broken path.
 * Every change is written atomically as {@code <playlistsDir>/<key>.json} and enters the catalog immediately
 * (copy-on-write commit, which also persists the index).
 */
public final class PlaylistService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final int MAX_NAME = 200;
    private static final int MAX_SONGS = 20_000;

    private final CatalogService catalog;
    private final Path dir;

    public PlaylistService(CatalogService catalog, Path playlistsDir) {
        this.catalog = catalog;
        this.dir = playlistsDir;
    }

    public synchronized Playlist create(String name) {
        String clean = cleanName(name);
        String key = freshKey(clean);
        Playlist p = new Playlist(key, clean, List.of());
        write(p);
        catalog.update(cur -> {
            List<Playlist> all = new ArrayList<>(cur.playlistDefinitions());
            all.add(p);
            return CatalogEdits.withPlaylists(cur, all);
        });
        return p;
    }

    public synchronized Playlist rename(String key, String name) {
        Playlist cur = existing(key);
        return store(new Playlist(cur.key(), cleanName(name), cur.songs()));
    }

    public synchronized void delete(String key) {
        existing(key);
        try {
            Files.deleteIfExists(file(key));
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot delete playlist " + key + ": " + e.getMessage(), null, e);
        }
        catalog.update(cur -> {
            List<Playlist> all = new ArrayList<>(cur.playlistDefinitions());
            all.removeIf(p -> p.key().equals(key));
            return CatalogEdits.withPlaylists(cur, all);
        });
    }

    /** Appends songs (they must exist in the catalog). */
    public synchronized Playlist add(String key, List<String> paths) {
        Playlist cur = existing(key);
        if (paths == null || paths.isEmpty()) {
            throw new CurationException(CurationException.INVALID, "No songs given");
        }
        List<String> songs = new ArrayList<>(cur.songs());
        for (String p : paths) {
            songs.add(knownTrack(p));
        }
        checkSize(songs);
        return store(new Playlist(cur.key(), cur.name(), songs));
    }

    /** Replaces the whole song list (reorder and remove); entries must at least be well-formed paths. */
    public synchronized Playlist replaceSongs(String key, List<String> songs) {
        Playlist cur = existing(key);
        if (songs == null) {
            throw new CurationException(CurationException.INVALID, "songs is required");
        }
        List<String> clean = new ArrayList<>();
        for (String s : songs) {
            clean.add(wellFormed(s));
        }
        checkSize(clean);
        return store(new Playlist(cur.key(), cur.name(), clean));
    }

    /** Points the entry at {@code index} to another (existing) file. */
    public synchronized Playlist fixPath(String key, int index, String newPath) {
        Playlist cur = existing(key);
        if (index < 0 || index >= cur.songs().size()) {
            throw new CurationException(CurationException.INVALID, "No entry at index " + index);
        }
        List<String> songs = new ArrayList<>(cur.songs());
        songs.set(index, knownTrack(newPath));
        return store(new Playlist(cur.key(), cur.name(), songs));
    }

    /** A song file was renamed: every playlist entry of {@code from} (or a section of it) now points to {@code to}. */
    public synchronized void renameSong(String from, String to) {
        for (Playlist p : List.copyOf(catalog.current().playlistDefinitions())) {
            List<String> songs = new ArrayList<>(p.songs());
            boolean changed = false;
            for (int i = 0; i < songs.size(); i++) {
                String s = songs.get(i);
                if (s.equals(from) || s.startsWith(from + "#")) {
                    songs.set(i, to + s.substring(from.length()));
                    changed = true;
                }
            }
            if (changed) {
                store(new Playlist(p.key(), p.name(), songs));
            }
        }
    }

    // ---- helpers ----

    private Playlist store(Playlist p) {
        write(p);
        catalog.update(cur -> {
            List<Playlist> all = new ArrayList<>(cur.playlistDefinitions());
            all.replaceAll(x -> x.key().equals(p.key()) ? p : x);
            return CatalogEdits.withPlaylists(cur, all);
        });
        return p;
    }

    private void write(Playlist p) {
        ObjectNode o = Json.mapper().createObjectNode();
        o.put("name", p.name());
        ArrayNode songs = o.putArray("songs");
        p.songs().forEach(songs::add);
        try {
            Files.createDirectories(dir);
            AtomicFiles.write(file(p.key()), (Json.mapper().writeValueAsString(o) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CurationException(CurationException.IO_ERROR,
                    "Cannot write playlist " + p.key() + ": " + e.getMessage(), null, e);
        }
    }

    private Path file(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new CurationException(CurationException.INVALID, "Invalid playlist key: " + key);
        }
        return dir.resolve(key + ".json");
    }

    private Playlist existing(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new CurationException(CurationException.INVALID, "Invalid playlist key: " + key);
        }
        return catalog.current().playlistDefinitions().stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new CurationException(CurationException.NOT_FOUND, "Unknown playlist: " + key));
    }

    private String knownTrack(String path) {
        String clean = wellFormed(path);
        Snapshot snap = catalog.current();
        if (snap.track(RootRef.parse(clean)).isEmpty()) {
            throw new CurationException(CurationException.NOT_FOUND, "No such song in the library: " + clean);
        }
        return clean;
    }

    private static String wellFormed(String path) {
        if (path == null || path.isBlank()) {
            throw new CurationException(CurationException.INVALID, "Empty song path");
        }
        try {
            RootRef ref = RootRef.parse(path.strip());
            if (ref.isRoot()) {
                throw new CurationException(CurationException.INVALID, "Not a file path: " + path);
            }
            return ref.qualified();
        } catch (IllegalArgumentException e) {
            throw new CurationException(CurationException.INVALID, "Invalid song path: " + path);
        }
    }

    private static void checkSize(List<String> songs) {
        if (songs.size() > MAX_SONGS) {
            throw new CurationException(CurationException.INVALID, "A playlist holds at most " + MAX_SONGS + " songs");
        }
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            throw new CurationException(CurationException.INVALID, "A playlist needs a name");
        }
        String n = name.strip();
        if (n.length() > MAX_NAME || n.chars().anyMatch(Character::isISOControl)) {
            throw new CurationException(CurationException.INVALID, "Invalid playlist name");
        }
        return n;
    }

    private String freshKey(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "playlist";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        String key = base;
        for (int i = 2; taken(key); i++) {
            key = base + "-" + i;
        }
        return key;
    }

    private boolean taken(String key) {
        boolean inCatalog = catalog.current().playlistDefinitions().stream()
                .anyMatch(p -> p.key().equalsIgnoreCase(key));
        return inCatalog || Files.exists(dir.resolve(key + ".json"));
    }
}
