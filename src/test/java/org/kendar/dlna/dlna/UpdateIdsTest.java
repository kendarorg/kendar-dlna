package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jupnp.support.model.BrowseFlag;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.ChangeSet;
import org.kendar.dlna.catalog.ContainerKeys;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.views.Views;

class UpdateIdsTest {

    private final Folder lib = folder("lib", new DirectorySettings(null, null, null, 2,
            List.of(ViewKind.ALBUM, ViewKind.TITLE)));
    private final Folder sub = folder("lib/sub");
    private final Playlist playlist = new Playlist("pl", "PL", List.of("lib/a.mp3"));

    private Snapshot snapshot(long id, Map<String, Long> ids) {
        return new Snapshot(id, Instant.EPOCH, List.of(lib, sub), List.of(track("lib/a.mp3"), track("lib/sub/b.mp3")),
                List.of(), List.of(playlist), ids);
    }

    private BrowseEngine engine(Snapshot snap) {
        SnapshotHolder holder = new SnapshotHolder(snap);
        return new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> "http://x",
                new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused")),
                new org.kendar.dlna.shuffle.Shuffle(holder::current), new Views(holder::current, 500));
    }

    @Test
    void browseReportsTheContainersOwnUpdateId() throws Exception {
        Map<String, Long> ids = Map.of(sub.id(), 7L, ContainerKeys.FOLDERS, 4L, ContainerKeys.PLAYLISTS, 5L,
                ContainerKeys.playlist(playlist.id()), 6L, ContainerKeys.virtual(lib.id()), 9L);
        BrowseEngine e = engine(snapshot(12, ids));

        assertThat(e.browse("0", BrowseFlag.DIRECT_CHILDREN, 0, 0).getContainerUpdateIDLong()).isEqualTo(12);
        assertThat(e.browse(ObjectIds.FOLDERS, BrowseFlag.DIRECT_CHILDREN, 0, 0).getContainerUpdateIDLong())
                .isEqualTo(4);
        assertThat(e.browse(ObjectIds.PLAYLISTS, BrowseFlag.DIRECT_CHILDREN, 0, 0).getContainerUpdateIDLong())
                .isEqualTo(5);
        assertThat(e.browse(ObjectIds.folder(sub), BrowseFlag.DIRECT_CHILDREN, 0, 0).getContainerUpdateIDLong())
                .isEqualTo(7);
        assertThat(e.browse(ObjectIds.folder(sub), BrowseFlag.METADATA, 0, 0).getContainerUpdateIDLong())
                .isEqualTo(7);
        assertThat(e.browse(ObjectIds.folder(lib), BrowseFlag.DIRECT_CHILDREN, 0, 0).getContainerUpdateIDLong())
                .isZero();   // lib's own listing never changed
        assertThat(e.browse(ObjectIds.container(CatalogNavigationIds.playlist(playlist)), BrowseFlag.DIRECT_CHILDREN,
                0, 0).getContainerUpdateIDLong()).isEqualTo(6);
    }

    @Test
    void viewAndRandomContainersFollowTheirDirectorysVirtualId() throws Exception {
        BrowseEngine e = engine(snapshot(12, Map.of(ContainerKeys.virtual(lib.id()), 9L)));
        String albums = ObjectIds.container(Views.rootId(lib, ViewKind.ALBUM));
        assertThat(e.browse(albums, BrowseFlag.DIRECT_CHILDREN, 0, 0).getContainerUpdateIDLong()).isEqualTo(9);

        String randomId = e.browse(ObjectIds.folder(lib), BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, new ClientId("1.1.1.1", "x"))
                .getResult().replaceAll("(?s).*id=\"(c:r\\.[^\"]+)\".*", "$1");
        assertThat(e.browse(randomId, BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, new ClientId("1.1.1.1", "x"))
                .getContainerUpdateIDLong()).isEqualTo(9);
    }

    @Test
    void eventValueListsObjectIdsAndTheirUpdateIds() {
        Snapshot snap = snapshot(8, Map.of(sub.id(), 8L, ContainerKeys.PLAYLISTS, 8L,
                ContainerKeys.playlist(playlist.id()), 8L, ContainerKeys.virtual(lib.id()), 8L));
        String value = UpdateEvents.containerUpdateIds(snap,
                new ChangeSet(8, Set.of(sub.id(), ContainerKeys.PLAYLISTS, ContainerKeys.playlist(playlist.id()),
                        ContainerKeys.virtual(lib.id()))));
        List<String> parts = List.of(value.split(","));
        assertThat(parts).hasSize(10);   // 5 object ids: folder, playlists, playlist, 2 view roots
        assertThat(parts).contains(ObjectIds.folderOf(sub.id()), ObjectIds.PLAYLISTS,
                ObjectIds.container(Views.rootId(lib, ViewKind.ALBUM)),
                ObjectIds.container(Views.rootId(lib, ViewKind.TITLE)));
        for (int i = 1; i < parts.size(); i += 2) {
            assertThat(parts.get(i)).isEqualTo("8");
        }
    }

    @Test
    void announceFiresEventsForSystemAndContainerUpdateIds() {
        LibraryContentDirectory cd = new LibraryContentDirectory(engine(snapshot(3, Map.of())));
        List<String> fired = new ArrayList<>();
        cd.getPropertyChangeSupport().addPropertyChangeListener(ev -> fired.add(ev.getPropertyName()));

        cd.announce("f:x,3");

        assertThat(fired).containsExactlyInAnyOrder("ContainerUpdateIDs", "SystemUpdateID");
        assertThat(cd.getContainerUpdateIDs()).isEqualTo("f:x,3");
        assertThat(cd.getSystemUpdateID().getValue()).isEqualTo(3);
    }

    @Test
    void announcerBuildsTheValueFromTheCurrentSnapshot() {
        Snapshot snap = snapshot(8, Map.of(sub.id(), 8L));
        BrowseEngine e = engine(snap);
        LibraryContentDirectory cd = new LibraryContentDirectory(e);
        UpdateEvents.announcer(e::snapshot, () -> cd).changed(new ChangeSet(8, Set.of(sub.id())));
        assertThat(cd.getContainerUpdateIDs()).isEqualTo(ObjectIds.folderOf(sub.id()) + ",8");
        UpdateEvents.announcer(e::snapshot, () -> null).changed(new ChangeSet(9, Set.of()));   // not started: no-op
    }

    /** Playlist context id as used by Browse. */
    private static final class CatalogNavigationIds {
        static String playlist(Playlist p) {
            return org.kendar.dlna.catalog.CatalogNavigation.playlistContext(p.id());
        }
    }
}
