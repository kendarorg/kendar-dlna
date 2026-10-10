package org.kendar.dlna.catalog;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link ContainerNavigation} of the catalog itself: folder contexts (the folder id) follow the folder's display
 * order (tracks only), playlist contexts ({@code p.<playlistId>}) follow the play order.
 */
public final class CatalogNavigation implements ContainerNavigation {

    public static final String PLAYLIST_PREFIX = "p.";

    private final Supplier<Snapshot> snapshot;

    public CatalogNavigation(Supplier<Snapshot> snapshot) {
        this.snapshot = snapshot;
    }

    /** Context id of a playlist. */
    public static String playlistContext(String playlistId) {
        return PLAYLIST_PREFIX + playlistId;
    }

    @Override
    public boolean knows(String containerId) {
        if (containerId == null) {
            return false;
        }
        Snapshot s = snapshot.get();
        if (containerId.startsWith(PLAYLIST_PREFIX)) {
            return s.playlist(containerId.substring(PLAYLIST_PREFIX.length())).isPresent();
        }
        return s.folderById(containerId).isPresent();
    }

    @Override
    public Optional<Track> next(String containerId, String itemId) {
        if (containerId == null || itemId == null) {
            return Optional.empty();
        }
        Snapshot s = snapshot.get();
        if (containerId.startsWith(PLAYLIST_PREFIX)) {
            return s.playlist(containerId.substring(PLAYLIST_PREFIX.length()))
                    .flatMap(p -> after(p.tracks(), itemId));
        }
        return s.folderById(containerId).flatMap(f -> after(
                s.children(f.ref()).stream()
                        .filter(c -> c instanceof Child.TrackChild)
                        .map(c -> ((Child.TrackChild) c).track()).toList(),
                itemId));
    }

    /** The element after the first one with the given id. */
    public static Optional<Track> after(List<Track> tracks, String itemId) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id().equals(itemId)) {
                return i + 1 < tracks.size() ? Optional.of(tracks.get(i + 1)) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
