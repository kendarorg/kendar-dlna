package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.Res;
import org.jupnp.support.model.item.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.clients.ClientProfiles;
import org.kendar.dlna.clients.ClientRequest;
import org.kendar.dlna.clients.ClientsConfig;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.delivery.ResourceOffers;
import org.kendar.dlna.delivery.TranscodeCache;
import org.kendar.dlna.delivery.TranscodeService;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.transcoding.MidiRenderer;
import org.kendar.dlna.transcoding.Transcoder;

/** Multi-res DIDL by client profile (plan section 11). */
class MultiResBrowseTest {

    private static final String BASE = "http://10.0.0.5:8200";

    @TempDir
    Path dir;

    private final Folder root = folder("m");

    private static Track track(String name, Format f, long samples, long rendered) {
        return new Track(RootRef.parse("m/" + name), 1, 3_000_000, f,
                new TrackTags(name, null, null, null, null, null, null, null, null), 180_000, 256, 44100, 2, false,
                samples, rendered);
    }

    private BrowseEngine engine(boolean transcoding, boolean midi, Track... tracks) {
        SnapshotHolder holder = new SnapshotHolder(snapshot(List.of(root), List.of(tracks)));
        TranscodeService service = new TranscodeService(new Transcoder(midi ? new MidiRenderer(null) : null),
                new TranscodeCache(dir.resolve("c"), 1 << 20, Duration.ofHours(1), Clock.systemUTC()), transcoding,
                320, "", null);
        return new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> BASE,
                new ArtworkService(holder, ref -> null, ref -> java.util.Optional.empty(), Path.of("unused")),
                new org.kendar.dlna.shuffle.Shuffle(holder::current, Clock.systemUTC(),
                        org.kendar.dlna.shuffle.Shuffle.DEFAULT_TTL),
                new org.kendar.dlna.views.Views(holder::current), new ResourceOffers(service),
                ClientProfiles.resolver(ClientsConfig.defaults(), dir));
    }

    private static List<Res> resources(BrowseResult r, String title) throws Exception {
        DIDLContent didl = new DIDLParser().parse(r.getResult());
        Item item = didl.getItems().stream().filter(i -> i.getTitle().equals(title)).findFirst().orElseThrow();
        return item.getResources();
    }

    private BrowseResult browse(BrowseEngine e, String ua, String filter) throws Exception {
        ClientId id = new ClientId("10.0.0.9", ua);
        return e.browse(ObjectIds.folder(root), BrowseFlag.DIRECT_CHILDREN, filter, 0, 0, id,
                ClientRequest.of("10.0.0.9", ua));
    }

    @Test
    void defaultClientGetsTranscodedMp3FirstAndOriginalSecondExceptForMp3AndMidi() throws Exception {
        Track flac = track("song.flac", Format.FLAC, 441_000, 0);
        Track mp3 = track("plain.mp3", Format.MP3, 0, 0);
        Track midi = track("tune.mid", Format.MIDI, 0, 0);
        BrowseResult r = browse(engine(true, true, flac, mp3, midi), "Unknown/1.0", "*");

        List<Res> f = resources(r, "song.flac");
        assertThat(f).hasSize(2);
        assertThat(f.get(0).getProtocolInfo().toString()).contains("audio/mpeg").contains("DLNA.ORG_PN=MP3")
                .contains("DLNA.ORG_CI=1");
        assertThat(f.get(0).getValue()).endsWith("/" + flac.id() + "/mp3");
        assertThat(f.get(0).getSize()).isEqualTo(Transcoder.mp3Length(441_000, 44100, 320));
        assertThat(f.get(0).getDuration()).isEqualTo("0:00:10.000");
        assertThat(f.get(0).getBitrate()).isEqualTo(320 * 125L);
        assertThat(f.get(0).getSampleFrequency()).isEqualTo(48000L);
        assertThat(f.get(0).getNrAudioChannels()).isEqualTo(2L);
        assertThat(f.get(1).getProtocolInfo().toString()).contains("audio/flac");
        assertThat(f.get(1).getValue()).endsWith("/" + flac.id()).doesNotContain("/mp3");
        assertThat(f.get(1).getSize()).isEqualTo(3_000_000L);

        List<Res> m = resources(r, "plain.mp3");
        assertThat(m).hasSize(1);
        assertThat(m.get(0).getProtocolInfo().toString()).contains("DLNA.ORG_PN=MP3").doesNotContain("DLNA.ORG_CI");

        List<Res> mid = resources(r, "tune.mid");
        assertThat(mid).hasSize(1);
        assertThat(mid.get(0).getValue()).endsWith("/mp3");
        assertThat(mid.get(0).getSize()).as("MIDI size is omitted until rendered").isNull();
        assertThat(mid.get(0).getDuration()).isEqualTo("0:03:00.000");
    }

    @Test
    void renderedMidiLengthAppearsAsExactSizeAndDuration() throws Exception {
        Track midi = track("tune.mid", Format.MIDI, 480_000 + 17, 10_000);
        List<Res> mid = resources(browse(engine(true, true, midi), "x", "*"), "tune.mid");
        assertThat(mid.get(0).getSize()).isEqualTo(Transcoder.mp3Length(480_017, 48000, 320));
        assertThat(mid.get(0).getDuration()).isEqualTo("0:00:10.000");
    }

    @Test
    void profilesDecideTheResSet() throws Exception {
        Track flac = track("song.flac", Format.FLAC, 441_000, 0);
        BrowseEngine e = engine(true, false, flac);
        assertThat(resources(browse(e, "VLC/3.0.20 LibVLC/3.0.20", "*"), "song.flac")).as("VLC plays FLAC").hasSize(1)
                .allSatisfy(x -> assertThat(x.getProtocolInfo().toString()).contains("audio/flac"));
        List<Res> samsung = resources(browse(e, "SEC_HHP_[TV] UE55", "*"), "song.flac");
        assertThat(samsung).as("Samsung gets one res only").hasSize(1);
        assertThat(samsung.get(0).getProtocolInfo().toString()).contains("audio/mpeg");
        assertThat(resources(browse(e, "Windows-Media-Player/12", "*"), "song.flac")).hasSize(2);
    }

    @Test
    void transcodingOffGivesOriginalsOnlyAndHidesMidi() throws Exception {
        Track flac = track("song.flac", Format.FLAC, 441_000, 0);
        Track midi = track("tune.mid", Format.MIDI, 0, 0);
        BrowseResult r = browse(engine(false, false, flac, midi), "x", "*");
        assertThat(resources(r, "song.flac")).hasSize(1);
        assertThat(resources(r, "tune.mid")).isEmpty();
    }

    @Test
    void theFilterAppliesToEveryRes() throws Exception {
        Track flac = track("song.flac", Format.FLAC, 441_000, 0);
        List<Res> res = resources(browse(engine(true, false, flac), "x", "dc:title,res@duration"), "song.flac");
        assertThat(res).hasSize(2);
        assertThat(res).allSatisfy(x -> {
            assertThat(x.getSize()).isNull();
            assertThat(x.getBitrate()).isNull();
            assertThat(x.getDuration()).isNotNull();
        });
    }

    @Test
    void searchHitsUseTheClientProfileToo() throws Exception {
        Track flac = track("song.flac", Format.FLAC, 441_000, 0);
        BrowseEngine e = engine(true, false, flac);
        BrowseResult def = e.search("0", "dc:title contains \"song\"", "*", 0, 0);
        assertThat(resources(def, "song.flac")).hasSize(2);
        BrowseResult sam = e.search("0", "dc:title contains \"song\"", "*", 0, 0,
                ClientRequest.of("10.0.0.9", "SEC_HHP_[TV]"));
        assertThat(resources(sam, "song.flac")).hasSize(1);
    }

    @Test
    void connectionManagerAdvertisesTheTranscodedForms() {
        String protocols = DlnaServer.sourceProtocols().toString();
        assertThat(protocols).contains("audio/mpeg", "DLNA.ORG_CI=1", "audio/L16;rate=44100;channels=2",
                "audio/L16;rate=48000;channels=2", "audio/flac");
        assertThat(protocols).doesNotContain("audio/midi");
    }
}
