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
import java.util.Optional;
import java.util.Random;

import org.jupnp.support.contentdirectory.ContentDirectoryErrorCode;
import org.jupnp.support.contentdirectory.ContentDirectoryException;
import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.item.Item;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.CatalogNavigation;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Playlist;
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

class VirtualContainersBrowseTest {

    private static final String BASE = "http://10.0.0.5:8200";
    private static final ClientId VLC = new ClientId("10.0.0.2", "VLC");
    private static final ClientId KODI = new ClientId("10.0.0.2", "Kodi");

    private final MutableClock clock = new MutableClock();
    private final Folder lib = folder("lib", new DirectorySettings(null, null, null, 3,
            List.of(ViewKind.ALBUM, ViewKind.ARTIST, ViewKind.GENRE, ViewKind.TITLE)));
    private final Folder rock = folder("lib/Rock");
    private final List<Track> tracks = new ArrayList<>();
    private final BrowseEngine engine;
    private final Snapshot snap;

    VirtualContainersBrowseTest() {
        for (int i = 1; i <= 6; i++) {
            tracks.add(track(String.format("lib/Rock/%02d.mp3", i),
                    new TrackTags("Rock song " + i, "Ann", "Blue", "Ann", "Rock", 1999, i, null, null), 0));
        }
        tracks.add(track("lib/Rock/zz.mp3",
                new TrackTags("Zed", "Bob", "Red", "Bob", "Pop", 2005, 1, null, null), 0));
        tracks.add(track("lib/root.mp3", new TrackTags("Root song", null, null, null, null, null, null, null, null),
                0));
        snap = new Snapshot(11, Instant.EPOCH, List.of(lib, rock), tracks,
                List.<UnsupportedFile>of(), List.of(new Playlist("trip", "Road trip",
                        List.of("lib/Rock/03.mp3", "lib/missing.mp3", "lib/Rock/01.mp3"))));
        SnapshotHolder holder = new SnapshotHolder(snap);
        engine = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> BASE,
                new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused")),
                new Shuffle(holder::current, clock, Duration.ofMinutes(30), new Random(5)),
                new Views(holder::current, 500));
    }

    private static DIDLContent parse(BrowseResult r) throws Exception {
        return new DIDLParser().parse(r.getResult());
    }

    private DIDLContent children(String id, ClientId client) throws Exception {
        return parse(engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, client));
    }

    private Container container(DIDLContent d, String title) {
        return d.getContainers().stream().filter(c -> c.getTitle().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void directoryListsRandomThenViewsInSettingsOrderThenRegularChildren() throws Exception {
        DIDLContent d = children("f:" + lib.id(), VLC);

        assertThat(d.getContainers()).extracting(Container::getTitle)
                .containsExactly("[Random 3]", "[Albums]", "[Artists]", "[Genres]", "[Titles]", "Rock");
        assertThat(d.getItems()).extracting(Item::getTitle).containsExactly("Root song");
        assertThat(container(d, "[Random 3]").getParentID()).isEqualTo("f:" + lib.id());
        assertThat(container(d, "[Albums]").getId()).isEqualTo("c:v." + lib.id() + ".al");
        assertThat(container(d, "[Albums]").getParentID()).isEqualTo("f:" + lib.id());
    }

    @Test
    void childCountOfADirectoryIncludesRandomAndViews() throws Exception {
        BrowseResult meta = engine.browse("f:" + lib.id(), BrowseFlag.METADATA, "*", 0, 0, VLC);
        assertThat(parse(meta).getContainers().get(0).getChildCount()).isEqualTo(7);   // 1 + 4 + Rock + root.mp3
        assertThat(engine.browse("f:" + lib.id(), BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, VLC)
                .getTotalMatchesLong()).isEqualTo(7);
    }

    @Test
    void directoryWithoutSettingsHasNoExtraContainers() throws Exception {
        DIDLContent d = children("f:" + rock.id(), VLC);
        assertThat(d.getContainers()).isEmpty();
        assertThat(d.getItems()).hasSize(7);
    }

    @Test
    void randomContainerHoldsNItemsWithSnapshotInTheIdsAndStreamUrls() throws Exception {
        Container random = container(children("f:" + lib.id(), VLC), "[Random 3]");
        assertThat(random.getId()).startsWith("c:r." + lib.id() + ".");
        String ctx = random.getId().substring(2);

        DIDLContent d = children(random.getId(), VLC);

        assertThat(d.getItems()).hasSize(3);
        for (Item item : d.getItems()) {
            assertThat(item.getId()).startsWith("i:" + ctx + ":");
            assertThat(item.getParentID()).isEqualTo(random.getId());
            assertThat(item.getFirstResource().getValue()).startsWith(BASE + "/stream/" + ctx + "/");
        }
        BrowseResult meta = engine.browse(random.getId(), BrowseFlag.METADATA, "*", 0, 0, VLC);
        assertThat(parse(meta).getContainers().get(0).getChildCount()).isEqualTo(3);
        assertThat(parse(meta).getContainers().get(0).getTitle()).isEqualTo("[Random 3]");
    }

    @Test
    void randomSnapshotIsStablePerClientAndDifferentBetweenClients() throws Exception {
        String first = container(children("f:" + lib.id(), VLC), "[Random 3]").getId();
        String again = container(children("f:" + lib.id(), VLC), "[Random 3]").getId();
        String other = container(children("f:" + lib.id(), KODI), "[Random 3]").getId();

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
        assertThat(children(first, VLC).getItems()).extracting(Item::getId)
                .isEqualTo(children(first, VLC).getItems().stream().map(Item::getId).toList());
    }

    @Test
    void randomContainerIsReplacedAfterTheTtlButTheOldOneIsGone() throws Exception {
        String first = container(children("f:" + lib.id(), VLC), "[Random 3]").getId();
        clock.advance(Duration.ofMinutes(31));

        String second = container(children("f:" + lib.id(), VLC), "[Random 3]").getId();

        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(() -> engine.browse(first, BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, VLC))
                .isInstanceOfSatisfying(ContentDirectoryException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ContentDirectoryErrorCode.NO_SUCH_OBJECT.getCode()));
    }

    @Test
    void playlistsContainerListsPlaylistContainersWithPlayableTracksOnly() throws Exception {
        DIDLContent top = children("playlists", VLC);
        Container pl = top.getContainers().get(0);

        assertThat(pl.getTitle()).isEqualTo("Road trip");
        assertThat(pl.getClazz().getValue()).isEqualTo("object.container.playlistContainer");
        assertThat(pl.getParentID()).isEqualTo("playlists");
        assertThat(pl.getChildCount()).isEqualTo(2);   // the missing song is skipped in DLNA

        DIDLContent items = children(pl.getId(), VLC);
        assertThat(items.getItems()).extracting(Item::getTitle).containsExactly("Rock song 3", "Rock song 1");
        String ctx = CatalogNavigation.playlistContext(Playlist.idOf("trip"));
        assertThat(items.getItems().get(0).getId()).isEqualTo("i:" + ctx + ":" + tracks.get(2).id());
        assertThat(items.getItems().get(0).getFirstResource().getValue())
                .isEqualTo(BASE + "/stream/" + ctx + "/" + tracks.get(2).id());
    }

    @Test
    void rootHasFoldersAndPlaylists() throws Exception {
        DIDLContent d = children("0", VLC);
        assertThat(d.getContainers()).extracting(Container::getId).containsExactly("folders", "playlists");
        BrowseResult meta = engine.browse("playlists", BrowseFlag.METADATA, "*", 0, 0, VLC);
        assertThat(parse(meta).getContainers().get(0).getChildCount()).isEqualTo(1);
        assertThat(parse(meta).getContainers().get(0).getParentID()).isEqualTo("0");
    }

    @Test
    void albumsViewUsesMusicAlbumContainersWithArtistAndTracksInOrder() throws Exception {
        DIDLContent albums = children("c:v." + lib.id() + ".al", VLC);

        assertThat(albums.getContainers()).extracting(Container::getTitle).containsExactly("Blue", "Red", "Unknown Album");
        Container blue = albums.getContainers().get(0);
        assertThat(blue.getClazz().getValue()).isEqualTo("object.container.album.musicAlbum");
        assertThat(blue.getCreator()).isEqualTo("Ann");
        assertThat(blue.getChildCount()).isEqualTo(6);
        assertThat(blue.getParentID()).isEqualTo("c:v." + lib.id() + ".al");

        DIDLContent tracksOfBlue = children(blue.getId(), VLC);
        assertThat(tracksOfBlue.getItems()).extracting(Item::getTitle).startsWith("Rock song 1", "Rock song 2");
        assertThat(tracksOfBlue.getItems().get(0).getFirstResource().getValue())
                .contains("/stream/" + blue.getId().substring(2) + "/");
    }

    @Test
    void artistsViewIsArtistThenAlbumsAndAllTracksThenTracks() throws Exception {
        DIDLContent artists = children("c:v." + lib.id() + ".ar", VLC);
        Container ann = container(artists, "Ann");
        assertThat(ann.getClazz().getValue()).isEqualTo("object.container.person.musicArtist");

        DIDLContent level2 = children(ann.getId(), VLC);
        assertThat(level2.getContainers()).extracting(Container::getTitle).containsExactly("Blue", "All tracks");
        assertThat(level2.getContainers().get(1).getClazz().getValue()).isEqualTo("object.container.storageFolder");
        assertThat(level2.getContainers().get(0).getParentID()).isEqualTo(ann.getId());

        assertThat(children(level2.getContainers().get(1).getId(), VLC).getItems()).hasSize(6);
    }

    @Test
    void genresViewUsesMusicGenreAndAlbumsBelow() throws Exception {
        DIDLContent genres = children("c:v." + lib.id() + ".ge", VLC);
        assertThat(genres.getContainers()).extracting(Container::getTitle).containsExactly("Pop", "Rock", "Unknown Genre");
        assertThat(genres.getContainers().get(0).getClazz().getValue()).isEqualTo("object.container.genre.musicGenre");

        DIDLContent albums = children(genres.getContainers().get(1).getId(), VLC);
        assertThat(albums.getContainers()).extracting(Container::getTitle).containsExactly("Blue");
    }

    @Test
    void titlesViewListsTracksSortedAndPagesWithExactTotals() throws Exception {
        String id = "c:v." + lib.id() + ".ti";
        BrowseResult page = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", 2, 3, VLC);

        assertThat(page.getTotalMatchesLong()).isEqualTo(8);
        assertThat(page.getCountLong()).isEqualTo(3);
        assertThat(parse(page).getItems()).extracting(Item::getTitle)
                .containsExactly("Rock song 3", "Rock song 4", "Rock song 5");
    }

    @Test
    void itemMetadataInAVirtualContainerResolvesWithItsContainerAsParent() throws Exception {
        String titles = "c:v." + lib.id() + ".ti";
        Item first = children(titles, VLC).getItems().get(0);

        BrowseResult meta = engine.browse(first.getId(), BrowseFlag.METADATA, "*", 0, 0, VLC);

        Item item = parse(meta).getItems().get(0);
        assertThat(item.getId()).isEqualTo(first.getId());
        assertThat(item.getParentID()).isEqualTo(titles);
        assertThatThrownBy(() -> engine.browse(first.getId(), BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, VLC))
                .isInstanceOf(ContentDirectoryException.class);
    }

    @Test
    void unknownVirtualIdsAreRejected() {
        for (String id : List.of("c:v." + lib.id() + ".al.nope", "c:r.x.y", "c:p.nope", "c:garbage", "i:c:v:x")) {
            assertThatThrownBy(() -> engine.browse(id, BrowseFlag.METADATA, "*", 0, 0, VLC))
                    .as(id).isInstanceOf(ContentDirectoryException.class);
        }
    }

    @Test
    void searchNeverReturnsRandomOrViewContainersOrTheirItems() throws Exception {
        BrowseResult r = engine.search("0", "dc:title contains \"Rock song\"", "*", 0, 0);

        assertThat(r.getTotalMatchesLong()).isEqualTo(6);
        DIDLContent d = parse(r);
        assertThat(d.getItems()).allMatch(i -> i.getId().startsWith("t:"));
        assertThat(d.getContainers()).isEmpty();

        BrowseResult mixed = engine.search("0", "dc:title contains \"Rock\"", "*", 0, 0);
        assertThat(mixed.getTotalMatchesLong()).isEqualTo(7);
        assertThat(parse(mixed).getContainers()).extracting(Container::getTitle).containsExactly("Rock");
        assertThat(parse(mixed).getContainers().get(0).getId()).startsWith("f:");
    }

    @Test
    void searchScopedToAVirtualContainerIsRejected() throws Exception {
        String random = container(children("f:" + lib.id(), VLC), "[Random 3]").getId();
        for (String scope : List.of(random, "c:v." + lib.id() + ".al", "playlists")) {
            assertThatThrownBy(() -> engine.search(scope, "dc:title contains \"a\"", "*", 0, 0))
                    .isInstanceOfSatisfying(ContentDirectoryException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BrowseEngine.SEARCH_NO_SUCH_CONTAINER));
        }
    }

    @Test
    void browseWithoutClientIdentityStillWorks() throws Exception {
        BrowseResult r = engine.browse("f:" + lib.id(), BrowseFlag.DIRECT_CHILDREN, 0, 0);
        assertThat(r.getTotalMatchesLong()).isEqualTo(7);
    }
}
