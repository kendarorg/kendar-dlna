package org.kendar.dlna.delivery;

import java.util.List;
import java.util.Optional;

import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.Track;

/**
 * Resolves a stream URL's container context to the navigation that owns it (folders and playlists, Random N
 * snapshots, view groups): the first delegate that knows the context answers.
 */
public final class CompositeNavigation implements ContainerNavigation {

    private final List<ContainerNavigation> delegates;

    public CompositeNavigation(ContainerNavigation... delegates) {
        this.delegates = List.of(delegates);
    }

    @Override
    public boolean knows(String containerId) {
        return owner(containerId).isPresent();
    }

    @Override
    public Optional<Track> next(String containerId, String itemId) {
        return owner(containerId).flatMap(n -> n.next(containerId, itemId));
    }

    @Override
    public Lease streamStarted(String containerId) {
        return owner(containerId).map(n -> n.streamStarted(containerId)).orElse(Lease.NONE);
    }

    private Optional<ContainerNavigation> owner(String containerId) {
        for (ContainerNavigation n : delegates) {
            if (n.knows(containerId)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }
}
