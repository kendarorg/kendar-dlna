package org.kendar.dlna.ingestion;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.shared.RootRef;

/**
 * Service-level API for rescans (manual, scheduled, catch-up): incremental (mtime + size, or forced: every file re-read), whole library or a
 * single directory, staged and committed at the end through the {@link CatalogService}. Scans never overlap:
 * starting one while another runs fails with {@link ScanAlreadyRunningException}. {@link #status()} reports live
 * progress and the last result.
 */
public final class RescanService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RescanService.class.getName());

    private final LibraryScanner scanner;
    private final CatalogService catalog;
    private final Map<String, Path> roots;
    private final Path playlistsDir;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rescan");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private boolean running;
    private ScanTrigger runningTrigger;
    private RootRef runningScope;
    private Instant runningSince;
    private ScanProgress progress = new ScanProgress();
    private ScanReport lastReport;

    public RescanService(LibraryScanner scanner, CatalogService catalog, Map<String, Path> roots, Path playlistsDir,
            Clock clock) {
        this.scanner = scanner;
        this.catalog = catalog;
        this.roots = roots;
        this.playlistsDir = playlistsDir;
        this.clock = clock;
    }

    /** Starts an asynchronous rescan of the whole library. */
    public CompletableFuture<ScanReport> rescanAll(ScanTrigger trigger) {
        return rescanAll(trigger, false);
    }

    /** @param force re-read the tags of every file, ignoring the mtime + size reuse (first-seen times are kept) */
    public CompletableFuture<ScanReport> rescanAll(ScanTrigger trigger, boolean force) {
        return start(trigger, null, force);
    }

    /**
     * Starts an asynchronous rescan of one directory's subtree.
     *
     * @throws IllegalArgumentException for an unknown root or directory (rescan everything instead)
     */
    public CompletableFuture<ScanReport> rescanFolder(RootRef folder) {
        return rescanFolder(folder, false);
    }

    /** As {@link #rescanFolder(RootRef)}; {@code force} re-reads every file of the subtree. */
    public CompletableFuture<ScanReport> rescanFolder(RootRef folder, boolean force) {
        Snapshot cur = catalog.current();
        if (!roots.containsKey(folder.root()) || (cur.folder(folder).isEmpty()
                && (folder.isRoot() || cur.folder(folder.parent()).isEmpty()))) {
            throw new IllegalArgumentException("Unknown directory: " + folder);
        }
        return start(ScanTrigger.MANUAL, folder, force);
    }

    /** Runs a rescan on the calling thread; same exclusion rules as the asynchronous variants. */
    public ScanReport rescanNow(ScanTrigger trigger, RootRef scope) {
        return rescanNow(trigger, scope, false);
    }

    public ScanReport rescanNow(ScanTrigger trigger, RootRef scope, boolean force) {
        begin(trigger, scope);
        return execute(trigger, scope, force);
    }

    private CompletableFuture<ScanReport> start(ScanTrigger trigger, RootRef scope, boolean force) {
        begin(trigger, scope);
        return CompletableFuture.supplyAsync(() -> execute(trigger, scope, force), executor);
    }

    private void begin(ScanTrigger trigger, RootRef scope) {
        synchronized (lock) {
            if (running) {
                throw new ScanAlreadyRunningException();
            }
            running = true;
            runningTrigger = trigger;
            runningScope = scope;
            runningSince = clock.instant();
            progress = new ScanProgress();
        }
    }

    private ScanReport execute(ScanTrigger trigger, RootRef scope, boolean force) {
        ScanProgress p;
        Instant started;
        synchronized (lock) {
            p = progress;
            started = runningSince;
        }
        int before = catalog.current().tracks().size();
        ScanReport report;
        try {
            Snapshot candidate = scanner.rescan(roots, playlistsDir, catalog.current(), scope, p, force);
            Set<String> changed = catalog.commit(candidate);
            report = new ScanReport(trigger, scope, started, clock.instant(), p.directories(), p.audioFiles(),
                    p.tracksReused(), p.tracksRead(), before, catalog.current().tracks().size(), changed,
                    p.errors(), null);
            LOG.info("Rescan " + (force ? "(forced, all tags re-read) " : "") + (scope == null ? "of the library" : "of " + scope) + " done: " + changed.size()
                    + " containers changed, " + p.tracksReused() + " tracks reused, " + p.tracksRead() + " read");
        } catch (RuntimeException e) {
            LOG.warning("Rescan failed, catalog unchanged: " + e);
            report = new ScanReport(trigger, scope, started, clock.instant(), p.directories(), p.audioFiles(),
                    p.tracksReused(), p.tracksRead(), before, before, Set.of(), p.errors(), e.toString());
        }
        synchronized (lock) {
            lastReport = report;
            running = false;
            runningTrigger = null;
            runningScope = null;
        }
        return report;
    }

    public ScanStatus status() {
        synchronized (lock) {
            if (!running) {
                return new ScanStatus(false, null, null, null, 0, 0, 0, 0, List.of(), lastReport);
            }
            return new ScanStatus(true, runningTrigger, runningScope, runningSince, progress.directories(),
                    progress.audioFiles(), progress.tracksReused(), progress.tracksRead(), progress.errors(),
                    lastReport);
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
