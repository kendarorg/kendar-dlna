package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.AudioFiles;
import org.kendar.dlna.transcoding.MidiRenderer;
import org.kendar.dlna.transcoding.Mp3Frames;
import org.kendar.dlna.transcoding.Transcoder;

/**
 * The delivery of transcoded streams on real generated audio: exact Content-Length and Range, live versus cached
 * identity, MIDI rendering with length feedback, variant routing, prefetch into the cache.
 */
class TranscodedDeliveryTest {

    @TempDir
    Path dir;

    Path music;
    Track wav;       // 44.1 kHz, sample count unknown to the catalog (probed lazily)
    Track flac;      // fixture, sample count known
    Track midi;
    Snapshot snap;
    TranscodeCache cache;
    TranscodeService service;
    final List<long[]> rendered = new ArrayList<>();
    final List<RootRef> missing = new ArrayList<>();

    private Track trackOf(String name, Format f, int rate, long samples, int channels) throws IOException {
        Path p = music.resolve(name);
        return new Track(RootRef.parse("m/" + name), Files.getLastModifiedTime(p).toMillis(), Files.size(p), f,
                TrackTags.EMPTY, samples == 0 ? 1000 : samples * 1000 / Math.max(rate, 1), 0, rate, channels, false,
                samples, 0);
    }

    @BeforeEach
    void setUp() throws Exception {
        music = Files.createDirectories(dir.resolve("music"));
        AudioFiles.sineWav(music.resolve("a.wav"), 44100, 2, 44100 + 321, 440);
        AudioFiles.fixture("sweep.flac", music.resolve("b.flac"));
        AudioFiles.midi(music.resolve("c.mid"), "Arp", 3);
        wav = trackOf("a.wav", Format.WAV, 44100, 0, 2);
        flac = trackOf("b.flac", Format.FLAC, 44100, 88200, 2);
        midi = trackOf("c.mid", Format.MIDI, 0, 0, 0);
        snap = snapshot(List.of(folder("m")), List.of(wav, flac, midi));
        cache = new TranscodeCache(dir.resolve("cache"), 64L << 20, Duration.ofHours(1), Clock.systemUTC());
        service = new TranscodeService(new Transcoder(new MidiRenderer(null)), cache, true, 320, "builtin",
                (t, samples, millis) -> rendered.add(new long[] {samples, millis}));
    }

    private StreamResolver resolver(TranscodeService s, Prefetcher p) {
        return new StreamResolver(() -> snap, ref -> music.resolve(ref.rel()), missing::add, ContainerNavigation.NONE,
                s, p);
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
    void headAndGetOfTheMp3VariantHaveTheExactContentLength() throws Exception {
        StreamResolver r = resolver(service, null);
        long expected = Transcoder.mp3Length(44100 + 321, 44100, 320);

        StreamResolver.Resolved head = r.resolve("HEAD", path(wav, "mp3"), null);
        assertThat(head.plan().status()).isEqualTo(200);
        assertThat(head.plan().headers()).containsEntry("Content-Length", Long.toString(expected))
                .containsEntry("Content-Type", "audio/mpeg").containsEntry("Accept-Ranges", "bytes");
        assertThat(head.plan().headers().get("contentFeatures.dlna.org"))
                .contains("DLNA.ORG_PN=MP3").contains("DLNA.ORG_OP=01").contains("DLNA.ORG_CI=1");
        assertThat(head.body()).isNull();

        StreamResolver.Resolved get = r.resolve("GET", path(wav, "mp3"), null);
        byte[] body = send(get);
        assertThat((long) body.length).isEqualTo(expected);
        Mp3Frames.Scan scan = Mp3Frames.scan(body);
        assertThat(scan.trailingBytes()).isZero();
        assertThat(scan.allSameLength()).isTrue();
    }

    @Test
    void rangesOfALiveTranscodeHaveTheExactLengthAndTheSuffixRangeWorks() throws Exception {
        StreamResolver r = resolver(service, null);
        long total = Transcoder.mp3Length(44100 + 321, 44100, 320);
        StreamResolver.Resolved part = r.resolve("GET", path(wav, "mp3"), "bytes=" + (960 * 7 + 100) + "-");
        assertThat(part.plan().status()).isEqualTo(206);
        assertThat(part.plan().headers()).containsEntry("Content-Range",
                "bytes " + (960 * 7 + 100) + "-" + (total - 1) + "/" + total);
        assertThat((long) send(part).length).isEqualTo(total - (960 * 7 + 100));

        StreamResolver.Resolved tail = r.resolve("GET", path(wav, "mp3"), "bytes=-1000");
        assertThat(tail.plan().status()).isEqualTo(206);
        assertThat(send(tail)).hasSize(1000);

        assertThat(r.resolve("GET", path(wav, "mp3"), "bytes=" + total + "-").plan().status()).isEqualTo(416);
    }

    @Test
    void liveAndCachedOutputAreByteIdenticalAndCachedRangesAreExactSlices() throws Exception {
        StreamResolver r = resolver(service, null);
        byte[] live = send(r.resolve("GET", path(flac, "mp3"), null));
        assertThat(cache.entryCount()).as("live streaming does not populate the cache").isZero();

        service.prefetch(flac, music.resolve("b.flac"), StreamVariant.mp3(0), () -> false);
        assertThat(cache.entryCount()).isEqualTo(1);
        StreamResolver.Resolved cached = r.resolve("GET", path(flac, "mp3"), null);
        assertThat(cached.file()).as("served from the cache file").isNotNull();
        assertThat(send(cached)).isEqualTo(live);

        StreamResolver.Resolved range = r.resolve("GET", path(flac, "mp3"), "bytes=12345-23456");
        assertThat(range.plan().status()).isEqualTo(206);
        assertThat(send(range)).isEqualTo(Arrays.copyOfRange(live, 12345, 23457));
    }

    @Test
    void aCacheEntryOfTheWrongSizeIsNeverServed() throws Exception {
        String key = TranscodeCache.key("v1", flac.ref().qualified(), flac.mtime(), flac.size(), "mp3-320", "");
        cache.getOrCreate(key, new TranscodeCache.Source(flac.ref().qualified(), flac.mtime(), flac.size(), "mp3"),
                (out, c) -> out.write(new byte[10]), () -> false);
        StreamResolver.Resolved r = resolver(service, null).resolve("GET", path(flac, "mp3"), null);
        assertThat(r.file()).isNull();
        assertThat(r.plan().length()).isEqualTo(Transcoder.mp3Length(88200, 44100, 320));
        assertThat(cache.contains(key)).isFalse();
    }

    @Test
    void lpcmVariantIsBigEndianWithExactLengthAndSourceRateInTheMimeType() throws Exception {
        StreamResolver r = resolver(service, null);
        StreamResolver.Resolved get = r.resolve("GET", path(flac, "lpcm"), "bytes=1001-");
        assertThat(get.plan().headers()).containsEntry("Content-Type", "audio/L16;rate=44100;channels=2");
        assertThat(get.plan().headers().get("contentFeatures.dlna.org")).contains("DLNA.ORG_PN=LPCM");
        assertThat(get.plan().length()).isEqualTo(88200L * 4 - 1001);
        assertThat(send(get)).hasSize(88200 * 4 - 1001);
    }

    @Test
    void variantsThatAreNotOfferedAnswer404() {
        StreamResolver r = resolver(service, null);
        assertThat(r.resolve("GET", path(wav, "opus"), null).plan().status()).isEqualTo(404);
        assertThat(r.resolve("GET", path(wav, "mp3-123"), null).plan().status()).isEqualTo(404);
        assertThat(r.resolve("GET", path(midi, null), null).plan().status()).as("MIDI exists only rendered")
                .isEqualTo(404);
        assertThat(r.resolve("GET", path(midi, "native"), null).plan().status()).isEqualTo(404);
        StreamResolver plain = resolver(null, null);
        assertThat(plain.resolve("GET", path(wav, "mp3"), null).plan().status()).isEqualTo(404);
        assertThat(plain.resolve("GET", path(wav, null), null).plan().status()).isEqualTo(200);
        assertThat(plain.resolve("GET", path(wav, "native"), null).plan().status()).isEqualTo(200);
        TranscodeService off = new TranscodeService(new Transcoder(null), cache, false, 320, "", null);
        assertThat(resolver(off, null).resolve("GET", path(wav, "mp3"), null).plan().status()).isEqualTo(404);
    }

    @Test
    void anUndecodableSourceAnswers500NotACrash() throws Exception {
        Files.write(music.resolve("bad.flac"), new byte[500]);
        Track bad = trackOf("bad.flac", Format.FLAC, 44100, 0, 2);
        snap = snapshot(List.of(folder("m")), List.of(bad));
        assertThat(resolver(service, null).resolve("GET", path(bad, "mp3"), null).plan().status()).isEqualTo(500);
    }

    @Test
    void midiIsRenderedFullyIntoTheCacheBeforeTheFirstByteAndTheLengthIsReported() throws Exception {
        StreamResolver r = resolver(service, null);
        assertThat(service.knownSource(midi)).as("not rendered yet").isEmpty();

        StreamResolver.Resolved head = r.resolve("HEAD", path(midi, "mp3"), null);
        assertThat(head.plan().status()).isEqualTo(200);
        assertThat(rendered).hasSize(1);
        long frames = rendered.get(0)[0];
        assertThat(frames).isGreaterThan(48000L * 2);
        assertThat(rendered.get(0)[1]).isEqualTo(Math.round(frames * 1000.0 / 48000));
        long expected = Transcoder.mp3Length(frames, 48000, 320);
        assertThat(head.plan().headers()).containsEntry("Content-Length", Long.toString(expected));
        assertThat(cache.entryCount()).isEqualTo(1);

        StreamResolver.Resolved get = r.resolve("GET", path(midi, "mp3"), "bytes=960-1919");
        assertThat(get.file()).isNotNull();
        assertThat(send(get)).hasSize(960);
        assertThat(rendered).as("second request does not render again").hasSize(1);

        // once the catalog knows the length, the offered size equals the delivered one
        Track known = new Track(midi.ref(), midi.mtime(), midi.size(), midi.format(), midi.tags(),
                midi.durationMillis(), 0, 0, 0, false, frames, rendered.get(0)[1]);
        assertThat(service.knownSource(known)).contains(new TranscodeService.SourceLength(48000, frames));
        assertThat(TranscodeService.lengthOf(new TranscodeService.SourceLength(48000, frames),
                StreamVariant.mp3(320))).isEqualTo(expected);
    }

    @Test
    void midiWithoutARendererIsNotTranscodable() {
        TranscodeService noMidi = new TranscodeService(new Transcoder(null), cache, true, 320, "", null);
        assertThat(noMidi.canTranscode(Format.MIDI)).isFalse();
        assertThat(noMidi.canTranscode(Format.FLAC)).isTrue();
        assertThat(resolver(noMidi, null).resolve("GET", path(midi, "mp3"), null).plan().status()).isEqualTo(404);
    }

    @Test
    void midiLpcmIsRenderedAndCachedToo() throws Exception {
        StreamResolver.Resolved get = resolver(service, null).resolve("GET", path(midi, "lpcm"), null);
        assertThat(get.plan().headers()).containsEntry("Content-Type", "audio/L16;rate=48000;channels=2");
        assertThat(get.plan().length()).isEqualTo(rendered.get(0)[0] * 4);
        assertThat(send(get)).hasSize((int) get.plan().length());
    }

    @Test
    void prefetchOfTheNextTrackFillsTheCacheOnStreamStart() throws Exception {
        ContainerNavigation nav = new ContainerNavigation() {
            @Override
            public boolean knows(String containerId) {
                return true;
            }

            @Override
            public java.util.Optional<Track> next(String containerId, String itemId) {
                return itemId.equals(wav.id()) ? java.util.Optional.of(flac) : java.util.Optional.empty();
            }
        };
        try (Prefetcher prefetcher = new Prefetcher(service, nav, ref -> music.resolve(ref.rel()),
                Prefetcher.lowPriorityPool(1))) {
            StreamResolver r = new StreamResolver(() -> snap, ref -> music.resolve(ref.rel()), missing::add, nav,
                    service, prefetcher);
            send(r.resolve("GET", "/ctx/" + wav.id() + "/mp3", null, "10.0.0.7"));
            assertThat(prefetcher.awaitIdle(Duration.ofSeconds(30))).isTrue();
            assertThat(cache.entryCount()).isEqualTo(1);
            StreamResolver.Resolved next = r.resolve("GET", "/ctx/" + flac.id() + "/mp3", null, "10.0.0.7");
            assertThat(next.file()).as("the next track now comes from the cache").isNotNull();
            assertThat(next.plan().length()).isEqualTo(Transcoder.mp3Length(88200, 44100, 320));
            // HEAD never triggers prefetch
            AtomicInteger entries = new AtomicInteger(cache.entryCount());
            r.resolve("HEAD", "/ctx/" + flac.id() + "/mp3", null, "10.0.0.7");
            assertThat(cache.entryCount()).isEqualTo(entries.get());
        }
    }
}
