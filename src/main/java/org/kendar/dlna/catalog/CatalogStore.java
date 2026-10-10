package org.kendar.dlna.catalog;

import java.util.Optional;

/**
 * Port for persisting the full catalog (a JSON index in a later milestone; replaceable by a database).
 * Implementations live outside the catalog context.
 */
public interface CatalogStore {

    /** The persisted snapshot, or empty when none is available (missing, corrupt, outdated). */
    Optional<Snapshot> load();

    void save(Snapshot snapshot);

    /** A store that persists nothing. */
    static CatalogStore none() {
        return new CatalogStore() {
            @Override
            public Optional<Snapshot> load() {
                return Optional.empty();
            }

            @Override
            public void save(Snapshot snapshot) {
                // nothing to do
            }
        };
    }
}
