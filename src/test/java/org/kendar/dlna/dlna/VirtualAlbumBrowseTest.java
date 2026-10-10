package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.container.MusicAlbum;
import org.jupnp.support.model.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.clients.ClientProfiles;
import org.kendar.dlna.clients.ClientRequest;
import org.kendar.dlna.clients.ClientsConfig;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.delivery.ResourceOffers;
import org.kendar.dlna.delivery.TranscodeCache;
import org.kendar.dlna.delivery.TranscodeService;
import org.kendar.dlna.search.Field;
import org.kendar.dlna.search.Query.Op;
import org.kendar.dlna.search.Query;
import org.kendar.dlna.search.SearchEngine;
import org.kendar.dlna.search.SearchHit;
import org.kendar.dlna.search.SearchScope;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.CueFixtures;
import org.kendar.dlna.testutil.LibraryFixture;
import org.kendar.dlna.transcoding.Transcoder;

/** DLNA browsing of a cue sheet album: a musicAlbum container in its directory whose items are the tracks. */
class VirtualAlbumBrowseTest {

    private static final String BASE = "http://10.0.0.5:8200";

    @TempDir
    Path tmp;
    LibraryFixture lib;
    BrowseEngine engine;
    Folder cuesDir;
    Folder album;
    Snapshot snap;

    @BeforeEach
    void setUp() {
        lib = new LibraryFixture(tmp);
        CueFixtures.write(lib.music.resolve("Cues"));
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        snap = lib.catalog.current();
        cuesDir = snap.folder(RootRef.parse("main/Cues")).orElseThrow();
        album = snap.folder(RootRef.parse("main/Cues/disc.wav")).orElseThrow();
        TranscodeService service = new TranscodeService(new Transcoder(null),
                new TranscodeCache(tmp.resolve("c"), 1 << 20, Duration.ofHours(1), Clock.systemUTC()), true, 320, "",
                null);
        engine = new BrowseEngine(lib.catalog.holder(), new DefaultStreamUrlBuilder(), () -> BASE,
                new ArtworkService(lib.catalog.holder(), ref -> null, ref -> java.util.Optional.empty(),
                        Path.of("unused")),
                new org.kendar.dlna.shuffle.Shuffle(lib.catalog.holder()::current, Clock.systemUTC(),
                        org.kendar.dlna.shuffle.Shuffle.DEFAULT_TTL),
                new org.kendar.dlna.views.Views(lib.catalog.holder()::current), new ResourceOffers(service),
                ClientProfiles.resolver(ClientsConfig.defaults(), tmp));
    }

    private DIDLContent children(String id) throws Exception {
        ClientId c = new ClientId("10.0.0.9", "Unknown/1.0");
        BrowseResult r = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, c,
                ClientRequest.of("10.0.0.9", "Unknown/1.0"));
        return new DIDLParser().parse(r.getResult());
    }

    @Test
    void theDirectoryListsTheFileAsAnAlbumContainerNotAsATrack() throws Exception {
        DIDLContent d = children(ObjectIds.folder(cuesDir));

        assertThat(d.getItems()).isEmpty();
        assertThat(d.getContainers()).hasSize(1);
        Container c = d.getContainers().get(0);
        assertThat(c).isInstanceOf(MusicAlbum.class);
        assertThat(c.getTitle()).isEqualTo("Live Album");
        assertThat(c.getId()).isEqualTo(ObjectIds.folder(album));
        assertThat(c.getParentID()).isEqualTo(ObjectIds.folder(cuesDir));
        assertThat(c.getChildCount()).isEqualTo(3);
        assertThat(c.getCreator()).isEqualTo("The Band");
    }

    @Test
    void theAlbumListsOneItemPerTrackWithExactTranscodedResources() throws Exception {
        DIDLContent d = children(ObjectIds.folder(album));

        assertThat(d.getContainers()).isEmpty();
        assertThat(d.getItems()).extracting(Item::getTitle).containsExactly("Opening", "Middle", "Finale");
        List<Track> tracks = snap.children(album.ref()).stream()
                .map(c -> ((org.kendar.dlna.catalog.Child.TrackChild) c).track()).toList();
        for (int i = 0; i < 3; i++) {
            Item item = d.getItems().get(i);
            Track t = tracks.get(i);
            assertThat(item.getParentID()).isEqualTo(ObjectIds.folder(album));
            assertThat(item.getResources()).as("only the decoded form is offered").hasSize(1);
            var res = item.getFirstResource();
            assertThat(res.getProtocolInfo().toString()).contains("audio/mpeg");
            assertThat(res.getValue()).endsWith("/" + t.id() + "/mp3");
            assertThat(res.getSize()).isEqualTo(Transcoder.mp3Length(t.sampleCount(), CueFixtures.RATE, 320));
        }
        assertThat(d.getItems().get(2).getFirstResource().getDuration()).startsWith("0:00:04");
        assertThat(d.getItems().get(1).getFirstResource().getDuration()).startsWith("0:00:02.50");
        assertThat(d.getItems().get(0).getFirstResource().getDuration()).startsWith("0:00:03.49");
    }

    @Test
    void metadataOfAnItemAndOfTheAlbum() throws Exception {
        ClientId c = new ClientId("10.0.0.9", "Unknown/1.0");
        BrowseResult r = engine.browse(ObjectIds.folder(album), BrowseFlag.METADATA, "*", 0, 0, c,
                ClientRequest.of("10.0.0.9", "Unknown/1.0"));
        assertThat(new DIDLParser().parse(r.getResult()).getContainers().get(0).getTitle()).isEqualTo("Live Album");
    }

    @Test
    void searchFindsVirtualTracksAndTheAlbum() {
        List<SearchHit> hits = SearchEngine.search(snap, SearchScope.LIBRARY,
                new Query.Term(Field.TITLE, Op.CONTAINS, "finale"));
        assertThat(hits).hasSize(1);
        assertThat(((SearchHit.TrackHit) hits.get(0)).track().ref().qualified()).isEqualTo("main/Cues/disc.wav#3");

        List<SearchHit> album = SearchEngine.search(snap, SearchScope.LIBRARY,
                new Query.Term(Field.TITLE, Op.CONTAINS, "live album"));
        assertThat(album).anyMatch(h -> h instanceof SearchHit.FolderHit f && f.folder().isVirtual());
    }
}
