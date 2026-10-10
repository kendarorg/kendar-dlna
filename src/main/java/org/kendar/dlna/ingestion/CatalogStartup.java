package org.kendar.dlna.ingestion;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.CatalogStore;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;

/**
 * Startup sequence (plan section 10): load the persisted index and serve immediately; when it is missing, corrupt,
 * outdated or for other roots, do a full scan first (and persist it). Whether a background catch-up scan is
 * needed is reported, not started, so the caller wires it once the scheduler exists.
 */
public final class CatalogStartup {

    private static final Logger LOG = Logger.getLogger(CatalogStartup.class.getName());

    /** {@code loadedFromIndex}: false when a full scan was needed; {@code catchUpNeeded}: index is stale. */
    public record Result(CatalogService catalog, boolean loadedFromIndex, boolean catchUpNeeded) {
    }

    private CatalogStartup() {
    }

    public static Result start(CatalogStore store, LibraryScanner scanner, Map<String, Path> roots,
            Path playlistsDir, Clock clock, Duration catchUpThreshold) {
        SnapshotHolder holder = new SnapshotHolder();
        CatalogService catalog = new CatalogService(holder, store, clock);
        Optional<Snapshot> loaded = store.load();
        if (loaded.isPresent()) {
            Snapshot s = loaded.get();
            catalog.install(s, false);
            boolean stale = ScanScheduler.catchUpNeeded(s.scannedAt(), catchUpThreshold, clock.instant());
            LOG.info("Catalog loaded from the persisted index: " + s.tracks().size() + " tracks, SystemUpdateID "
                    + s.updateId() + (stale ? " (stale, catch-up scan needed)" : ""));
            return new Result(catalog, true, stale);
        }
        LOG.info("No usable persisted index, running a full scan before serving");
        Snapshot scanned = scanner.scan(roots, playlistsDir, 1);
        catalog.install(scanned, true);
        return new Result(catalog, false, false);
    }
}
