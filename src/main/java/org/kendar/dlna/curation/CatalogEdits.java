package org.kendar.dlna.curation;

import java.util.List;

import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;

/** Builds the candidate snapshots of web UI edits (copy-on-write; update ids are assigned by the commit). */
final class CatalogEdits {

    private CatalogEdits() {
    }

    static Snapshot withTracks(Snapshot cur, List<Track> tracks) {
        return build(cur, List.copyOf(cur.allFolders()), tracks, cur.playlistDefinitions());
    }

    static Snapshot withFolders(Snapshot cur, List<Folder> folders) {
        return build(cur, folders, cur.tracks(), cur.playlistDefinitions());
    }

    static Snapshot withPlaylists(Snapshot cur, List<Playlist> playlists) {
        return build(cur, List.copyOf(cur.allFolders()), cur.tracks(), playlists);
    }

    private static Snapshot build(Snapshot cur, List<Folder> folders, List<Track> tracks, List<Playlist> playlists) {
        return new Snapshot(cur.updateId(), cur.scannedAt(), folders, tracks, cur.unsupported(), playlists,
                cur.containerUpdateIds());
    }
}
