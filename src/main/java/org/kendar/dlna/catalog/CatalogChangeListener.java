package org.kendar.dlna.catalog;

/** Receives the containers that changed with every committed catalog change (see {@link ChangeSet}). */
@FunctionalInterface
public interface CatalogChangeListener {

    /** Called after the new snapshot is in place, on the committing thread. Must not throw. */
    void changed(ChangeSet change);
}
