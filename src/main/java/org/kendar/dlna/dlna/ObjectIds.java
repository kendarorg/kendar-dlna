package org.kendar.dlna.dlna;

import java.util.Optional;

import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Track;

/**
 * DLNA object id scheme (the ids inside are the stable, path-derived catalog ids):
 * <ul>
 *   <li>{@code 0} root, {@code folders} (the "Folders" container), {@code playlists} (the "Playlists" container)</li>
 *   <li>{@code f:<folderId>}, {@code t:<trackId>}: real directories and tracks</li>
 *   <li>{@code c:<context>}: a virtual container (playlist {@code p.<id>}, Random N snapshot
 *       {@code r.<dirId>.<snapshotId>}, view group {@code v.<dirId>.<view>...})</li>
 *   <li>{@code i:<context>:<trackId>}: a track as listed in a virtual container; the context is also the
 *       container id of its stream URL</li>
 * </ul>
 */
public final class ObjectIds {

    public static final String ROOT = "0";
    public static final String FOLDERS = "folders";
    public static final String PLAYLISTS = "playlists";
    private static final String FOLDER_PREFIX = "f:";
    private static final String TRACK_PREFIX = "t:";
    private static final String CONTAINER_PREFIX = "c:";
    private static final String ITEM_PREFIX = "i:";

    /** An item inside a virtual container. */
    public record ContextItem(String context, String trackId) {
    }

    private ObjectIds() {
    }

    public static String folder(Folder f) {
        return FOLDER_PREFIX + f.id();
    }

    public static String folderOf(String folderId) {
        return FOLDER_PREFIX + folderId;
    }

    public static String track(Track t) {
        return TRACK_PREFIX + t.id();
    }

    public static String container(String context) {
        return CONTAINER_PREFIX + context;
    }

    public static String contextItem(String context, Track t) {
        return ITEM_PREFIX + context + ":" + t.id();
    }

    /** The catalog folder id for a {@code f:} object id. */
    public static Optional<String> folderId(String objectId) {
        return strip(objectId, FOLDER_PREFIX);
    }

    /** The catalog track id for a {@code t:} object id. */
    public static Optional<String> trackId(String objectId) {
        return strip(objectId, TRACK_PREFIX);
    }

    /** The context of a {@code c:} object id. */
    public static Optional<String> containerContext(String objectId) {
        return strip(objectId, CONTAINER_PREFIX);
    }

    public static Optional<ContextItem> contextItem(String objectId) {
        return strip(objectId, ITEM_PREFIX).flatMap(rest -> {
            int i = rest.lastIndexOf(':');
            return i > 0 && i < rest.length() - 1
                    ? Optional.of(new ContextItem(rest.substring(0, i), rest.substring(i + 1))) : Optional.empty();
        });
    }

    private static Optional<String> strip(String objectId, String prefix) {
        if (objectId != null && objectId.startsWith(prefix) && objectId.length() > prefix.length()) {
            return Optional.of(objectId.substring(prefix.length()));
        }
        return Optional.empty();
    }
}
