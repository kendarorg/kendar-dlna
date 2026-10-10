package org.kendar.dlna.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Internal scheduler of the periodic rescan (cron) and of the startup catch-up scan. The decisions are plain
 * methods ({@link #catchUpNeeded}, {@link #fire}) so they can be tested without waiting for real time.
 */
public final class ScanScheduler implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ScanScheduler.class.getName());

    private final RescanService rescans;
    private final CronExpression cron;
    private final Clock clock;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "scan-scheduler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending;
    private boolean closed;

    public ScanScheduler(RescanService rescans, CronExpression cron, Clock clock) {
        this.rescans = rescans;
        this.cron = cron;
        this.clock = clock;
    }

    /** Whether the last completed scan is older than the threshold (so a background scan should run now). */
    public static boolean catchUpNeeded(Instant lastCompletedScan, Duration threshold, Instant now) {
        return Duration.between(lastCompletedScan, now).compareTo(threshold) > 0;
    }

    /** Starts the catch-up scan in the background when needed; returns whether it was started. */
    public boolean catchUpIfStale(Instant lastCompletedScan, Duration threshold) {
        if (!catchUpNeeded(lastCompletedScan, threshold, clock.instant())) {
            return false;
        }
        LOG.info("Last completed scan is older than " + threshold + ", starting a background catch-up scan");
        return trigger(ScanTrigger.CATCH_UP);
    }

    /** The next cron fire time after now. */
    public Instant nextFire() {
        return cron.next(clock.instant(), clock.getZone());
    }

    /** Arms the timer for the next cron fire; each fire re-arms it. */
    public synchronized void start() {
        schedule();
    }

    private void schedule() {
        if (closed) {
            return;
        }
        Duration delay = Duration.between(clock.instant(), nextFire());
        pending = timer.schedule(this::fire, Math.max(delay.toMillis(), 1000), TimeUnit.MILLISECONDS);
        LOG.info("Next scheduled rescan (" + cron + ") at " + nextFire());
    }

    /** One scheduled fire: starts a rescan (skipped when one runs) and re-arms. */
    void fire() {
        try {
            trigger(ScanTrigger.SCHEDULED);
        } finally {
            synchronized (this) {
                schedule();
            }
        }
    }

    private boolean trigger(ScanTrigger trigger) {
        try {
            rescans.rescanAll(trigger);
            return true;
        } catch (ScanAlreadyRunningException e) {
            LOG.info("Skipping " + trigger + " rescan: a scan is already running");
            return false;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) {
            pending.cancel(false);
        }
        timer.shutdownNow();
    }
}
