package org.kendar.dlna.catalog;

import java.util.Optional;

/**
 * Navigation inside a browsing context (the container id carried by a stream URL): folder order, playlist order,
 * Random N snapshot order or view order. Implemented by the catalog (folders, playlists), Shuffle and Views and
 * consumed by Delivery. Context ids are opaque strings to the consumer.
 */
public interface ContainerNavigation {

    /** An open stream in a context; closing it ends the activity. */
    interface Lease extends AutoCloseable {
        Lease NONE = () -> {
        };

        @Override
        void close();
    }

    /** Navigation that knows no context. */
    ContainerNavigation NONE = new ContainerNavigation() {
        @Override
        public boolean knows(String containerId) {
            return false;
        }

        @Override
        public Optional<Track> next(String containerId, String itemId) {
            return Optional.empty();
        }
    };

    /** Whether this navigation owns the context and the context currently exists. */
    boolean knows(String containerId);

    /** The item after {@code itemId} in the context; empty at the end (no wrap-around) or for unknown input. */
    Optional<Track> next(String containerId, String itemId);

    /** Reports that a stream of the context started; closing the lease reports its end. */
    default Lease streamStarted(String containerId) {
        return Lease.NONE;
    }
}
