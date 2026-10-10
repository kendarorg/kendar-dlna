package org.kendar.dlna.catalog;

import java.util.List;

import org.kendar.dlna.shared.StableIds;

/**
 * A server-side playlist as stored in its JSON file: an ordered list of root-qualified song paths
 * ({@code root/relative/path.mp3}). {@code key} identifies the file (its name without extension).
 */
public record Playlist(String key, String name, List<String> songs) {

    public Playlist {
        songs = songs == null ? List.of() : List.copyOf(songs);
        name = name == null || name.isBlank() ? key : name.strip();
    }

    public static String idOf(String key) {
        return StableIds.id("p", key);
    }

    public String id() {
        return idOf(key);
    }
}
