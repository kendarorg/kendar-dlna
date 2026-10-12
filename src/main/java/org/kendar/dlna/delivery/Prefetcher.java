package org.kendar.dlna.delivery;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;

/**
 * Next-track prefetch (plan section 11): when a client starts item N of a browsing context, the next item of that
 * context (folder, playlist, Random N or view order; no wrap-around) is transcoded into the cache ahead of time if
 * it needs transcoding. Runs on a small low-priority pool; a pending job is cancelled when the client moves on to a
 * different item. Client queues are ignored.
 */
public final class Prefetcher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Prefetcher.class.getName());

    /** Flags shared by the queued task and whoever cancels it; the bookkeeping is released exactly once. */
    private static final class State {
        final String dedupe;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean done = new AtomicBoolean();

        State(String dedupe) {
            this.dedupe = dedupe;
        }
    }

    private record Job(String trackId, State state, Future<?> future) {
    }

    private final PrefetchTarget target;
    private final ContainerNavigation navigation;
    private final MediaFiles files;
    private final ExecutorService executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final AtomicInteger running = new AtomicInteger();

    /**
     * @param executor runs the jobs; use {@link #lowPriorityPool(int)} in production
     */
    public Prefetcher(PrefetchTarget target, ContainerNavigation navigation, MediaFiles files,
            ExecutorService executor) {
        this.target = target;
        this.navigation = navigation;
        this.files = files;
        this.executor = executor;
    }

    /** A pool of daemon threads at minimum priority whose cancelled tasks leave the queue. */
    public static ExecutorService lowPriorityPool(int threads) {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(threads, r -> {
            Thread t = new Thread(r, "prefetch");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    /**
     * A transcoded or native stream of {@code current} started in {@code containerId}.
     *
     * @param clientKey identifies the client (address); its earlier pending job is cancelled unless it targets the
     *                  item now being played
     * @param variant   what the client is playing; a transcoded variant is applied to the next item as well
     */
    public void streamStarted(String clientKey, String containerId, Track current, StreamVariant variant) {
        String jobKey = clientKey + "|" + containerId;
        Job previous = jobs.get(jobKey);
        if (previous != null && !previous.trackId().equals(current.id())) {
            cancel(jobKey, previous);
        }
        Optional<Track> next;
        try {
            next = navigation.next(containerId, current.id());
        } catch (RuntimeException e) {
            return;
        }
        if (next.isEmpty()) {
            return;
        }
        Track n = next.get();
        StreamVariant v = variantFor(n, variant);
        if (v == null) {
            return;
        }
        schedule(jobKey, n, v);
    }

    /**
     * An explicit hint (web player): produce {@code track} in {@code variant} into the cache when it is not cached
     * yet. A pending job of the same {@code jobKey} for another track is replaced.
     *
     * @return true when the form is being (or about to be) produced, false when nothing needs to be done
     */
    public boolean hint(String jobKey, Track track, StreamVariant variant) {
        return schedule(jobKey, track, variant);
    }

    private boolean schedule(String jobKey, Track n, StreamVariant v) {
        if (!target.needsPrefetch(n, v)) {
            return false;
        }
        Path file = files.resolve(n.ref());
        if (file == null) {
            return false;
        }
        String dedupe = n.id() + "|" + v.path();
        if (!pending.add(dedupe)) {
            return true;   // already being produced
        }
        Job existing = jobs.get(jobKey);
        if (existing != null) {
            cancel(jobKey, existing);
        }
        State state = new State(dedupe);
        running.incrementAndGet();
        try {
            Future<?> f = executor.submit(() -> run(n, file, v, state));
            jobs.put(jobKey, new Job(n.id(), state, f));
            return true;
        } catch (RejectedExecutionException e) {
            release(state);
            return false;
        }
    }

    /** Which form to prefetch for the next item: null when the client plays it natively anyway. */
    private StreamVariant variantFor(Track next, StreamVariant current) {
        if (next.format().rendered()) {
            return current.transcoded() ? current : StreamVariant.mp3(0);
        }
        if (!current.transcoded() || next.format() == Format.MP3) {
            return null;
        }
        return current;
    }

    private void release(State state) {
        if (state.done.compareAndSet(false, true)) {
            pending.remove(state.dedupe);
            running.decrementAndGet();
        }
    }

    private void run(Track track, Path file, StreamVariant v, State state) {
        try {
            if (!state.cancelled.get()) {
                target.prefetch(track, file, v, state.cancelled::get);
            }
        } catch (InterruptedIOException e) {
            LOG.fine("Prefetch cancelled: " + track.ref());
        } catch (IOException | RuntimeException e) {
            LOG.warning("Prefetch failed for " + track.ref() + ": " + e);
        } finally {
            release(state);
        }
    }

    private void cancel(String jobKey, Job job) {
        job.state().cancelled.set(true);
        if (job.future().cancel(true)) {
            // a task removed from the queue never runs: release here (no-op when it already ran)
            release(job.state());
        }
        jobs.remove(jobKey, job);
    }

    /** Number of jobs queued or running. */
    public int activeJobs() {
        return running.get();
    }

    /** Waits until no job is queued or running (for tests and shutdown). */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (running.get() > 0) {
            if (System.nanoTime() > end) {
                return false;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return true;
    }

    @Override
    public void close() {
        jobs.values().forEach(j -> {
            j.state().cancelled.set(true);
            j.future().cancel(true);
        });
        jobs.clear();
        executor.shutdownNow();
    }
}
