package org.kendar.dlna.delivery;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.CatalogChangeListener;
import org.kendar.dlna.catalog.ChangeSet;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;

/**
 * Drops cached transcodes whose source file was changed or removed (tag write, rescan finding a new version) when
 * the catalog commits a change. A changed file also gets a new cache key (its mtime and size are part of it), so
 * this is about reclaiming space promptly, never about correctness.
 */
public final class TranscodeCacheInvalidator implements CatalogChangeListener {

    private static final Logger LOG = Logger.getLogger(TranscodeCacheInvalidator.class.getName());

    private final TranscodeCache cache;
    private final Supplier<Snapshot> snapshot;

    public TranscodeCacheInvalidator(TranscodeCache cache, Supplier<Snapshot> snapshot) {
        this.cache = cache;
        this.snapshot = snapshot;
    }

    @Override
    public void changed(ChangeSet change) {
        Snapshot snap = snapshot.get();
        int removed = cache.invalidate(src -> {
            Optional<Track> t = snap.track(RootRef.parse(src.ref()));
            return t.isEmpty() || t.get().mtime() != src.mtime() || t.get().size() != src.size();
        });
        if (removed > 0) {
            LOG.info("Dropped " + removed + " stale transcode cache entries");
        }
    }
}
