package org.kendar.dlna.shuffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.testutil.Fixtures;
import org.kendar.dlna.testutil.MutableClock;

class ShuffleTest {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final ClientId VLC = new ClientId("10.0.0.2", "VLC");
    private static final ClientId KODI = new ClientId("10.0.0.2", "Kodi");
    private static final ClientId PHONE = new ClientId("10.0.0.9", "VLC");

    private final MutableClock clock = new MutableClock();
    private final List<Track> tracks = new ArrayList<>();
    private final Folder rock = folder("m/Rock", settings(5));
    private final Folder root = folder("m", settings(0));
    private final AtomicReference<Snapshot> catalog = new AtomicReference<>();
    private final Shuffle shuffle;

    ShuffleTest() {
        for (int i = 0; i < 20; i++) {
            tracks.add(track(String.format("m/Rock/%02d.mp3", i)));
        }
        tracks.add(track("m/Other/x.mp3"));
        catalog.set(Fixtures.snapshot(List.of(root, rock, folder("m/Other")), tracks));
        shuffle = new Shuffle(catalog::get, clock, TTL, new Random(42));
    }

    private static DirectorySettings settings(int random) {
        return new DirectorySettings(null, null, null, random, null);
    }

    @Test
    void picksNTracksFromTheSubtreeOnly() {
        RandomSnapshot s = shuffle.open(VLC, rock);

        List<Track> picked = s.tracks(catalog.get());
        assertThat(picked).hasSize(5).doesNotHaveDuplicates();
        assertThat(picked).allMatch(t -> t.ref().rel().startsWith("Rock/"));
        assertThat(s.contextId()).isEqualTo("r." + rock.id() + "." + s.id());
        assertThat(Shuffle.isContext(s.contextId())).isTrue();
        assertThat(Shuffle.title(5)).isEqualTo("[Random 5]");
    }

    @Test
    void fewerTracksThanNGivesAllOfThemShuffled() {
        Folder small = folder("m/Other", settings(50));
        List<Track> picked = shuffle.open(VLC, small).tracks(catalog.get());
        assertThat(picked).containsExactly(tracks.get(20));

        Folder big = folder("m/Rock", settings(100));
        List<Track> all = shuffle.open(KODI, big).tracks(catalog.get());
        assertThat(all).hasSize(20).containsExactlyInAnyOrderElementsOf(tracks.subList(0, 20));
        assertThat(all).isNotEqualTo(tracks.subList(0, 20));
    }

    @Test
    void sameClientGetsTheSameSnapshotAndOrder() {
        RandomSnapshot first = shuffle.open(VLC, rock);
        RandomSnapshot again = shuffle.open(VLC, rock);

        assertThat(again).isSameAs(first);
        assertThat(again.tracks(catalog.get())).isEqualTo(first.tracks(catalog.get()));
        assertThat(shuffle.snapshotCount()).isEqualTo(1);
    }

    @Test
    void clientsAreSeparatedByIpAndUserAgent() {
        RandomSnapshot a = shuffle.open(VLC, rock);
        RandomSnapshot b = shuffle.open(KODI, rock);
        RandomSnapshot c = shuffle.open(PHONE, rock);

        assertThat(List.of(a.id(), b.id(), c.id())).doesNotHaveDuplicates();
        assertThat(shuffle.snapshotCount()).isEqualTo(3);
    }

    @Test
    void sameSeedGivesTheSameOrder() {
        Shuffle one = new Shuffle(catalog::get, clock, TTL, new Random(7));
        Shuffle two = new Shuffle(catalog::get, clock, TTL, new Random(7));
        assertThat(one.open(VLC, rock).tracks(catalog.get())).isEqualTo(two.open(VLC, rock).tracks(catalog.get()));
    }

    @Test
    void expiresAfterTtlWithoutActivityAndNextBrowseCreatesANewSnapshot() {
        RandomSnapshot first = shuffle.open(VLC, rock);

        clock.advance(TTL.plusSeconds(1));
        RandomSnapshot second = shuffle.open(VLC, rock);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(shuffle.knows(first.contextId())).isFalse();   // unreferenced: dropped
        assertThat(shuffle.knows(second.contextId())).isTrue();
    }

    @Test
    void browseOrFindActivityRefreshesTheTtl() {
        RandomSnapshot first = shuffle.open(VLC, rock);

        clock.advance(Duration.ofMinutes(25));
        assertThat(shuffle.find(first.contextId())).isPresent();   // browsing the Random container
        clock.advance(Duration.ofMinutes(25));

        assertThat(shuffle.open(VLC, rock)).isSameAs(first);
        clock.advance(Duration.ofMinutes(25));
        assertThat(shuffle.open(VLC, rock)).isSameAs(first);       // browsing the directory also refreshes
    }

    @Test
    void neverExpiresWhileAStreamIsActiveAndExpiresAfterItEnds() {
        RandomSnapshot first = shuffle.open(VLC, rock);
        ContainerNavigation.Lease lease = shuffle.streamStarted(first.contextId());

        clock.advance(Duration.ofHours(3));
        assertThat(shuffle.open(VLC, rock)).isSameAs(first);
        assertThat(first.activeStreams()).isEqualTo(1);

        lease.close();
        assertThat(first.activeStreams()).isZero();
        clock.advance(TTL.minusMinutes(1));
        assertThat(shuffle.open(VLC, rock)).isSameAs(first);       // the end of the stream counted as activity
        clock.advance(TTL.plusSeconds(1));
        assertThat(shuffle.open(VLC, rock)).isNotSameAs(first);
    }

    @Test
    void supersededSnapshotIsKeptWhileItsStreamIsStillRunning() {
        RandomSnapshot old = shuffle.open(VLC, rock);
        ContainerNavigation.Lease lease = shuffle.streamStarted(old.contextId());
        // a different N replaces the container, the stream of the old snapshot must keep working
        RandomSnapshot replaced = shuffle.open(VLC, folder("m/Rock", settings(3)));

        assertThat(replaced).isNotSameAs(old);
        assertThat(replaced.tracks(catalog.get())).hasSize(3);
        clock.advance(Duration.ofHours(2));
        shuffle.open(KODI, rock);   // triggers a sweep
        assertThat(shuffle.knows(old.contextId())).isTrue();

        lease.close();
        clock.advance(TTL.plusSeconds(1));
        shuffle.open(KODI, rock);
        assertThat(shuffle.knows(old.contextId())).isFalse();
    }

    @Test
    void nextFollowsSnapshotOrderWithoutWrapAround() {
        RandomSnapshot s = shuffle.open(VLC, rock);
        List<Track> picked = s.tracks(catalog.get());

        assertThat(shuffle.next(s.contextId(), picked.get(0).id())).contains(picked.get(1));
        assertThat(shuffle.next(s.contextId(), picked.get(4).id())).isEmpty();
        assertThat(shuffle.next("r.nope.nope", picked.get(0).id())).isEmpty();
    }

    @Test
    void tracksRemovedFromTheCatalogAreSkipped() {
        RandomSnapshot s = shuffle.open(VLC, rock);
        List<Track> picked = s.tracks(catalog.get());
        Track removed = picked.get(2);
        List<Track> remaining = new ArrayList<>(tracks);
        remaining.remove(removed);
        catalog.set(Fixtures.snapshot(List.of(root, rock, folder("m/Other")), remaining));

        assertThat(s.tracks(catalog.get())).hasSize(4).doesNotContain(removed);
        assertThat(shuffle.next(s.contextId(), picked.get(1).id())).contains(picked.get(3));
    }

    @Test
    void streamLeaseOfUnknownContextIsHarmless() {
        shuffle.streamStarted("r.x.y").close();
        assertThat(shuffle.knows("r.x.y")).isFalse();
    }

    @Test
    void unusedExpiredCurrentSnapshotsAreEventuallyDropped() {
        RandomSnapshot s = shuffle.open(VLC, rock);
        clock.advance(Duration.ofHours(25));
        shuffle.open(KODI, rock);
        assertThat(shuffle.knows(s.contextId())).isFalse();
    }
}
