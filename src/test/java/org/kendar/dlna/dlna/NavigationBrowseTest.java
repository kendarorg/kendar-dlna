package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.item.Item;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Navigation;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.MutableClock;
import org.kendar.dlna.catalog.NavigationRegistry;
import org.kendar.dlna.views.Views;

class NavigationBrowseTest {

    private static final ClientId VLC = new ClientId("10.0.0.2", "VLC");

    private final Folder lib = folder("lib");
    private final Folder rock = folder("lib/Rock");
    private final List<Track> tracks = new ArrayList<>();
    private final BrowseEngine engine;

    NavigationBrowseTest() {
        tracks.add(track("lib/Rock/a.mp3", new TrackTags("Alpha", "Ann", "Blue", "Ann", "Rock", 1999, 1, null, null), 0));
        tracks.add(track("lib/Rock/b.mp3", new TrackTags("Beta", "Ann", "Blue", "Ann", "Rock", 1999, 2, null, null), 0));
        Snapshot snap = new Snapshot(3, Instant.EPOCH, List.of(lib, rock), tracks, List.<UnsupportedFile>of(), List.of());
        SnapshotHolder holder = new SnapshotHolder(snap);
        Views views = new Views(holder::current).withNavigations(new NavigationRegistry(List.of(
                new Navigation("Artists", null, null, List.of("artist", "album", "title"))), List.of()));
        engine = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> "http://x:8200",
                new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused")),
                new Shuffle(holder::current, new MutableClock(), Duration.ofMinutes(30), new Random(1)), views);
    }

    private DIDLContent children(String id) throws Exception {
        BrowseResult r = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, VLC);
        return new DIDLParser().parse(r.getResult());
    }

    @Test
    void theLibraryRootListsTheNavigationAndItsLevelsAreBrowsable() throws Exception {
        DIDLContent root = children("f:" + lib.id());
        Container artists = root.getContainers().stream().filter(c -> c.getTitle().equals("[Artists]")).findFirst()
                .orElseThrow();
        assertThat(artists.getId()).startsWith("c:v." + lib.id() + ".n");
        assertThat(artists.getParentID()).isEqualTo("f:" + lib.id());

        Container ann = children(artists.getId()).getContainers().get(0);
        assertThat(ann.getTitle()).isEqualTo("Ann");
        assertThat(ann.getParentID()).isEqualTo(artists.getId());
        DIDLContent albums = children(ann.getId());
        assertThat(albums.getContainers()).extracting(Container::getTitle).containsExactly("Blue", "All tracks");

        DIDLContent songs = children(albums.getContainers().get(0).getId());
        assertThat(songs.getItems()).extracting(Item::getTitle).containsExactly("Alpha", "Beta");
        assertThat(songs.getItems().get(0).getFirstResource().getValue()).contains("/stream/v." + lib.id());
    }


    @Test
    void aNavigationWithRandomListsARandomNContainerFirst() throws Exception {
        Snapshot snap = new Snapshot(3, Instant.EPOCH, List.of(lib, rock), tracks, List.<UnsupportedFile>of(), List.of());
        SnapshotHolder holder = new SnapshotHolder(snap);
        Views views = new Views(holder::current).withNavigations(new NavigationRegistry(List.of(
                new Navigation("Artists", null, null, List.of("artist", "album", "title"), 5)), List.of()));
        BrowseEngine withRandom = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> "http://x:8200",
                new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused")),
                new Shuffle(holder::current, new MutableClock(), Duration.ofMinutes(30), new Random(1)), views);
        Container artists = new DIDLParser().parse(withRandom.browse("f:" + lib.id(), BrowseFlag.DIRECT_CHILDREN, "*", 0, 0,
                VLC).getResult()).getContainers().stream().filter(c -> c.getTitle().equals("[Artists]")).findFirst()
                .orElseThrow();
        assertThat(artists.getChildCount()).isEqualTo(2);   // [Random 5] + Ann

        DIDLContent inside = new DIDLParser().parse(withRandom.browse(artists.getId(), BrowseFlag.DIRECT_CHILDREN, "*", 0, 0,
                VLC).getResult());
        assertThat(inside.getContainers()).extracting(Container::getTitle).containsExactly("[Random 5]", "Ann");
        Container random = inside.getContainers().get(0);
        assertThat(random.getParentID()).isEqualTo(artists.getId());

        DIDLContent picked = new DIDLParser().parse(withRandom.browse(random.getId(), BrowseFlag.DIRECT_CHILDREN, "*", 0, 0,
                VLC).getResult());
        assertThat(picked.getItems()).extracting(Item::getTitle).containsExactlyInAnyOrder("Alpha", "Beta");
        // the sub-levels have no Random N of their own
        assertThat(children(inside.getContainers().get(1).getId()).getContainers())
                .extracting(Container::getTitle).doesNotContain("[Random 5]");
    }
    @Test
    void aDirectoryWithoutAListOrAnInheritedOneHasNoNavigation() throws Exception {
        assertThat(children("f:" + rock.id()).getContainers()).isEmpty();
    }

    @Test
    void theRootChildCountIncludesTheNavigations() throws Exception {
        BrowseResult meta = engine.browse("f:" + lib.id(), BrowseFlag.METADATA, "*", 0, 0, VLC);
        assertThat(new DIDLParser().parse(meta.getResult()).getContainers().get(0).getChildCount()).isEqualTo(2);
    }
}
