package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.util.List;

import org.jupnp.support.contentdirectory.ContentDirectoryErrorCode;
import org.jupnp.support.contentdirectory.ContentDirectoryException;
import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.item.Item;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;

class BrowseEngineTest {

    private static final String BASE = "http://10.0.0.5:8200";

    private final Folder rockRoot = folder("rock");
    private final Folder jazz = folder("jazz", new DirectorySettings(null, null,
            new AlbumInfo("Jazz Collection", null, null, null, null, null), 0, null));
    private final Folder sub = folder("rock/Live");
    private final Track[] rootTracks = new Track[12];
    private final BrowseEngine engine;
    private final Track live;

    BrowseEngineTest() {
        List<Track> tracks = new java.util.ArrayList<>();
        for (int i = 0; i < rootTracks.length; i++) {
            rootTracks[i] = track(String.format("rock/%02d song.mp3", i + 1),
                    new TrackTags("Song " + (i + 1), "Band", "Album", null, null, null, i + 1, null, null), 0);
            tracks.add(rootTracks[i]);
        }
        live = track("rock/Live/gig.flac");
        tracks.add(live);
        SnapshotHolder holder = new SnapshotHolder(snapshot(List.of(rockRoot, jazz, sub), tracks));
        engine = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> BASE,
                new org.kendar.dlna.artwork.ArtworkService(holder, ref -> null, ref -> java.util.Optional.empty(),
                        java.nio.file.Path.of("unused")));
    }

    private static DIDLContent parse(BrowseResult r) throws Exception {
        return new DIDLParser().parse(r.getResult());
    }

    @Test
    void rootMetadataAndChildren() throws Exception {
        BrowseResult meta = engine.browse("0", BrowseFlag.METADATA, 0, 0);
        assertThat(meta.getCountLong()).isEqualTo(1);
        assertThat(parse(meta).getContainers().get(0).getId()).isEqualTo("0");
        assertThat(parse(meta).getContainers().get(0).getParentID()).isEqualTo("-1");

        BrowseResult kids = engine.browse("0", BrowseFlag.DIRECT_CHILDREN, 0, 0);
        assertThat(kids.getTotalMatchesLong()).isEqualTo(2);
        assertThat(parse(kids).getContainers()).extracting(c -> c.getId()).containsExactly("folders", "playlists");
        assertThat(kids.getContainerUpdateIDLong()).isEqualTo(7);
    }

    @Test
    void foldersListsLibraryRootsWithDisplayNamesInConfiguredOrder() throws Exception {
        BrowseResult r = engine.browse("folders", BrowseFlag.DIRECT_CHILDREN, 0, 0);

        DIDLContent didl = parse(r);
        assertThat(didl.getContainers()).extracting(c -> c.getTitle()).containsExactly("rock", "Jazz Collection");
        assertThat(didl.getContainers()).extracting(c -> c.getParentID()).containsOnly("folders");
        assertThat(didl.getContainers()).extracting(c -> c.getClazz().getValue())
                .containsOnly("object.container.storageFolder");
        assertThat(didl.getContainers().get(0).getChildCount()).isEqualTo(13);   // 12 tracks + Live
        assertThat(didl.getContainers().get(0).getId()).isEqualTo(ObjectIds.folder(rockRoot));
    }

    @Test
    void displayNameRenamesRootsAndSubfoldersInBrowse() throws Exception {
        Folder named = folder("rock", new DirectorySettings(null, null,
                new AlbumInfo("Ignored", null, null, null, 2, null), 0, null, 0, "Rock Music"));
        Folder subNamed = folder("rock/Live", new DirectorySettings(null, null, null, 0, null, 0, "On Stage"));
        SnapshotHolder holder = new SnapshotHolder(snapshot(List.of(named, subNamed), List.of(live)));
        BrowseEngine e = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> BASE,
                new org.kendar.dlna.artwork.ArtworkService(holder, ref -> null, ref -> java.util.Optional.empty(),
                        java.nio.file.Path.of("unused")));

        assertThat(parse(e.browse("folders", BrowseFlag.DIRECT_CHILDREN, 0, 0)).getContainers())
                .extracting(c -> c.getTitle()).containsExactly("Rock Music");
        assertThat(parse(e.browse(ObjectIds.folder(named), BrowseFlag.DIRECT_CHILDREN, 0, 0)).getContainers())
                .extracting(c -> c.getTitle()).containsExactly("On Stage");
        assertThat(parse(e.browse(ObjectIds.folder(subNamed), BrowseFlag.METADATA, 0, 0)).getContainers().get(0)
                .getTitle()).isEqualTo("On Stage");
        // object ids stay path-based
        assertThat(ObjectIds.folder(subNamed)).isEqualTo(ObjectIds.folder(sub));
    }

    @Test
    void folderChildrenHaveSubdirsFirstAndTracksWithResources() throws Exception {
        BrowseResult r = engine.browse(ObjectIds.folder(rockRoot), BrowseFlag.DIRECT_CHILDREN, 0, 0);

        DIDLContent didl = parse(r);
        assertThat(r.getTotalMatchesLong()).isEqualTo(13);
        assertThat(r.getCountLong()).isEqualTo(13);
        assertThat(didl.getContainers()).extracting(c -> c.getId()).containsExactly(ObjectIds.folder(sub));
        assertThat(didl.getContainers().get(0).getParentID()).isEqualTo(ObjectIds.folder(rockRoot));
        Item first = didl.getItems().get(0);
        assertThat(first.getId()).isEqualTo(ObjectIds.track(rootTracks[0]));
        assertThat(first.getParentID()).isEqualTo(ObjectIds.folder(rockRoot));
        assertThat(first.getTitle()).isEqualTo("Song 1");
        assertThat(first.getClazz().getValue()).isEqualTo("object.item.audioItem.musicTrack");
        assertThat(first.getFirstResource().getValue())
                .isEqualTo(BASE + "/stream/" + rockRoot.id() + "/" + rootTracks[0].id());
        assertThat(first.getFirstResource().getSize()).isEqualTo(1000L);
        assertThat(first.getFirstResource().getDuration()).isEqualTo("0:01:01.500");
        assertThat(first.getFirstResource().getProtocolInfo().toString())
                .startsWith("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01");
    }

    @Test
    void pagingIsExactWithTotalMatches() throws Exception {
        String id = ObjectIds.folder(rockRoot);

        BrowseResult page1 = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, 0, 5);
        assertThat(page1.getCountLong()).isEqualTo(5);
        assertThat(page1.getTotalMatchesLong()).isEqualTo(13);
        assertThat(parse(page1).getContainers()).hasSize(1);   // the subdir is entry 0
        assertThat(parse(page1).getItems()).hasSize(4);

        BrowseResult page3 = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, 10, 5);
        assertThat(page3.getCountLong()).isEqualTo(3);
        assertThat(page3.getTotalMatchesLong()).isEqualTo(13);
        assertThat(parse(page3).getItems()).extracting(i -> i.getTitle())
                .containsExactly("Song 10", "Song 11", "Song 12");

        BrowseResult beyond = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, 100, 5);
        assertThat(beyond.getCountLong()).isZero();
        assertThat(beyond.getTotalMatchesLong()).isEqualTo(13);

        BrowseResult rest = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, 12, 0);
        assertThat(rest.getCountLong()).isEqualTo(1);
    }

    @Test
    void metadataOfFolderAndTrack() throws Exception {
        BrowseResult sf = engine.browse(ObjectIds.folder(sub), BrowseFlag.METADATA, 0, 0);
        assertThat(parse(sf).getContainers().get(0).getParentID()).isEqualTo(ObjectIds.folder(rockRoot));
        assertThat(parse(sf).getContainers().get(0).getChildCount()).isEqualTo(1);

        BrowseResult rf = engine.browse(ObjectIds.folder(rockRoot), BrowseFlag.METADATA, 0, 0);
        assertThat(parse(rf).getContainers().get(0).getParentID()).isEqualTo("folders");

        BrowseResult tr = engine.browse(ObjectIds.track(live), BrowseFlag.METADATA, 0, 0);
        Item item = parse(tr).getItems().get(0);
        assertThat(item.getParentID()).isEqualTo(ObjectIds.folder(sub));
        assertThat(item.getTitle()).isEqualTo("gig");
        assertThat(item.getFirstResource().getProtocolInfo().toString()).contains("audio/flac");
        assertThat(item.getFirstResource().getValue()).endsWith("/" + live.id());
    }

    @Test
    void unknownOrWrongKindOfObjectIsRejected() {
        assertThatThrownBy(() -> engine.browse("nope", BrowseFlag.METADATA, 0, 0))
                .isInstanceOfSatisfying(ContentDirectoryException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ContentDirectoryErrorCode.NO_SUCH_OBJECT.getCode()));
        assertThatThrownBy(() -> engine.browse("f:doesnotexist", BrowseFlag.DIRECT_CHILDREN, 0, 0))
                .isInstanceOf(ContentDirectoryException.class);
        assertThatThrownBy(() -> engine.browse(ObjectIds.track(live), BrowseFlag.DIRECT_CHILDREN, 0, 0))
                .isInstanceOf(ContentDirectoryException.class);
    }

    @Test
    void objectIdsRoundTripAndAreStable() {
        assertThat(ObjectIds.folderId(ObjectIds.folder(rockRoot))).contains(rockRoot.id());
        assertThat(ObjectIds.trackId(ObjectIds.track(live))).contains(live.id());
        assertThat(ObjectIds.folderId("0")).isEmpty();
        assertThat(ObjectIds.trackId("t:")).isEmpty();
        assertThat(ObjectIds.track(live)).isEqualTo(ObjectIds.track(track("rock/Live/gig.flac")));
    }

    @Test
    void durationFormat() {
        assertThat(BrowseEngine.formatDuration(3_725_042)).isEqualTo("1:02:05.042");
        assertThat(BrowseEngine.formatDuration(999)).isEqualTo("0:00:00.999");
    }
}
