package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.delivery.StreamResolver;
import org.kendar.dlna.delivery.StreamVariant;
import org.kendar.dlna.delivery.TranscodeCache;
import org.kendar.dlna.delivery.TranscodeService;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.CueFixtures;
import org.kendar.dlna.testutil.LibraryFixture;
import org.kendar.dlna.transcoding.Mp3Frames;
import org.kendar.dlna.transcoding.Transcoder;

/**
 * Streaming the tracks of a cue sheet: exactly the time range of the track, with the exact Content-Length and
 * Range support, never a byte range of the whole file.
 */
class VirtualTrackStreamingTest {

    @TempDir
    Path tmp;

    LibraryFixture lib;
    LibraryRoots roots;
    TranscodeCache cache;
    TranscodeService service;
    final List<RootRef> missing = new ArrayList<>();
    List<Track> tracks;
    byte[] wavPcm;   // little-endian s16 stereo of the whole source

    @BeforeEach
    void setUp() throws IOException {
        lib = new LibraryFixture(tmp);
        Path wav = CueFixtures.write(lib.music.resolve("Cues"));
        lib.rescans.rescanNow(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, null);
        roots = new LibraryRoots(Map.of("main", lib.music.toString()));
        cache = new TranscodeCache(tmp.resolve("cache"), 64L << 20, Duration.ofHours(1), Clock.systemUTC());
        service = new TranscodeService(new Transcoder(null), cache, true, 320, "", null);
        tracks = lib.catalog.current().children(RootRef.parse("main/Cues/disc.wav")).stream()
                .map(c -> ((org.kendar.dlna.catalog.Child.TrackChild) c).track()).toList();
        byte[] all = Files.readAllBytes(wav);
        wavPcm = Arrays.copyOfRange(all, 44, all.length);
        assertThat(wavPcm).hasSize(CueFixtures.FRAMES * 4);
    }

    private StreamResolver resolver(TranscodeService s) {
        return new StreamResolver(lib.catalog::current, roots, missing::add, ContainerNavigation.NONE, s, null);
    }

    private String path(Track t, String variant) {
        return "/" + Folder.idOf(t.parentRef()) + "/" + t.id() + (variant == null ? "" : "/" + variant);
    }

    private static byte[] send(StreamResolver.Resolved r) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        r.body().writeTo(r.plan().start(), r.plan().length(), out);
        return out.toByteArray();
    }

    @Test
    void theMp3VariantHasTheExactLengthOfTheTimeRange() throws Exception {
        for (Track t : tracks) {
            long expected = Transcoder.mp3Length(t.sampleCount(), CueFixtures.RATE, 320);
            StreamResolver r = resolver(service);

            StreamResolver.Resolved head = r.resolve("HEAD", path(t, "mp3"), null);
            assertThat(head.plan().status()).isEqualTo(200);
            assertThat(head.plan().headers()).containsEntry("Content-Length", Long.toString(expected))
                    .containsEntry("Content-Type", "audio/mpeg").containsEntry("Accept-Ranges", "bytes");

            byte[] body = send(r.resolve("GET", path(t, "mp3"), null));
            assertThat((long) body.length).isEqualTo(expected);
            Mp3Frames.Scan scan = Mp3Frames.scan(body);
            assertThat(scan.trailingBytes()).isZero();
            assertThat(scan.allSameLength()).isTrue();
        }
    }

    @Test
    void aNativeRequestIsServedAsTheDecodedTrackNeverAsTheWholeFile() throws Exception {
        Track two = tracks.get(1);
        StreamResolver.Resolved r = resolver(service).resolve("GET", path(two, null), null);
        assertThat(r.plan().status()).isEqualTo(200);
        assertThat(r.plan().headers()).containsEntry("Content-Type", "audio/mpeg");
        assertThat(r.plan().length()).isEqualTo(Transcoder.mp3Length(two.sampleCount(), CueFixtures.RATE, 320));
        assertThat(r.plan().length()).isLessThan(Files.size(lib.music.resolve("Cues/disc.wav")));

        StreamResolver.Resolved explicit = resolver(service).resolve("GET", path(two, "native"), null);
        assertThat(explicit.plan().length()).isEqualTo(r.plan().length());
    }

    @Test
    void lpcmIsExactlyTheSamplesOfTheRange() throws Exception {
        for (Track t : tracks) {
            StreamResolver.Resolved r = resolver(service).resolve("GET", path(t, "lpcm"), null);
            assertThat(r.plan().length()).isEqualTo(t.segment().samples() * 4);
            assertThat(r.plan().headers()).containsEntry("Content-Type", "audio/L16;rate=44100;channels=2");
            byte[] be = send(r);
            byte[] expected = Arrays.copyOfRange(wavPcm, (int) t.segment().startSample() * 4,
                    (int) t.segment().endSample() * 4);
            byte[] le = be.clone();
            for (int i = 0; i + 1 < le.length; i += 2) {
                byte x = le[i];
                le[i] = le[i + 1];
                le[i + 1] = x;
            }
            assertThat(le).as("PCM of " + t.fileName()).isEqualTo(expected);
        }
    }

    @Test
    void rangesAreExactSlicesOfTheTrack() throws Exception {
        Track t = tracks.get(1);
        long total = t.segment().samples() * 4;
        byte[] whole = send(resolver(service).resolve("GET", path(t, "lpcm"), null));

        StreamResolver.Resolved from = resolver(service).resolve("GET", path(t, "lpcm"), "bytes=1001-");
        assertThat(from.plan().status()).isEqualTo(206);
        assertThat(from.plan().headers()).containsEntry("Content-Range", "bytes 1001-" + (total - 1) + "/" + total);
        assertThat(send(from)).isEqualTo(Arrays.copyOfRange(whole, 1001, whole.length));

        StreamResolver.Resolved mid = resolver(service).resolve("GET", path(t, "lpcm"), "bytes=4000-4999");
        assertThat(send(mid)).isEqualTo(Arrays.copyOfRange(whole, 4000, 5000));

        StreamResolver.Resolved tail = resolver(service).resolve("GET", path(t, "lpcm"), "bytes=-777");
        assertThat(tail.plan().status()).isEqualTo(206);
        assertThat(send(tail)).isEqualTo(Arrays.copyOfRange(whole, whole.length - 777, whole.length));

        assertThat(resolver(service).resolve("GET", path(t, "lpcm"), "bytes=" + total + "-").plan().status())
                .isEqualTo(416);

        long mp3Total = Transcoder.mp3Length(t.sampleCount(), CueFixtures.RATE, 320);
        StreamResolver.Resolved mp3Range = resolver(service).resolve("GET", path(t, "mp3"), "bytes=1920-");
        assertThat(mp3Range.plan().status()).isEqualTo(206);
        assertThat((long) send(mp3Range).length).isEqualTo(mp3Total - 1920);
    }

    @Test
    void liveAndCachedOutputAreByteIdenticalAndUseTheSameCacheRules() throws Exception {
        Track t = tracks.get(2);
        StreamResolver r = resolver(service);
        byte[] live = send(r.resolve("GET", path(t, "mp3"), null));
        assertThat(cache.entryCount()).as("live streaming does not populate the cache").isZero();

        service.prefetch(t, roots.resolve(t.ref()), StreamVariant.mp3(0), () -> false);
        assertThat(cache.entryCount()).isEqualTo(1);
        StreamResolver.Resolved cached = r.resolve("GET", path(t, "mp3"), null);
        assertThat(cached.file()).isNotNull();
        assertThat(send(cached)).isEqualTo(live);

        // another track of the same file has its own cache entry
        service.prefetch(tracks.get(0), roots.resolve(tracks.get(0).ref()), StreamVariant.mp3(0), () -> false);
        assertThat(cache.entryCount()).isEqualTo(2);
    }

    @Test
    void theVirtualRefResolvesToTheSourceFile() {
        assertThat(roots.resolve(RootRef.parse("main/Cues/disc.wav#2"))).isEqualTo(lib.music.resolve("Cues/disc.wav")
                .toAbsolutePath().normalize());
        assertThat(roots.resolve(RootRef.parse("main/Cues/disc.wav"))).isEqualTo(lib.music.resolve("Cues/disc.wav")
                .toAbsolutePath().normalize());
        assertThat(roots.resolve(RootRef.parse("other/Cues/disc.wav#2"))).isNull();
        assertThat(LibraryRoots.sourceRel("a/b#3.mp3")).isEqualTo("a/b#3.mp3");
        assertThat(LibraryRoots.sourceRel("a/b.mp3#12")).isEqualTo("a/b.mp3");
        assertThat(LibraryRoots.sourceRel("a.d/b#1")).isEqualTo("a.d/b#1");
    }

    @Test
    void withoutTranscodingVirtualTracksAnswer404() {
        StreamResolver plain = resolver(null);
        assertThat(plain.resolve("GET", path(tracks.get(0), null), null).plan().status()).isEqualTo(404);
        assertThat(plain.resolve("GET", path(tracks.get(0), "mp3"), null).plan().status()).isEqualTo(404);
    }

    @Test
    void aVanishedSourceFileAnswers404AndIsReported() throws IOException {
        Files.delete(lib.music.resolve("Cues/disc.wav"));
        assertThat(resolver(service).resolve("GET", path(tracks.get(0), "mp3"), null).plan().status()).isEqualTo(404);
        assertThat(missing).hasSize(1);
    }
}
