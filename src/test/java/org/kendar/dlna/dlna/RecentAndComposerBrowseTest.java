package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.jupnp.support.contentdirectory.ContentDirectoryException;
import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.item.Item;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.ContainerKeys;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.MutableClock;
import org.kendar.dlna.views.Views;

/** The "Recently added" container, the Composers view and the composer in DIDL. */
class RecentAndComposerBrowseTest {

    private static final String BASE = "http://10.0.0.5:8200";
    private static final ClientId VLC = new ClientId("10.0.0.2", "VLC");

    private final Folder lib = folder("lib", new DirectorySettings(null, null, null, 2,
            List.of(ViewKind.COMPOSER, ViewKind.TITLE), 3));
    private final Folder sub = folder("lib/Sub");
    private final List<Track> tracks = new ArrayList<>();
    private final BrowseEngine engine;
    private final Snapshot snap;

    RecentAndComposerBrowseTest() {
        tracks.add(add("lib/a.mp3", "Alpha", "Mozart", 100));
        tracks.add(add("lib/Sub/b.mp3", "Bravo", "Mozart", 400));
        tracks.add(add("lib/Sub/c.mp3", "Charlie", null, 300));
        tracks.add(add("lib/Sub/d.mp3", "Delta", "Haydn", 200));
        tracks.add(add("lib/Sub/e.mp3", "Echo", "Haydn", 50));
        snap = new Snapshot(11, Instant.EPOCH, List.of(lib, sub), tracks, List.<UnsupportedFile>of());
        SnapshotHolder holder = new SnapshotHolder(snap);
        engine = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> BASE,
                new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused")),
                new Shuffle(holder::current, new MutableClock(), Duration.ofMinutes(30), new Random(5)),
                new Views(holder::current, 500));
    }

    private static Track add(String path, String title, String composer, long addedAt) {
        return track(path, new TrackTags(title, "Perf", "Alb", "Perf", null, null, 1, null, null, null, null, null,
                composer, null), 0).withAddedAt(addedAt);
    }

    private DIDLContent children(String id) throws Exception {
        BrowseResult r = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, VLC);
        return new DIDLParser().parse(r.getResult());
    }

    @Test
    void recentComesAfterRandomAndBeforeTheViews() throws Exception {
        DIDLContent d = children("f:" + lib.id());

        assertThat(d.getContainers()).extracting(Container::getTitle).containsExactly("[Random 2]",
                "[Recently added]", "[Composers]", "[Titles]", "Sub");
        Container recent = d.getContainers().get(1);
        assertThat(recent.getId()).isEqualTo("c:n." + lib.id());
        assertThat(recent.getParentID()).isEqualTo("f:" + lib.id());
        assertThat(recent.getChildCount()).isEqualTo(3);
    }

    @Test
    void directoryChildCountIncludesTheRecentContainer() throws Exception {
        BrowseResult r = engine.browse("f:" + lib.id(), BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, VLC);
        assertThat(r.getTotalMatchesLong()).isEqualTo(6);   // random, recent, 2 views, Sub, a.mp3
        BrowseResult meta = engine.browse("f:" + lib.id(), BrowseFlag.METADATA, "*", 0, 0, VLC);
        assertThat(new DIDLParser().parse(meta.getResult()).getContainers().get(0).getChildCount()).isEqualTo(6);
    }

    @Test
    void recentListsTheNewestTracksOfTheSubtreeWithStableIdsAndStreamUrls() throws Exception {
        String id = "c:n." + lib.id();

        DIDLContent d = children(id);

        assertThat(d.getItems()).extracting(Item::getTitle).containsExactly("Bravo", "Charlie", "Delta");
        String ctx = "n." + lib.id();
        for (Item item : d.getItems()) {
            assertThat(item.getId()).startsWith("i:" + ctx + ":");
            assertThat(item.getParentID()).isEqualTo(id);
            assertThat(item.getFirstResource().getValue()).startsWith(BASE + "/stream/" + ctx + "/");
        }
        assertThat(children(id).getItems()).extracting(Item::getId)
                .isEqualTo(d.getItems().stream().map(Item::getId).toList());
        BrowseResult meta = engine.browse(id, BrowseFlag.METADATA, "*", 0, 0, VLC);
        Container c = new DIDLParser().parse(meta.getResult()).getContainers().get(0);
        assertThat(c.getTitle()).isEqualTo("[Recently added]");
    }

    @Test
    void recentItemMetadataCanBeBrowsedAndUsesTheContainerAsParent() throws Exception {
        Track newest = tracks.get(1);
        BrowseResult r = engine.browse("i:n." + lib.id() + ":" + newest.id(), BrowseFlag.METADATA, "*", 0, 0, VLC);
        Item item = new DIDLParser().parse(r.getResult()).getItems().get(0);
        assertThat(item.getTitle()).isEqualTo("Bravo");
        assertThat(item.getParentID()).isEqualTo("c:n." + lib.id());
    }

    @Test
    void directoryWithoutRecentHasNoRecentContainerAndItsContextIsUnknown() throws Exception {
        assertThat(children("f:" + sub.id()).getContainers()).isEmpty();
        assertThatThrownBy(() -> children("c:n." + sub.id())).isInstanceOf(ContentDirectoryException.class);
    }

    @Test
    void recentContainerFollowsTheVirtualUpdateIdOfItsDirectory() throws Exception {
        Snapshot withIds = snap.withUpdates(11, Instant.EPOCH, Map.of(ContainerKeys.virtual(lib.id()), 9L));
        SnapshotHolder holder = new SnapshotHolder(withIds);
        BrowseEngine e = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> BASE,
                new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused")));
        assertThat(e.browse("c:n." + lib.id(), BrowseFlag.METADATA, "*", 0, 0, VLC).getContainerUpdateIDLong()).isEqualTo(9);
        assertThat(UpdateEvents.objectIds(withIds, ContainerKeys.virtual(lib.id())))
                .contains("c:n." + lib.id(), "c:v." + lib.id() + ".co");
    }

    @Test
    void composersViewListsComposersAlbumsAndTracks() throws Exception {
        DIDLContent composers = children("c:v." + lib.id() + ".co");

        assertThat(composers.getContainers()).extracting(Container::getTitle)
                .containsExactly("Haydn", "Mozart", "Unknown Composer");
        Container mozart = composers.getContainers().get(1);
        assertThat(mozart.getClazz().getValue()).isEqualTo("object.container.person.musicArtist");
        DIDLContent inside = children(mozart.getId());
        assertThat(inside.getContainers()).extracting(Container::getTitle).containsExactly("Alb", "All tracks");
        assertThat(children(inside.getContainers().get(1).getId()).getItems()).hasSize(2);
    }

    @Test
    void trackItemsCarryTheComposerAsUpnpAuthor() throws Exception {
        String xml = engine.browse("t:" + tracks.get(0).id(), BrowseFlag.METADATA, "*", 0, 0, VLC).getResult();
        assertThat(xml).contains("upnp:author").contains("Mozart").contains("Composer");
        String without = engine.browse("t:" + tracks.get(2).id(), BrowseFlag.METADATA, "*", 0, 0, VLC).getResult();
        assertThat(without).doesNotContain("upnp:author");
        String filtered = engine.browse("t:" + tracks.get(0).id(), BrowseFlag.METADATA, "dc:title", 0, 0, VLC)
                .getResult();
        assertThat(filtered).doesNotContain("upnp:author");
    }

    @Test
    void searchFindsByComposerButNeverInsideVirtualContainers() throws Exception {
        BrowseResult r = engine.search("0", "upnp:author contains \"haydn\"", "*", 0, 0);
        DIDLContent d = new DIDLParser().parse(r.getResult());
        assertThat(d.getItems()).extracting(Item::getTitle).containsExactlyInAnyOrder("Delta", "Echo");
        assertThat(d.getItems()).allSatisfy(i -> assertThat(i.getId()).startsWith("t:"));
    }
}
