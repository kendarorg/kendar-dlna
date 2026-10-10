package org.kendar.dlna.shuffle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shared.RootRef;

/**
 * One Random N snapshot: the fixed, ordered pick of up to N tracks for a (client, directory) pair. Tracks are
 * kept as references and resolved against the current catalog snapshot, so entries removed by a rescan are
 * skipped instead of breaking the container.
 */
public final class RandomSnapshot {

    private final String id;
    private final String directoryId;
    private final ClientId client;
    private final int size;
    private final List<RootRef> refs;
    private final Instant createdAt;
    private volatile Instant lastActivity;
    private final AtomicInteger activeStreams = new AtomicInteger();

    RandomSnapshot(String id, String directoryId, ClientId client, int size, List<RootRef> refs, Instant now) {
        this.id = id;
        this.directoryId = directoryId;
        this.client = client;
        this.size = size;
        this.refs = List.copyOf(refs);
        this.createdAt = now;
        this.lastActivity = now;
    }

    /** The snapshot id (embedded in the context id, item ids and stream URLs). */
    public String id() {
        return id;
    }

    public String directoryId() {
        return directoryId;
    }

    public ClientId client() {
        return client;
    }

    /** The N this snapshot was created for. */
    public int requestedSize() {
        return size;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    public int activeStreams() {
        return activeStreams.get();
    }

    /** The context id of this snapshot, as used in stream URLs. */
    public String contextId() {
        return Shuffle.contextId(directoryId, id);
    }

    /** The picked tracks, in order, as far as they still exist in the catalog. */
    public List<Track> tracks(Snapshot catalog) {
        return refs.stream().map(catalog::track).flatMap(Optional::stream).toList();
    }

    void touch(Instant now) {
        lastActivity = now;
    }

    void streamOpened() {
        activeStreams.incrementAndGet();
    }

    void streamClosed() {
        activeStreams.updateAndGet(v -> Math.max(0, v - 1));
    }
}
