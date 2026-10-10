package org.kendar.dlna.catalog;

import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.shared.StableIds;

/**
 * A directory. {@code settingsError} is non-null when its settings.json was malformed (defaults are then used); {@code listHash} identifies the content of its
 * list.json (null = none) and {@code listError} is non-null when that file could not be parsed at all.
 */
public record Folder(RootRef ref, long mtime, DirectorySettings settings, String settingsError, boolean hasFront,
        boolean hasRear, String listHash, String listError, Split split) {

    public Folder {
        settings = settings == null ? DirectorySettings.DEFAULT : settings;
    }

    /** A directory (no split marker). */
    public Folder(RootRef ref, long mtime, DirectorySettings settings, String settingsError, boolean hasFront,
            boolean hasRear, String listHash, String listError) {
        this(ref, mtime, settings, settingsError, hasFront, hasRear, listHash, listError, null);
    }

    /** True for a virtual album container (one audio file split into tracks); it is not a directory on disk. */
    public boolean isVirtual() {
        return split != null;
    }

    /** Without list.json information. */
    public Folder(RootRef ref, long mtime, DirectorySettings settings, String settingsError, boolean hasFront,
            boolean hasRear) {
        this(ref, mtime, settings, settingsError, hasFront, hasRear, null, null);
    }

    /** The same folder with the list.json state of a (re)read. */
    public Folder withList(String hash, String error) {
        return new Folder(ref, mtime, settings, settingsError, hasFront, hasRear, hash, error, split);
    }

    public static String idOf(RootRef ref) {
        return StableIds.forPath("f", ref);
    }

    public String id() {
        return idOf(ref);
    }

    public boolean isRoot() {
        return ref.isRoot();
    }
}
