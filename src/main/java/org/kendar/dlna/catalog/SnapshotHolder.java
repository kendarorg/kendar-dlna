package org.kendar.dlna.catalog;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the current {@link Snapshot}; readers call {@link #current()}, writers {@link #commit(Snapshot)}. */
public final class SnapshotHolder {

    private final AtomicReference<Snapshot> ref;

    public SnapshotHolder() {
        this(Snapshot.empty());
    }

    public SnapshotHolder(Snapshot initial) {
        this.ref = new AtomicReference<>(Objects.requireNonNull(initial));
    }

    public Snapshot current() {
        return ref.get();
    }

    /** Atomically replaces the snapshot and returns the previous one. */
    public Snapshot commit(Snapshot next) {
        return ref.getAndSet(Objects.requireNonNull(next));
    }
}
