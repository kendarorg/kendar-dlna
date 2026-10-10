package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.Track;

class PrefetcherTest {

    private final Track t1 = track("m/1.flac");
    private final Track t2 = track("m/2.flac");
    private final Track t3 = track("m/3.mp3");
    private final Track t4 = track("m/4.mid");
    private final List<Track> order = List.of(t1, t2, t3, t4);

    private final ContainerNavigation nav = new ContainerNavigation() {
        @Override
        public boolean knows(String containerId) {
            return true;
        }

        @Override
        public Optional<Track> next(String containerId, String itemId) {
            for (int i = 0; i < order.size() - 1; i++) {
                if (order.get(i).id().equals(itemId)) {
                    return Optional.of(order.get(i + 1));
                }
            }
            return Optional.empty();   // last item: no wrap-around
        }
    };

    /** Records what would be produced; a track can be made to block until released. */
    private static final class FakeTarget implements PrefetchTarget {
        final List<String> filled = new CopyOnWriteArrayList<>();
        final List<String> cancelled = new CopyOnWriteArrayList<>();
        final List<Track> cached = new CopyOnWriteArrayList<>();
        volatile Track blockOn;
        final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public boolean needsPrefetch(Track track, StreamVariant variant) {
            return variant.transcoded() && !cached.contains(track);
        }

        @Override
        public void prefetch(Track track, Path file, StreamVariant variant, BooleanSupplier isCancelled)
                throws InterruptedIOException {
            if (track.equals(blockOn)) {
                entered.countDown();
                try {
                    while (!isCancelled.getAsBoolean()) {
                        TimeUnit.MILLISECONDS.sleep(5);
                    }
                } catch (InterruptedException e) {
                    // cancelled by interrupt
                }
                cancelled.add(track.id());
                throw new InterruptedIOException("cancelled");
            }
            filled.add(track.id() + "|" + variant.path());
            cached.add(track);
        }
    }

    private final FakeTarget target = new FakeTarget();
    private final ExecutorService pool = Prefetcher.lowPriorityPool(1);
    private final Prefetcher prefetcher = new Prefetcher(target, nav, ref -> Path.of(ref.rel()), pool);

    @AfterEach
    void close() {
        prefetcher.close();
    }

    @Test
    void streamStartOfItemNEncodesItemNPlusOne() throws Exception {
        prefetcher.streamStarted("c1", "ctx", t1, StreamVariant.mp3(0));
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(target.filled).containsExactly(t2.id() + "|mp3");
        // repeating the start (range requests) does not produce it again
        prefetcher.streamStarted("c1", "ctx", t1, StreamVariant.mp3(0));
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(target.filled).hasSize(1);
    }

    @Test
    void theTranscodeVariantOfTheCurrentStreamIsAppliedToTheNextItem() throws Exception {
        prefetcher.streamStarted("c1", "ctx", t1, StreamVariant.LPCM);
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(target.filled).containsExactly(t2.id() + "|lpcm");
    }

    @Test
    void nextItemsThatNeedNoTranscodingAreSkipped() throws Exception {
        prefetcher.streamStarted("c1", "ctx", t2, StreamVariant.mp3(0));   // next is an MP3 original
        prefetcher.streamStarted("c2", "ctx", t1, StreamVariant.NATIVE);   // client plays FLAC natively
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(target.filled).isEmpty();
    }

    @Test
    void midiNextIsAlwaysRenderedEvenWhenTheCurrentStreamIsNative() throws Exception {
        prefetcher.streamStarted("c1", "ctx", t3, StreamVariant.NATIVE);
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(target.filled).containsExactly(t4.id() + "|mp3");
    }

    @Test
    void thereIsNoWrapAroundAtTheEnd() throws Exception {
        prefetcher.streamStarted("c1", "ctx", t4, StreamVariant.mp3(0));
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(target.filled).isEmpty();
    }

    @Test
    void alreadyCachedNextItemsAreNotProducedAgain() throws Exception {
        target.cached.add(t2);
        prefetcher.streamStarted("c1", "ctx", t1, StreamVariant.mp3(0));
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(target.filled).isEmpty();
    }

    @Test
    void aPendingJobIsCancelledWhenTheClientMovesOnElsewhere() throws Exception {
        target.blockOn = t2;
        prefetcher.streamStarted("c1", "ctx", t1, StreamVariant.mp3(0));
        assertThat(target.entered.await(5, TimeUnit.SECONDS)).isTrue();
        // the client jumps to item 3 instead of 2: the encode of 2 is abandoned
        prefetcher.streamStarted("c1", "ctx", t3, StreamVariant.mp3(0));
        assertThat(prefetcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        for (int i = 0; i < 200 && target.cancelled.isEmpty(); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(target.cancelled).containsExactly(t2.id());
        assertThat(target.filled).contains(t4.id() + "|mp3");   // and the MIDI after item 3 is prepared instead
        assertThat(target.cached).doesNotContain(t2);
    }

    @Test
    void aJobForTheItemTheClientNowPlaysKeepsRunning() throws Exception {
        target.blockOn = t2;
        prefetcher.streamStarted("c1", "ctx", t1, StreamVariant.mp3(0));
        assertThat(target.entered.await(5, TimeUnit.SECONDS)).isTrue();
        // the client reached item 2 while its prefetch is still running: not cancelled
        prefetcher.streamStarted("c1", "ctx", t2, StreamVariant.mp3(0));
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(target.cancelled).isEmpty();
        assertThat(prefetcher.activeJobs()).isPositive();
    }

    @Test
    void differentClientsDoNotCancelEachOther() throws Exception {
        target.blockOn = t2;
        prefetcher.streamStarted("c1", "ctx", t1, StreamVariant.mp3(0));
        assertThat(target.entered.await(5, TimeUnit.SECONDS)).isTrue();
        prefetcher.streamStarted("c2", "ctx", t3, StreamVariant.mp3(0));
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(target.cancelled).isEmpty();
    }

    @Test
    void unknownFilesAndRejectedPoolsAreIgnored() throws Exception {
        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdown();
        try (Prefetcher p = new Prefetcher(target, nav, ref -> Path.of(ref.rel()), dead)) {
            p.streamStarted("c1", "ctx", t1, StreamVariant.mp3(0));
            assertThat(p.activeJobs()).isZero();
        }
        try (Prefetcher p = new Prefetcher(target, nav, ref -> null, Executors.newSingleThreadExecutor())) {
            p.streamStarted("c1", "ctx", t1, StreamVariant.mp3(0));
            assertThat(p.activeJobs()).isZero();
        }
    }
}
