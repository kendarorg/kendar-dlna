package org.kendar.dlna.catalog;

/**
 * Keys under which the catalog tracks per-container update ids. Adapters map their own object ids onto these
 * (the catalog knows nothing of DLNA ids).
 */
public final class ContainerKeys {

    /** The list of library roots. */
    public static final String FOLDERS = "folders";
    /** The list of playlists. */
    public static final String PLAYLISTS = "playlists";

    private ContainerKeys() {
    }

    /** A real directory (its folder id). */
    public static String folder(String folderId) {
        return folderId;
    }

    /** One playlist (its playlist id). */
    public static String playlist(String playlistId) {
        return "p." + playlistId;
    }

    /** The virtual containers (Random N, Recently added, views) of a directory: they change when its subtree pool changes. */
    public static String virtual(String folderId) {
        return "virt:" + folderId;
    }
}
