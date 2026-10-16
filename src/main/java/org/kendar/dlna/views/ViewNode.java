package org.kendar.dlna.views;

import java.util.List;

import org.kendar.dlna.catalog.Track;

/**
 * A container of a view tree. A node holds either child containers or tracks (never both). {@code id} is the
 * stable context id (also the stream URL container id); {@code parentId} is the id of the parent view node, or
 * null for the view root (whose parent is the directory). {@code artTrack} is the track whose artwork represents
 * the node (may be null); {@code artist} and {@code year} describe album nodes.
 */
public record ViewNode(String id, String parentId, Type type, String title, String artist, Integer year,
        List<ViewNode> children, List<Track> tracks, Track artTrack) {

    public enum Type {
        /** The view container itself (Albums, Artists, Genres, Titles). */
        ROOT,
        /** An A-Z / # sub-container. */
        BUCKET,
        ALBUM,
        ARTIST,
        GENRE,
        /** The "All tracks" container of an artist. */
        ALL_TRACKS,
        /** A level of a configured navigation grouped by a tag other than album, artist and genre. */
        GROUP,
        /** The "Recently added" container: the newest tracks of the directory subtree. */
        RECENT
    }

    public ViewNode {
        children = List.copyOf(children);
        tracks = List.copyOf(tracks);
    }

    /** Number of direct entries (containers or tracks). */
    public int childCount() {
        return children.size() + tracks.size();
    }
}
