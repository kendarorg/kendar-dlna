package org.kendar.dlna.catalog;

import java.util.List;

import org.kendar.dlna.shared.StableIds;

/**
 * A server-side playlist: an ordered list of root-qualified song paths ({@code root/relative/path.mp3}).
 * {@code key} identifies it (the JSON file name without extension). {@code system} playlists are read from
 * {@code .m3u/.m3u8/.pls} files found in the library: they are read-only, and entries that are not (any more) in
 * the catalog are left out.
 */
public record Playlist(String key, String name, List<String> songs, boolean system) {

    /** Prefix of the keys of system playlists, so they never clash with the JSON playlists. */
    public static final String SYSTEM_PREFIX = "sys.";

    public Playlist {
        songs = songs == null ? List.of() : List.copyOf(songs);
        name = name == null || name.isBlank() ? key : name.strip();
    }

    /** An editable (JSON file) playlist. */
    public Playlist(String key, String name, List<String> songs) {
        this(key, name, songs, false);
    }

    public static String idOf(String key) {
        return StableIds.id("p", key);
    }

    public String id() {
        return idOf(key);
    }
}
