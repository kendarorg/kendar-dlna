package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.item.MusicTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.shared.RootRef;

/** Full DIDL metadata, art URIs, filter handling, namespaces and escaping. */
class BrowseMetadataTest {

    private static final String BASE = "http://10.0.0.5:8200";

    @TempDir
    Path tmp;

    Folder root;
    Folder album;
    Folder plain;
    Track full;
    Track bare;
    Track embedded;
    BrowseEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        Path music = Files.createDirectories(tmp.resolve("music/alb"));
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", b);
        Files.write(music.resolve("front.jpg"), b.toByteArray());

        DirectorySettings overlay = new DirectorySettings(null, null,
                new AlbumInfo("Overlay <Album> & Co", "Overlay Artist", 1999, "Jazz", null, null), 0, null);
        root = new Folder(RootRef.parse("r"), 0, DirectorySettings.DEFAULT, null, false, false);
        album = new Folder(RootRef.parse("r/alb"), 0, overlay, null, true, false);
        plain = new Folder(RootRef.parse("r/plain"), 0, DirectorySettings.DEFAULT, null, false, false);
        full = new Track(RootRef.parse("r/alb/01 a.flac"), 1, 5_000_000, Format.FLAC,
                new TrackTags("Tom & \"Jerry\" <live>", "The Artist", "Tag Album", null, "Rock", 2001, 3, null, null),
                215_500, 800, 44100, 2, false);
        bare = new Track(RootRef.parse("r/plain/untitled.wav"), 1, 100, Format.WAV, TrackTags.EMPTY, 0, 0, 0, 0,
                false);
        embedded = new Track(RootRef.parse("r/plain/emb.mp3"), 1, 100, Format.MP3, TrackTags.EMPTY, 1000, 128,
                44100, 2, true);
        Snapshot s = new Snapshot(42, Instant.EPOCH, List.of(root, album, plain), List.of(full, bare, embedded),
                List.of());
        SnapshotHolder holder = new SnapshotHolder(s);
        ArtworkService art = new ArtworkService(holder, ref -> music.getParent().resolve(ref.rel()),
                ref -> Optional.empty(), tmp.resolve("thumbs"));
        engine = new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> BASE, art);
    }

    private BrowseResult browse(String id, BrowseFlag flag, String filter) throws Exception {
        return engine.browse(id, flag, filter, 0, 0);
    }

    private MusicTrack item(Track t, String filter) throws Exception {
        BrowseResult r = browse(ObjectIds.track(t), BrowseFlag.METADATA, filter);
        DIDLContent c = new DIDLParser().parse(r.getResult());
        return (MusicTrack) c.getItems().get(0);
    }

    @Test
    void trackCarriesFullMetadataWithAlbumOverlay() throws Exception {
        MusicTrack m = item(full, "*");

        assertThat(m.getTitle()).isEqualTo("Tom & \"Jerry\" <live>");
        assertThat(m.getCreator()).isEqualTo("The Artist");
        assertThat(m.getFirstArtist().getName()).isEqualTo("The Artist");
        assertThat(m.getAlbum()).isEqualTo("Overlay <Album> & Co");       // overlay wins
        assertThat(m.getOriginalTrackNumber()).isEqualTo(3);
        assertThat(m.getDate()).isEqualTo("1999-01-01");                  // overlay year
        assertThat(m.getFirstPropertyValue(org.jupnp.support.model.DIDLObject.Property.UPNP.GENRE.class))
                .isEqualTo("Jazz");                                        // overlay genre
        assertThat(m.getClazz().getValue()).isEqualTo("object.item.audioItem.musicTrack");
        assertThat(m.isRestricted()).isTrue();
        var res = m.getFirstResource();
        assertThat(res.getDuration()).isEqualTo("0:03:35.500");
        assertThat(res.getSize()).isEqualTo(5_000_000L);
        assertThat(res.getBitrate()).isEqualTo(100_000L);                 // 800 kbps = 100000 bytes/s
        assertThat(res.getSampleFrequency()).isEqualTo(44100L);
        assertThat(res.getNrAudioChannels()).isEqualTo(2L);
        assertThat(res.getProtocolInfo().toString()).startsWith("http-get:*:audio/flac:");
    }

    @Test
    void trackWithoutTagsFallsBackToFileNameAndOmitsUnknownFields() throws Exception {
        MusicTrack m = item(bare, "*");

        assertThat(m.getTitle()).isEqualTo("untitled");
        assertThat(m.getCreator()).isNull();
        assertThat(m.getAlbum()).isNull();
        assertThat(m.getDate()).isNull();
        assertThat(m.getOriginalTrackNumber()).isNull();
        var res = m.getFirstResource();
        assertThat(res.getDuration()).isNull();
        assertThat(res.getBitrate()).isNull();
        assertThat(res.getSampleFrequency()).isNull();
        assertThat(res.getNrAudioChannels()).isNull();
    }

    @Test
    void albumArtUriUsesDirectoryFrontJpgOrPerTrackEmbeddedArtWithJpegTnProfile() throws Exception {
        String xmlFront = browse(ObjectIds.track(full), BrowseFlag.METADATA, "*").getResult();
        assertThat(xmlFront).contains(">" + BASE + "/art/f/" + album.id() + "/tn</upnp:albumArtURI>");
        assertThat(xmlFront).contains("dlna:profileID=\"JPEG_TN\"");

        String xmlEmb = browse(ObjectIds.track(embedded), BrowseFlag.METADATA, "*").getResult();
        assertThat(xmlEmb).contains(">" + BASE + "/art/t/" + embedded.id() + "/tn</upnp:albumArtURI>");

        assertThat(browse(ObjectIds.track(bare), BrowseFlag.METADATA, "*").getResult())
                .doesNotContain("albumArtURI");
    }

    @Test
    void didlDeclaresNamespacesAndEscapesXml() throws Exception {
        String xml = browse(ObjectIds.track(full), BrowseFlag.METADATA, "*").getResult();

        assertThat(xml).contains("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
                .contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
                .contains("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"")
                .contains("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\"");
        assertThat(xml).contains("<dc:title>Tom &amp; \"Jerry\" &lt;live&gt;</dc:title>")
                .contains("<upnp:album>Overlay &lt;Album&gt; &amp; Co</upnp:album>")
                .doesNotContain("<live>");
        assertThat(new DIDLParser().parse(xml).getItems()).hasSize(1);   // well-formed round trip
    }

    @Test
    void containersHaveChildCountArtAndOverlayTitle() throws Exception {
        BrowseResult r = browse(ObjectIds.folder(root), BrowseFlag.DIRECT_CHILDREN, "*");
        String xml = r.getResult();
        DIDLContent c = new DIDLParser().parse(xml);

        var alb = c.getContainers().stream().filter(x -> x.getId().equals(ObjectIds.folder(album))).findFirst()
                .orElseThrow();
        assertThat(alb.getTitle()).isEqualTo("Overlay <Album> & Co");
        assertThat(alb.getChildCount()).isEqualTo(1);
        assertThat(alb.getClazz().getValue()).isEqualTo("object.container.storageFolder");
        assertThat(xml).contains(">" + BASE + "/art/f/" + album.id() + "/tn</upnp:albumArtURI>");
        var pl = c.getContainers().stream().filter(x -> x.getId().equals(ObjectIds.folder(plain))).findFirst()
                .orElseThrow();
        assertThat(pl.getTitle()).isEqualTo("plain");
        assertThat(pl.getChildCount()).isEqualTo(2);
        // plain contains a track with embedded art -> folder art falls back to it, served by folder id
        assertThat(xml).contains("/art/f/" + plain.id() + "/tn");
    }

    @Test
    void filterLimitsOptionalPropertiesButKeepsRequiredOnes() throws Exception {
        String xml = browse(ObjectIds.track(full), BrowseFlag.METADATA, "upnp:artist").getResult();

        assertThat(xml).contains("<dc:title>").contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
                .contains("id=\"" + ObjectIds.track(full) + "\"").contains("parentID=").contains("restricted=");
        assertThat(xml).contains("<upnp:artist>The Artist</upnp:artist>");
        assertThat(xml).doesNotContain("upnp:album").doesNotContain("upnp:genre").doesNotContain("dc:date")
                .doesNotContain("albumArtURI").doesNotContain("<dc:creator>").doesNotContain("<res")
                .doesNotContain("originalTrackNumber");
    }

    @Test
    void filterWithResAttributesAndChildCount() throws Exception {
        String xml = browse(ObjectIds.track(full), BrowseFlag.METADATA, "res@duration").getResult();
        assertThat(xml).contains("duration=\"0:03:35.500\"").doesNotContain("size=").doesNotContain("bitrate=");
        assertThat(xml).contains("protocolInfo=");   // required attribute of res

        String noCount = browse(ObjectIds.folder(album), BrowseFlag.METADATA, "dc:title").getResult();
        assertThat(noCount).doesNotContain("childCount");
        String count = browse(ObjectIds.folder(album), BrowseFlag.METADATA, "@childCount").getResult();
        assertThat(count).contains("childCount=\"1\"");
    }

    @Test
    void pagingEdgeCases() throws Exception {
        String id = ObjectIds.folder(plain);
        assertThat(engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", -5, 1).getCountLong()).isEqualTo(1);
        BrowseResult beyond = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", 2, 10);
        assertThat(beyond.getCountLong()).isZero();
        assertThat(beyond.getTotalMatchesLong()).isEqualTo(2);
        BrowseResult huge = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", 1, Long.MAX_VALUE);
        assertThat(huge.getCountLong()).isEqualTo(1);
        assertThat(huge.getTotalMatchesLong()).isEqualTo(2);
        BrowseResult hugeStart = engine.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", Long.MAX_VALUE, 5);
        assertThat(hugeStart.getCountLong()).isZero();
        assertThat(hugeStart.getTotalMatchesLong()).isEqualTo(2);
        assertThat(engine.systemUpdateId()).isEqualTo(42);
    }
}
