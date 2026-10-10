package org.kendar.dlna.dlna;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.kendar.dlna.catalog.ChangeSet;
import org.kendar.dlna.catalog.ContainerKeys;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.catalog.CatalogChangeListener;
import org.kendar.dlna.views.Views;

/**
 * Maps the catalog's changed containers to DLNA object ids and builds the value of the evented
 * {@code ContainerUpdateIDs} state variable ({@code id,updateId,id,updateId,...}). Random N containers are
 * per-client and not announced; the view roots of a directory are.
 */
final class UpdateEvents {

    private UpdateEvents() {
    }

    /** The {@code ContainerUpdateIDs} value for a change, using the update ids of the new snapshot. */
    static String containerUpdateIds(Snapshot snap, ChangeSet change) {
        List<String> parts = new ArrayList<>();
        for (String key : new TreeSet<>(change.containerKeys())) {
            for (String objectId : objectIds(snap, key)) {
                parts.add(objectId);
                parts.add(Long.toString(snap.containerUpdateId(key)));
            }
        }
        return String.join(",", parts);
    }

    /** DLNA object ids announced for a catalog container key. */
    static List<String> objectIds(Snapshot snap, String key) {
        if (ContainerKeys.FOLDERS.equals(key)) {
            return List.of(ObjectIds.FOLDERS);
        }
        if (ContainerKeys.PLAYLISTS.equals(key)) {
            return List.of(ObjectIds.PLAYLISTS);
        }
        if (key.startsWith("p.")) {
            return List.of(ObjectIds.container(key));
        }
        if (key.startsWith("virt:")) {
            String folderId = key.substring("virt:".length());
            Folder f = snap.folderById(folderId).orElse(null);
            if (f == null) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            if (f.settings().recent() > 0) {
                ids.add(ObjectIds.container(Views.recentId(f)));
            }
            for (ViewKind kind : f.settings().views()) {
                ids.add(ObjectIds.container(Views.rootId(f, kind)));
            }
            return ids;
        }
        return List.of(ObjectIds.folderOf(key));
    }

    /** A listener that announces every change through the content directory service instance. */
    static CatalogChangeListener announcer(java.util.function.Supplier<Snapshot> snapshot,
            java.util.function.Supplier<LibraryContentDirectory> service) {
        return change -> {
            LibraryContentDirectory cd = service.get();
            if (cd != null) {
                cd.announce(containerUpdateIds(snapshot.get(), change));
            }
        };
    }
}
