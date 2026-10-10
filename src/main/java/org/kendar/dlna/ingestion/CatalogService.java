package org.kendar.dlna.ingestion;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.CatalogChangeListener;
import org.kendar.dlna.catalog.CatalogStore;
import org.kendar.dlna.catalog.ChangeSet;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotDiff;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;

/**
 * The single writer of the catalog: every change (rescan, removal of a vanished file, later web UI edits) goes
 * through {@link #commit(Snapshot)}, which diffs against the current snapshot, bumps the SystemUpdateID once and
 * the ContainerUpdateIDs of the changed containers, swaps the snapshot atomically, persists the index and
 * notifies the {@link CatalogChangeListener}s.
 */
public final class CatalogService {

    private static final Logger LOG = Logger.getLogger(CatalogService.class.getName());

    private final SnapshotHolder holder;
    private final CatalogStore store;
    private final Clock clock;
    private final List<CatalogChangeListener> listeners = new CopyOnWriteArrayList<>();

    public CatalogService(SnapshotHolder holder, CatalogStore store, Clock clock) {
        this.holder = holder;
        this.store = store;
        this.clock = clock;
    }

    public SnapshotHolder holder() {
        return holder;
    }

    public Snapshot current() {
        return holder.current();
    }

    public void addListener(CatalogChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Commits a staged candidate (content only; its own update ids are ignored). When nothing changed the
     * SystemUpdateID stays and only the last-scan time moves, which is persisted.
     *
     * @return the changed containers (empty = nothing changed)
     */
    public synchronized Set<String> commit(Snapshot candidate) {
        Snapshot old = holder.current();
        Set<String> changed = SnapshotDiff.changedContainers(old, candidate);
        if (changed.isEmpty()) {
            // nothing DLNA-visible changed: ids stay, but the candidate may still differ (unsupported files,
            // directory mtimes) and the last-scan time moves, so it is installed and persisted when different
            if (!candidate.scannedAt().equals(old.scannedAt()) || !sameContent(old, candidate)) {
                Snapshot same = candidate.withUpdates(old.updateId(), candidate.scannedAt(),
                        old.containerUpdateIds());
                holder.commit(same);
                store.save(same);
            }
            return changed;
        }
        long next = old.updateId() + 1;
        Map<String, Long> ids = new HashMap<>(old.containerUpdateIds());
        changed.forEach(k -> ids.put(k, next));
        Snapshot fresh = candidate.withUpdates(next, candidate.scannedAt(), ids);
        holder.commit(fresh);
        store.save(fresh);
        ChangeSet event = new ChangeSet(next, changed);
        for (CatalogChangeListener l : listeners) {
            try {
                l.changed(event);
            } catch (RuntimeException e) {
                LOG.warning("Change listener failed: " + e);
            }
        }
        return changed;
    }

    /**
     * Read-modify-write of the catalog as one atomic step (web UI edits): applies {@code edit} to the current
     * snapshot and commits the result.
     *
     * @return the changed containers (empty = nothing changed)
     */
    public synchronized Set<String> update(java.util.function.UnaryOperator<Snapshot> edit) {
        return commit(edit.apply(holder.current()));
    }

    /**
     * Installs a snapshot loaded from the persisted index or produced by the initial full scan, without diffing
     * or bumping. Saves it when {@code persist}.
     */
    public synchronized void install(Snapshot snapshot, boolean persist) {
        holder.commit(snapshot);
        if (persist) {
            store.save(snapshot);
        }
    }

    /**
     * Removes a track whose file vanished from disk (copy-on-write commit plus persist). Playlist entries
     * pointing to it become missing.
     *
     * @return false when the catalog did not contain it
     */
    public synchronized boolean removeTrack(RootRef ref) {
        Snapshot cur = holder.current();
        Optional<Track> t = cur.track(ref);
        if (t.isEmpty()) {
            return false;
        }
        // the source file of a virtual album is gone: the whole album goes with it
        RootRef file = t.get().fileRef();
        List<Track> tracks = new ArrayList<>(cur.tracks());
        tracks.removeIf(x -> x.ref().equals(ref) || x.fileRef().equals(file));
        List<org.kendar.dlna.catalog.Folder> folders = new ArrayList<>(cur.allFolders());
        folders.removeIf(f -> f.isVirtual() && f.ref().equals(file));
        Snapshot candidate = new Snapshot(cur.updateId(), cur.scannedAt(), folders,
                tracks, cur.unsupported(), cur.playlistDefinitions(), cur.containerUpdateIds());
        commit(candidate);
        LOG.info("Removed vanished file from the catalog: " + ref);
        return true;
    }

    /**
     * Records the measured length of a rendered MIDI file (feedback from transcoding): the exact sample count at
     * the 48 kHz output rate and the duration. Persisted with the index; bumps the update ids of the containers
     * that list the track so clients see the exact size.
     *
     * @param rendered the track as it was when rendering started; ignored when the file changed since
     * @return true when the catalog changed
     */
    public synchronized boolean recordRenderedLength(Track rendered, long sampleCount, long renderedMillis) {
        Snapshot cur = holder.current();
        Track t = cur.track(rendered.ref()).orElse(null);
        if (t == null || t.mtime() != rendered.mtime() || t.size() != rendered.size()) {
            return false;
        }
        if (t.sampleCount() == sampleCount && t.renderedMillis() == renderedMillis) {
            return false;
        }
        Track updated = new Track(t.ref(), t.mtime(), t.size(), t.format(), t.tags(), t.durationMillis(),
                t.bitrateKbps(), t.sampleRate(), t.channels(), t.embeddedArt(), sampleCount, renderedMillis, t.addedAt());
        List<Track> tracks = new ArrayList<>(cur.tracks());
        tracks.replaceAll(x -> x.ref().equals(t.ref()) ? updated : x);
        Snapshot candidate = new Snapshot(cur.updateId(), cur.scannedAt(), new ArrayList<>(cur.allFolders()),
                tracks, cur.unsupported(), cur.playlistDefinitions(), cur.containerUpdateIds());
        return !commit(candidate).isEmpty();
    }

    private static boolean sameContent(Snapshot a, Snapshot b) {
        return new HashSet<>(a.tracks()).equals(new HashSet<>(b.tracks()))
                && new HashSet<>(a.allFolders()).equals(new HashSet<>(b.allFolders()))
                && new HashSet<>(a.unsupported()).equals(new HashSet<>(b.unsupported()));
    }

    /** Clock used for scan timestamps. */
    public Clock clock() {
        return clock;
    }
}
