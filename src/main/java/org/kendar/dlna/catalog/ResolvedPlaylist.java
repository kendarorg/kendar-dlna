package org.kendar.dlna.catalog;

import java.util.List;

/**
 * A playlist matched against the catalog. Entries whose song is missing or unsupported keep their place in the
 * model (so the UI can offer "fix path") but are skipped by {@link #tracks()} and therefore by DLNA.
 */
public record ResolvedPlaylist(Playlist playlist, List<Entry> entries) {

    public enum Status {
        OK, MISSING, UNSUPPORTED
    }

    /** {@code track} is null unless {@code status} is {@link Status#OK}. */
    public record Entry(String path, Track track, Status status) {
    }

    public ResolvedPlaylist {
        entries = List.copyOf(entries);
    }

    public String id() {
        return playlist.id();
    }

    public String name() {
        return playlist.name();
    }

    /** The playable tracks in play order. */
    public List<Track> tracks() {
        return entries.stream().filter(e -> e.status() == Status.OK).map(Entry::track).toList();
    }

    /** Number of entries that cannot be played. */
    public long brokenCount() {
        return entries.stream().filter(e -> e.status() != Status.OK).count();
    }
}
