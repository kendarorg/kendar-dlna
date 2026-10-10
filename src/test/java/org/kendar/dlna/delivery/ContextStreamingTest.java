package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.CatalogNavigation;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shuffle.RandomSnapshot;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.MutableClock;
import org.kendar.dlna.views.ViewNode;
import org.kendar.dlna.views.Views;

/** Container context resolution of stream URLs: playlists, Random N snapshots, view groups. */
class ContextStreamingTest {

    @TempDir
    Path dir;

    final MutableClock clock = new MutableClock();
    Track a;
    Track b;
    Track c;
    Snapshot snap;
    Shuffle shuffle;
    Views views;
    StreamResolver resolver;
    Folder root;

    @BeforeEach
    void setUp() throws IOException {
        root = folder("m", new DirectorySettings(null, null, null, 2, List.of(ViewKind.TITLE)));
        a = track("m/a.mp3");
        b = track("m/b.mp3");
        c = track("m/c.mp3");
        for (String n : List.of("a.mp3", "b.mp3", "c.mp3")) {
            Files.write(dir.resolve(n), new byte[100]);
        }
        snap = new Snapshot(1, Instant.EPOCH, List.of(root), List.of(a, b, c), List.<UnsupportedFile>of(),
                List.of(new Playlist("mix", "Mix", List.of("m/c.mp3", "m/a.mp3"))));
        shuffle = new Shuffle(() -> snap, clock, Duration.ofMinutes(30), new Random(1));
        views = new Views(() -> snap);
        resolver = new StreamResolver(() -> snap, ref -> dir.resolve(ref.rel()), new ArrayList<>()::add,
                new CompositeNavigation(new CatalogNavigation(() -> snap), shuffle, views));
    }

    private static String path(String ctx, Track t) {
        return "/" + ctx + "/" + t.id();
    }

    @Test
    void folderContextsKeepWorkingWithoutValidation() {
        assertThat(resolver.resolve("GET", path(Folder.idOf(a.parentRef()), a), null).plan().status()).isEqualTo(200);
        assertThat(resolver.resolve("GET", path("anything", a), null).plan().status()).isEqualTo(200);
    }

    @Test
    void playlistContextStreamsAndNavigatesInPlaylistOrder() {
        String ctx = CatalogNavigation.playlistContext(Playlist.idOf("mix"));

        assertThat(resolver.resolve("GET", path(ctx, c), null).plan().status()).isEqualTo(200);
        assertThat(resolver.nextInContext(ctx, c.id())).contains(a);
        assertThat(resolver.nextInContext(ctx, a.id())).isEmpty();
        assertThat(resolver.resolve("GET", path(CatalogNavigation.playlistContext("zzz"), c), null).plan().status())
                .isEqualTo(404);
    }

    @Test
    void randomContextResolvesWhileTheSnapshotExistsAndHoldsALeaseDuringTheStream() {
        RandomSnapshot s = shuffle.open(new ClientId("1.2.3.4", "VLC"), root);
        Track first = s.tracks(snap).get(0);

        StreamResolver.Resolved r = resolver.resolve("GET", path(s.contextId(), first), null);

        assertThat(r.plan().status()).isEqualTo(200);
        assertThat(s.activeStreams()).isEqualTo(1);
        clock.advance(Duration.ofHours(2));
        assertThat(shuffle.open(new ClientId("1.2.3.4", "VLC"), root)).isSameAs(s);   // not expired while streaming
        r.lease().close();
        assertThat(s.activeStreams()).isZero();
        assertThat(resolver.nextInContext(s.contextId(), first.id())).contains(s.tracks(snap).get(1));
    }

    @Test
    void headRequestsDoNotHoldALease() {
        RandomSnapshot s = shuffle.open(new ClientId("1.2.3.4", "VLC"), root);

        StreamResolver.Resolved r = resolver.resolve("HEAD", path(s.contextId(), s.tracks(snap).get(0)), null);

        assertThat(r.plan().status()).isEqualTo(200);
        assertThat(s.activeStreams()).isZero();
    }

    @Test
    void unknownOrPurgedRandomContextIs404() {
        assertThat(resolver.resolve("GET", path("r." + root.id() + ".deadbeef", a), null).plan().status())
                .isEqualTo(404);
    }

    @Test
    void viewContextResolvesAndNavigates() {
        ViewNode titles = views.root(snap, root, ViewKind.TITLE).orElseThrow();

        assertThat(resolver.resolve("GET", path(titles.id(), a), null).plan().status()).isEqualTo(200);
        assertThat(resolver.nextInContext(titles.id(), a.id())).contains(b);
        assertThat(resolver.nextInContext(titles.id(), c.id())).isEmpty();
        assertThat(resolver.resolve("GET", path("v." + root.id() + ".ti.nope", a), null).plan().status())
                .isEqualTo(404);
    }

    @Test
    void folderNavigationIsAvailableThroughTheResolver() {
        assertThat(resolver.nextInContext(Folder.idOf(a.parentRef()), a.id())).contains(b);
    }

    @Test
    void compositeAnswersEmptyForUnknownContexts() {
        CompositeNavigation nav = new CompositeNavigation(shuffle, views);
        assertThat(nav.knows("x")).isFalse();
        assertThat(nav.next("x", "y")).isEmpty();
        nav.streamStarted("x").close();
    }
}
