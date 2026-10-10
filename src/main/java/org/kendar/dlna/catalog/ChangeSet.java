package org.kendar.dlna.catalog;

import java.util.Set;

/**
 * What a committed change touched. {@code containerKeys} use the catalog's container keys, see
 * {@link ContainerKeys}. {@code systemUpdateId} is the new SystemUpdateID.
 */
public record ChangeSet(long systemUpdateId, Set<String> containerKeys) {

    public ChangeSet {
        containerKeys = Set.copyOf(containerKeys);
    }
}
