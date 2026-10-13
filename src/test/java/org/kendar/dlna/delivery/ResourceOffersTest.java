package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.clients.ClientProfile;
import org.kendar.dlna.clients.MediaFormat;
import org.kendar.dlna.clients.Mp3Quirk;
import org.kendar.dlna.clients.ProfileMatch;
import org.kendar.dlna.clients.ResOrder;
import org.kendar.dlna.clients.TranscodeTarget;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.transcoding.MidiRenderer;
import org.kendar.dlna.transcoding.Transcoder;

class ResourceOffersTest {

    @TempDir
    Path dir;

    private TranscodeService service(boolean enabled, boolean midi) {
        return new TranscodeService(new Transcoder(midi ? new MidiRenderer(null) : null),
                new TranscodeCache(dir.resolve("c"), 1 << 20, Duration.ofHours(1), Clock.systemUTC()), enabled, 320, "",
                null);
    }

    private static Track track(String name, Format f, long samples, int rate, long rendered) {
        return new Track(RootRef.parse("m/" + name), 1, 5_000_000, f, TrackTags.EMPTY, 200_000, 900, rate, 2, false,
                samples, rendered);
    }

    private static ClientProfile profile(TranscodeTarget target, int bitrate, ResOrder order, Mp3Quirk... quirks) {
        return new ClientProfile("p", "", ProfileMatch.userAgent("x"), Set.of(MediaFormat.MP3), order, target,
                bitrate, Set.of(quirks));
    }

    @Test
    void defaultProfileMp3IsTheOriginalOnly() {
        ResourceOffers o = new ResourceOffers(service(true, false));
        List<ResourceOffers.Offer> offers = o.offers(track("a.mp3", Format.MP3, 0, 44100, 0),
                ClientProfile.builtinDefault());
        assertThat(offers).singleElement().satisfies(x -> {
            assertThat(x.variant()).isEqualTo(StreamVariant.NATIVE);
            assertThat(x.mimeType()).isEqualTo("audio/mpeg");
            assertThat(x.protocolInfo()).isEqualTo(MediaTypes.protocolInfo(Format.MP3));
            assertThat(x.sizeBytes()).isEqualTo(5_000_000);
            assertThat(x.bytesPerSecond()).isEqualTo(900 * 125L);
        });
    }

    @Test
    void otherFormatsGetTheTranscodedMp3FirstWithExactSizeAndDuration() {
        ResourceOffers o = new ResourceOffers(service(true, false));
        Track flac = track("a.flac", Format.FLAC, 441_000, 44100, 0);
        List<ResourceOffers.Offer> offers = o.offers(flac, ClientProfile.builtinDefault());
        assertThat(offers).extracting(x -> x.variant().path()).containsExactly("mp3", "native");
        ResourceOffers.Offer mp3 = offers.get(0);
        assertThat(mp3.transcoded()).isTrue();
        assertThat(mp3.protocolInfo()).isEqualTo("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=1;"
                + "DLNA.ORG_FLAGS=01700000000000000000000000000000");
        long frames = (long) Math.ceil(441_000L * 48000 / 44100 / 1152.0);
        assertThat(mp3.sizeBytes()).isEqualTo(frames * 960).isEqualTo(Transcoder.mp3Length(441_000, 44100, 320));
        assertThat(mp3.durationMillis()).isEqualTo(10_000);
        assertThat(mp3.bytesPerSecond()).isEqualTo(320 * 125L);
        assertThat(mp3.sampleRate()).isEqualTo(48000);
        assertThat(mp3.channels()).isEqualTo(2);
        assertThat(offers.get(1).protocolInfo()).isEqualTo(MediaTypes.protocolInfo(Format.FLAC));
    }

    @Test
    void sizeIsOmittedWhenTheSampleCountIsNotKnownYet() {
        ResourceOffers o = new ResourceOffers(service(true, false));
        ResourceOffers.Offer mp3 = o.offers(track("a.ogg", Format.OGG, 0, 44100, 0), ClientProfile.builtinDefault())
                .get(0);
        assertThat(mp3.sizeBytes()).isZero();
        assertThat(mp3.durationMillis()).isEqualTo(200_000);   // the tag duration still answers
    }

    @Test
    void midiHasOnlyTheMp3AndItsSizeOnlyAfterTheFirstRender() {
        ResourceOffers o = new ResourceOffers(service(true, true));
        Track fresh = track("t.mid", Format.MIDI, 0, 0, 0);
        List<ResourceOffers.Offer> before = o.offers(fresh, ClientProfile.builtinDefault());
        assertThat(before).singleElement().satisfies(x -> {
            assertThat(x.variant().path()).isEqualTo("mp3");
            assertThat(x.sizeBytes()).isZero();
            assertThat(x.durationMillis()).isEqualTo(200_000);   // the sequence duration
        });
        Track rendered = track("t.mid", Format.MIDI, 480_000, 0, 10_000);
        assertThat(o.offers(rendered, ClientProfile.builtinDefault())).singleElement().satisfies(x -> {
            assertThat(x.sizeBytes()).isEqualTo(Transcoder.mp3Length(480_000, 48000, 320));
            assertThat(x.durationMillis()).isEqualTo(10_000);
        });
    }

    @Test
    void midiIsNotOfferedWithoutARendererOrWithTranscodingOff() {
        Track midi = track("t.mid", Format.MIDI, 0, 0, 0);
        assertThat(new ResourceOffers(service(true, false)).offers(midi, ClientProfile.builtinDefault())).isEmpty();
        assertThat(new ResourceOffers(service(false, true)).offers(midi, ClientProfile.builtinDefault())).isEmpty();
        assertThat(ResourceOffers.nativeOnly().offers(midi, ClientProfile.builtinDefault())).isEmpty();
    }

    @Test
    void transcodingOffOrAbsentLeavesOnlyOriginals() {
        Track flac = track("a.flac", Format.FLAC, 441_000, 44100, 0);
        assertThat(new ResourceOffers(service(false, false)).offers(flac, ClientProfile.builtinDefault()))
                .extracting(x -> x.variant().path()).containsExactly("native");
        assertThat(ResourceOffers.nativeOnly().offers(flac, ClientProfile.builtinDefault()))
                .extracting(x -> x.variant().path()).containsExactly("native");
    }

    @Test
    void profilesOverrideBitrateOrderAndTarget() {
        ResourceOffers o = new ResourceOffers(service(true, false));
        Track flac = track("a.flac", Format.FLAC, 441_000, 44100, 0);

        List<ResourceOffers.Offer> br = o.offers(flac, profile(TranscodeTarget.MP3, 192, ResOrder.NATIVE_FIRST));
        assertThat(br).extracting(x -> x.variant().path()).containsExactly("native", "mp3-192");
        assertThat(br.get(1).sizeBytes()).isEqualTo(Transcoder.mp3Length(441_000, 44100, 192));
        assertThat(br.get(1).bytesPerSecond()).isEqualTo(192 * 125L);

        assertThat(o.offers(flac, profile(TranscodeTarget.MP3, 320, ResOrder.TRANSCODED_FIRST)).get(0).variant().path())
                .as("the configured default bitrate needs no suffix").isEqualTo("mp3");

        List<ResourceOffers.Offer> lpcm = o.offers(flac, profile(TranscodeTarget.LPCM, 0, ResOrder.TRANSCODED_FIRST,
                Mp3Quirk.SINGLE_RES));
        assertThat(lpcm).singleElement().satisfies(x -> {
            assertThat(x.variant()).isEqualTo(StreamVariant.LPCM);
            assertThat(x.mimeType()).isEqualTo("audio/L16;rate=44100;channels=2");
            assertThat(x.protocolInfo()).startsWith("http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM;");
            assertThat(x.sizeBytes()).isEqualTo(441_000L * 4);
            assertThat(x.bytesPerSecond()).isEqualTo(44100 * 4L);
        });

        // an MP3 the profile refuses to play natively is transcoded (size from the lazily probed count if known)
        List<ResourceOffers.Offer> avoid = o.offers(track("a.mp3", Format.MP3, 441_000, 44100, 0),
                profile(TranscodeTarget.MP3, 0, ResOrder.TRANSCODED_FIRST, Mp3Quirk.AVOID_NATIVE_MP3));
        assertThat(avoid).extracting(x -> x.variant().path()).containsExactly("mp3", "native");
    }

    @Test
    void urlsCarryTheVariantSegment() {
        DefaultStreamUrlBuilder b = new DefaultStreamUrlBuilder();
        assertThat(b.url("http://h:1", "c", "i")).isEqualTo("http://h:1/stream/c/i");
        assertThat(b.url("http://h:1", "c", "i", StreamVariant.NATIVE)).isEqualTo("http://h:1/stream/c/i");
        assertThat(b.url("http://h:1", "c", "i", StreamVariant.mp3(0))).isEqualTo("http://h:1/stream/c/i/mp3");
        assertThat(b.url("http://h:1", "c", "i", StreamVariant.mp3(256))).isEqualTo("http://h:1/stream/c/i/mp3-256");
        assertThat(b.url("http://h:1", "c", "i", StreamVariant.LPCM)).isEqualTo("http://h:1/stream/c/i/lpcm");
    }

    @Test
    void variantParsingAcceptsOnlyKnownSegments() {
        assertThat(StreamVariant.parse(null)).contains(StreamVariant.NATIVE);
        assertThat(StreamVariant.parse("native")).contains(StreamVariant.NATIVE);
        assertThat(StreamVariant.parse("lpcm")).contains(StreamVariant.LPCM);
        assertThat(StreamVariant.parse("mp3")).contains(StreamVariant.mp3(0));
        assertThat(StreamVariant.parse("mp3-128")).contains(StreamVariant.mp3(128));
        assertThat(StreamVariant.parse("mp3-129")).isEmpty();
        assertThat(StreamVariant.parse("mp3-x")).isEmpty();
        assertThat(StreamVariant.parse("flac")).contains(StreamVariant.pcm(StreamVariant.Kind.FLAC, 16, 0));
        assertThat(StreamVariant.parse("wav-24-r96000")).contains(StreamVariant.pcm(StreamVariant.Kind.WAV, 24, 96000));
        assertThat(StreamVariant.parse("wav-r96000").get().path()).isEqualTo("wav-r96000");
        assertThat(StreamVariant.parse("lpcm-16")).isEmpty();
        assertThat(StreamVariant.parse("lpcm-r100")).isEmpty();
        assertThat(StreamVariant.parse("opus")).isEmpty();
        assertThat(StreamVariant.mp3(0).resolved(256)).isEqualTo(StreamVariant.mp3(256));
        assertThat(StreamVariant.mp3(128).resolved(256)).isEqualTo(StreamVariant.mp3(128));
        assertThat(StreamVariant.LPCM.resolved(256)).isEqualTo(StreamVariant.LPCM);
    }

    @Test
    void aacAndAlacGetTheTranscodedMp3FirstAndTheMp4OriginalSecondByDefault() {
        ResourceOffers o = new ResourceOffers(service(true, false));
        for (Format f : new Format[] {Format.AAC, Format.ALAC}) {
            Track t = track("a.m4a", f, 88_200, 44100, 0);
            List<ResourceOffers.Offer> offers = o.offers(t, ClientProfile.builtinDefault());
            assertThat(offers).extracting(x -> x.variant().path()).containsExactly("mp3", "native");
            assertThat(offers.get(0).sizeBytes()).isEqualTo(Transcoder.mp3Length(88_200, 44100, 320));
            assertThat(offers.get(0).durationMillis()).isEqualTo(2000);
            assertThat(offers.get(1).mimeType()).isEqualTo("audio/mp4");
            assertThat(offers.get(1).protocolInfo()).doesNotContain("DLNA.ORG_PN");
        }
        Track adts = track("a.aac", Format.ADTS, 19_456, 44100, 0);
        assertThat(o.offers(adts, ClientProfile.builtinDefault())).extracting(x -> x.mimeType())
                .containsExactly("audio/mpeg", "audio/aac");
    }

    @Test
    void aClientThatPlaysAacNativelyGetsOnlyTheOriginal() {
        ResourceOffers o = new ResourceOffers(service(true, false));
        ClientProfile vlc = new ClientProfile("v", "", ProfileMatch.userAgent("x"),
                Set.of(MediaFormat.MP3, MediaFormat.AAC), ResOrder.TRANSCODED_FIRST, TranscodeTarget.MP3, 0, Set.of());
        assertThat(o.offers(track("a.m4a", Format.AAC, 88_200, 44100, 0), vlc)).singleElement()
                .satisfies(x -> assertThat(x.variant()).isEqualTo(StreamVariant.NATIVE));
        assertThat(o.offers(track("b.m4a", Format.ALAC, 88_200, 44100, 0), vlc)).hasSize(2);
    }
}
