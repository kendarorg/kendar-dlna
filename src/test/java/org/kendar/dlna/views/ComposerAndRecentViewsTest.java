package org.kendar.dlna.views;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.testutil.Fixtures;

class ComposerAndRecentViewsTest {

    private Folder root = folder("m", new DirectorySettings(null, null, null, 0, List.of(ViewKind.COMPOSER), 3));
    private final List<Track> tracks = new ArrayList<>();
    private final AtomicReference<Snapshot> catalog = new AtomicReference<>();

    private static TrackTags tags(String title, String album, String composer, List<String> composers) {
        return new TrackTags(title, "Perf", album, "Perf", null, null, 1, null, null, null, null, null, composer,
                composers);
    }

    private Track add(String path, TrackTags t, long addedAt) {
        Track tr = track(path, t, 0).withAddedAt(addedAt);
        tracks.add(tr);
        return tr;
    }

    private Views views() {
        catalog.set(Fixtures.snapshot(List.of(root, folder("m/A"), folder("m/B")), tracks));
        return new Views(catalog::get, 500);
    }

    private ViewNode composerRoot(Views v) {
        return v.root(catalog.get(), catalog.get().folder(root.ref()).orElseThrow(), ViewKind.COMPOSER).orElseThrow();
    }

    // ---- composer view ----------------------------------------------------------------------------------------

    @Test
    void composersListComposerThenAlbumsThenAllTracks() {
        add("m/A/1.mp3", tags("Aria", "Goldberg", "J. S. Bach", null), 1);
        add("m/A/2.mp3", tags("Var 1", "Goldberg", "J. S. Bach", null), 2);
        add("m/B/1.mp3", tags("Sonata", "Piano", "Beethoven", null), 3);

        ViewNode r = composerRoot(views());

        assertThat(r.title()).isEqualTo("[Composers]");
        assertThat(r.id()).isEqualTo("v." + root.id() + ".co");
        assertThat(r.children()).extracting(ViewNode::title).containsExactly("Beethoven", "J. S. Bach");
        ViewNode bach = r.children().get(1);
        assertThat(bach.children()).extracting(ViewNode::title).containsExactly("Goldberg", "All tracks");
        assertThat(bach.children().get(1).type()).isEqualTo(ViewNode.Type.ALL_TRACKS);
        assertThat(bach.children().get(1).tracks()).hasSize(2);
        assertThat(bach.parentId()).isEqualTo(r.id());
    }

    @Test
    void tracksWithoutComposerGoToUnknownComposerAndKeysAreNormalized() {
        add("m/A/1.mp3", tags("x", "A1", null, null), 1);
        add("m/A/2.mp3", tags("y", "A1", "  dvořák ", null), 2);
        add("m/A/3.mp3", tags("z", "A2", "DVORAK", null), 3);

        ViewNode r = composerRoot(views());

        assertThat(r.children()).hasSize(2);
        assertThat(r.children()).extracting(ViewNode::title).contains("Unknown Composer");
        ViewNode dv = r.children().stream().filter(n -> !n.title().startsWith("Unknown")).findFirst().orElseThrow();
        assertThat(dv.children().stream().filter(n -> n.type() == ViewNode.Type.ALL_TRACKS).findFirst().orElseThrow()
                .tracks()).hasSize(2);
    }

    @Test
    void multiValueComposersListTheTrackUnderEachOne() {
        add("m/A/1.mp3", tags("Duet", "Songs", "Lennon", List.of("Lennon", "McCartney")), 1);

        ViewNode r = composerRoot(views());

        assertThat(r.children()).extracting(ViewNode::title).containsExactly("Lennon", "McCartney");
    }

    @Test
    void composerNodesAreFoundByIdAndNavigable() {
        add("m/A/1.mp3", tags("One", "Alb", "Zed", null), 1);
        add("m/A/2.mp3", tags("Two", "Alb", "Zed", null), 2);
        Views v = views();
        ViewNode album = composerRoot(v).children().get(0).children().get(0);

        assertThat(v.node(catalog.get(), album.id())).contains(album);
        assertThat(v.knows(album.id())).isTrue();
        assertThat(v.next(album.id(), album.tracks().get(0).id())).contains(album.tracks().get(1));
    }

    // ---- recently added -----------------------------------------------------------------------------------------

    @Test
    void recentListsTheNewestNTracksOfTheSubtreeNewestFirst() {
        add("m/A/old.mp3", tags("old", null, null, null), 100);
        add("m/B/new.mp3", tags("new", null, null, null), 500);
        add("m/mid.mp3", tags("mid", null, null, null), 300);
        add("m/A/newer.mp3", tags("newer", null, null, null), 900);
        Views v = views();

        ViewNode recent = v.recentRoot(catalog.get(), catalog.get().folder(root.ref()).orElseThrow()).orElseThrow();

        assertThat(recent.title()).isEqualTo("[Recently added]");
        assertThat(recent.id()).isEqualTo("n." + root.id());
        assertThat(recent.type()).isEqualTo(ViewNode.Type.RECENT);
        assertThat(recent.tracks()).extracting(t -> t.tags().title()).containsExactly("newer", "new", "mid");
        assertThat(recent.parentId()).isNull();
    }

    @Test
    void recentOnlyCoversItsOwnSubtree() {
        Folder sub = folder("m/A", new DirectorySettings(null, null, null, 0, List.of(), 5));
        root = folder("m");
        add("m/A/a.mp3", tags("a", null, null, null), 10);
        add("m/B/b.mp3", tags("b", null, null, null), 20);
        catalog.set(Fixtures.snapshot(List.of(root, sub, folder("m/B")), tracks));
        Views v = new Views(catalog::get, 500);

        assertThat(v.recentRoot(catalog.get(), root)).isEmpty();
        assertThat(v.recentRoot(catalog.get(), sub).orElseThrow().tracks()).extracting(t -> t.tags().title())
                .containsExactly("a");
    }

    @Test
    void fewerTracksThanNListsAllAndTiesAreStable() {
        add("m/b.mp3", tags("b", null, null, null), 50);
        add("m/a.mp3", tags("a", null, null, null), 50);
        Views v = views();

        ViewNode recent = v.recentRoot(catalog.get(), catalog.get().folder(root.ref()).orElseThrow()).orElseThrow();

        assertThat(recent.tracks()).extracting(t -> t.tags().title()).containsExactly("a", "b");
    }

    @Test
    void recentContextIsKnownNavigableAndGoneWhenDisabled() {
        Track t1 = add("m/a.mp3", tags("a", null, null, null), 10);
        Track t2 = add("m/b.mp3", tags("b", null, null, null), 20);
        Views v = views();
        String ctx = "n." + root.id();

        assertThat(v.knows(ctx)).isTrue();
        assertThat(v.next(ctx, t2.id())).contains(t1);   // newest first: b then a
        assertThat(v.next(ctx, t1.id())).isEmpty();

        root = folder("m");
        views();
        Views off = new Views(catalog::get, 500);
        assertThat(off.knows(ctx)).isFalse();
        assertThat(off.knows("n.unknown")).isFalse();
    }

    @Test
    void recentIsRecomputedWhenTheSnapshotIsSwapped() {
        add("m/a.mp3", tags("a", null, null, null), 10);
        Views v = views();
        Folder f = catalog.get().folder(root.ref()).orElseThrow();
        assertThat(v.recentRoot(catalog.get(), f).orElseThrow().tracks()).hasSize(1);

        add("m/b.mp3", tags("b", null, null, null), 20);
        catalog.set(Fixtures.snapshot(List.of(root, folder("m/A"), folder("m/B")), tracks));   // same Views instance
        f = catalog.get().folder(root.ref()).orElseThrow();
        assertThat(v.recentRoot(catalog.get(), f).orElseThrow().tracks()).extracting(t -> t.tags().title())
                .containsExactly("b", "a");
    }
}
