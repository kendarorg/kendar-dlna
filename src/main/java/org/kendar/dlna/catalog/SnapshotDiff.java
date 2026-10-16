package org.kendar.dlna.catalog;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.kendar.dlna.shared.RootRef;

/**
 * Compares two snapshots and names the containers (by {@link ContainerKeys}) whose content changed. A directory
 * changed when its settings, art, direct tracks or direct subdirectories changed; the virtual containers (Random
 * N, views) of a directory change when anything in its subtree changed. Pure; used to bump ContainerUpdateIDs.
 */
public final class SnapshotDiff {

    private SnapshotDiff() {
    }

    /** The changed container keys; empty when the two snapshots have the same content. */
    public static Set<String> changedContainers(Snapshot before, Snapshot after) {
        return changedContainers(before, after, false);
    }

    /** @param rootNavigations the library roots list configured navigations (whole-library pools) */
    public static Set<String> changedContainers(Snapshot before, Snapshot after, boolean rootNavigations) {
        Set<RootRef> dirty = new HashSet<>();   // directories whose own listing changed
        Set<String> keys = new HashSet<>();

        for (Folder f : after.allFolders()) {
            Folder old = before.folder(f.ref()).orElse(null);
            if (old == null) {
                dirty.add(f.ref());
                markParent(f.ref(), dirty);
            } else if (!sameFolder(old, f)) {
                dirty.add(f.ref());
                markParent(f.ref(), dirty);   // its name / order / art are listed by the parent
                if (f.isRoot()) {
                    keys.add(ContainerKeys.FOLDERS);   // a root has no parent folder: the Folders list shows it
                }
            }
        }
        for (Folder f : before.allFolders()) {
            if (after.folder(f.ref()).isEmpty()) {
                keys.add(ContainerKeys.folder(f.id()));
                markParent(f.ref(), dirty);
            }
        }
        for (Track t : after.tracks()) {
            Track old = before.track(t.ref()).orElse(null);
            if (!t.equals(old)) {
                dirty.add(t.parentRef());
            }
        }
        for (Track t : before.tracks()) {
            if (after.track(t.ref()).isEmpty()) {
                dirty.add(t.parentRef());
            }
        }

        Set<RootRef> virtualDirty = new HashSet<>();
        for (RootRef d : dirty) {
            Folder f = after.folder(d).orElse(null);
            if (f != null) {
                keys.add(ContainerKeys.folder(f.id()));
            }
            Folder o = before.folder(d).orElse(null);
            if (o != null) {
                keys.add(ContainerKeys.folder(o.id()));
            }
            // this directory and every ancestor: their pools (Random N, views) include the changed content
            for (RootRef a = d;; a = a.parent()) {
                virtualDirty.add(a);
                if (a.isRoot()) {
                    break;
                }
            }
        }
        for (RootRef a : virtualDirty) {
            Folder fa = after.folder(a).orElse(null);
            Folder fb = before.folder(a).orElse(null);
            if ((fa != null && hasVirtual(after, fa, rootNavigations)) || (fb != null && hasVirtual(before, fb, rootNavigations))) {
                keys.add(ContainerKeys.virtual((fa != null ? fa : fb).id()));
            }
        }

        if (!rootRefs(before).equals(rootRefs(after))) {
            keys.add(ContainerKeys.FOLDERS);
        }
        playlistChanges(before, after, keys);
        return keys;
    }

    private static void markParent(RootRef ref, Set<RootRef> dirty) {
        if (!ref.isRoot()) {
            dirty.add(ref.parent());
        }
    }

    private static boolean hasVirtual(Snapshot snap, Folder f, boolean rootNavigations) {
        // library roots show every configured navigation by default; other directories when they list or inherit some
        return f.settings().random() > 0 || f.settings().recent() > 0 || !f.settings().views().isEmpty()
                || (f.isRoot() && rootNavigations) || snap.effectiveSettings(f.ref()).navigations() != null;
    }

    /** Folder equality ignoring the directory mtime unless it drives the sort order. */
    private static boolean sameFolder(Folder a, Folder b) {
        if (a.settings().sort() == SortMode.MTIME || b.settings().sort() == SortMode.MTIME) {
            if (a.mtime() != b.mtime()) {
                return false;
            }
        }
        return a.settings().equals(b.settings()) && Objects.equals(a.settingsError(), b.settingsError())
                && a.hasFront() == b.hasFront() && a.hasRear() == b.hasRear();
    }

    private static List<RootRef> rootRefs(Snapshot s) {
        return s.roots().stream().map(Folder::ref).toList();
    }

    private static void playlistChanges(Snapshot before, Snapshot after, Set<String> keys) {
        boolean listChanged = before.playlists().size() != after.playlists().size();
        for (int i = 0; !listChanged && i < after.playlists().size(); i++) {
            ResolvedPlaylist a = after.playlists().get(i);
            ResolvedPlaylist b = before.playlists().get(i);
            listChanged = !a.id().equals(b.id()) || !a.name().equals(b.name());
        }
        if (listChanged) {
            keys.add(ContainerKeys.PLAYLISTS);
        }
        for (ResolvedPlaylist p : after.playlists()) {
            ResolvedPlaylist old = before.playlist(p.id()).orElse(null);
            if (!p.equals(old)) {
                keys.add(ContainerKeys.playlist(p.id()));
            }
        }
        for (ResolvedPlaylist p : before.playlists()) {
            if (after.playlist(p.id()).isEmpty()) {
                keys.add(ContainerKeys.playlist(p.id()));
            }
        }
    }
}
