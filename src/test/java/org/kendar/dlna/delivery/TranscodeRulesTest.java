package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Segment;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.clients.ClientProfile;
import org.kendar.dlna.clients.MediaFormat;
import org.kendar.dlna.clients.ProfileMatch;
import org.kendar.dlna.clients.ResOrder;
import org.kendar.dlna.clients.TranscodeTarget;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.transcoding.Ffmpeg;
import org.kendar.dlna.transcoding.Transcoder;

/** Rules, output defaults and cut (passthrough) offers. */
class TranscodeRulesTest {

    @TempDir
    Path dir;

    private String ffmpegBefore = Ffmpeg.binary();

    @AfterEach
    void restore() {
        Ffmpeg.configure(ffmpegBefore);
        Ffmpeg.configureDsd(44_100, false);
    }

    private TranscodeService service(int bits, int maxRate) {
        return new TranscodeService(new Transcoder(null),
                new TranscodeCache(dir.resolve("c"), 1 << 20, Duration.ofHours(1), Clock.systemUTC()), true, 320, bits,
                maxRate, "", null);
    }

    private static Track track(String name, Format f, long samples, int rate, int kbps) {
        return new Track(RootRef.parse("m/" + name), 1, 5_000_000, f, TrackTags.EMPTY, 200_000, kbps, rate, 2, false,
                samples, 0);
    }

    private static ClientProfile profile(String id, TranscodeTarget target) {
        return new ClientProfile(id, "", ProfileMatch.userAgent("x"), Set.of(MediaFormat.MP3),
                ResOrder.TRANSCODED_FIRST, target, 0, Set.of());
    }

    private static TranscodeRules.Rule rule(Set<Format> formats, Set<String> profiles, int minRate, int maxBitrate,
            StreamVariant.Kind action, Integer outRate, Integer outBits, boolean hide) {
        return new TranscodeRules.Rule(formats, profiles, minRate, 0, 0, maxBitrate, action, 0, outRate, outBits, hide);
    }

    @Test
    void aRuleChoosesTheFormBySourceRateAndDevice() {
        TranscodeRules rules = new TranscodeRules(List.of(
                rule(Set.of(Format.FLAC), Set.of("tv"), 96_000, 0, StreamVariant.Kind.WAV, 48_000, 16, false)));
        ResourceOffers o = new ResourceOffers(service(16, 0), rules);
        Track hires = track("a.flac", Format.FLAC, 192_000, 96_000, 2000);
        List<ResourceOffers.Offer> tv = o.offers(hires, profile("tv", TranscodeTarget.MP3));
        assertThat(tv).extracting(x -> x.variant().path()).containsExactly("wav-r48000", "native");
        ResourceOffers.Offer wav = tv.get(0);
        assertThat(wav.mimeType()).isEqualTo("audio/wav");
        assertThat(wav.sampleRate()).isEqualTo(48_000);
        assertThat(wav.sizeBytes()).isEqualTo(44 + 96_000L * 4);
        assertThat(wav.durationMillis()).isEqualTo(2000);
        assertThat(wav.bytesPerSecond()).isEqualTo(48_000 * 4L);
        // another device, and a lower rate, are not touched
        assertThat(o.offers(hires, profile("phone", TranscodeTarget.MP3))).extracting(x -> x.variant().path())
                .containsExactly("mp3", "native");
        assertThat(o.offers(track("b.flac", Format.FLAC, 88_200, 44_100, 900), profile("tv", TranscodeTarget.MP3)))
                .extracting(x -> x.variant().path()).containsExactly("mp3", "native");
    }

    @Test
    void theFirstMatchingRuleWinsAndNativeAndHideOriginalWork() {
        TranscodeRules rules = new TranscodeRules(List.of(
                rule(Set.of(Format.FLAC), Set.of(), 0, 600, StreamVariant.Kind.MP3, null, null, true),
                rule(Set.of(Format.FLAC), Set.of(), 0, 0, null, null, null, false),
                rule(Set.of(Format.OGG), Set.of(), 0, 0, StreamVariant.Kind.LPCM, null, 24, false)));
        ResourceOffers o = new ResourceOffers(service(16, 0), rules);
        ClientProfile p = profile("p", TranscodeTarget.MP3);
        assertThat(o.offers(track("small.flac", Format.FLAC, 441_000, 44_100, 500), p))
                .extracting(x -> x.variant().path()).containsExactly("mp3");
        assertThat(o.offers(track("big.flac", Format.FLAC, 441_000, 44_100, 1400), p))
                .extracting(x -> x.variant().path()).containsExactly("native");
        List<ResourceOffers.Offer> ogg = o.offers(track("a.ogg", Format.OGG, 44_100, 44_100, 160), p);
        assertThat(ogg.get(0).variant().path()).isEqualTo("lpcm-24");
        assertThat(ogg.get(0).mimeType()).isEqualTo("audio/L24;rate=44100;channels=2");
        assertThat(ogg.get(0).sizeBytes()).isEqualTo(44_100L * 6);
        assertThat(ogg.get(0).protocolInfo()).doesNotContain("DLNA.ORG_PN=LPCM");
    }

    @Test
    void theServerOutputDefaultsApplyToThePcmTargetsOfProfiles() {
        ResourceOffers o = new ResourceOffers(service(24, 48_000));
        Track hires = track("a.flac", Format.FLAC, 192_000, 96_000, 2000);
        ResourceOffers.Offer lpcm = o.offers(hires, profile("p", TranscodeTarget.LPCM)).get(0);
        assertThat(lpcm.variant().path()).isEqualTo("lpcm-24-r48000");
        assertThat(lpcm.sampleRate()).isEqualTo(48_000);
        assertThat(lpcm.sizeBytes()).isEqualTo(96_000L * 6);
        // MP3 is never affected
        assertThat(o.offers(hires, profile("p", TranscodeTarget.MP3)).get(0).variant().path()).isEqualTo("mp3");
        // a source below the cap keeps its rate in the URL cap but is not resampled
        Track cd = track("b.flac", Format.FLAC, 44_100, 44_100, 900);
        ResourceOffers.Offer cdOffer = o.offers(cd, profile("p", TranscodeTarget.LPCM)).get(0);
        assertThat(cdOffer.sampleRate()).isEqualTo(44_100);
        assertThat(cdOffer.sizeBytes()).isEqualTo(44_100L * 6);
    }

    @Test
    void losslessTargetsNeedFfmpegAndFallBackToMp3() {
        Ffmpeg.configure("no-such-ffmpeg");
        ResourceOffers o = new ResourceOffers(service(16, 0));
        assertThat(o.offers(track("a.ogg", Format.OGG, 44_100, 44_100, 160), profile("p", TranscodeTarget.FLAC)).get(0)
                .variant().path()).isEqualTo("mp3");
    }

    @Test
    void flacOutputHasNoSizeButAMimeTypeWhenFfmpegIsThere() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Ffmpeg.configure(null) != null, "no ffmpeg on the PATH");
        ResourceOffers o = new ResourceOffers(service(16, 0));
        ResourceOffers.Offer flac = o.offers(track("a.ogg", Format.OGG, 44_100, 44_100, 160),
                profile("p", TranscodeTarget.FLAC)).get(0);
        assertThat(flac.variant().path()).isEqualTo("flac");
        assertThat(flac.mimeType()).isEqualTo("audio/flac");
        assertThat(flac.sizeBytes()).isZero();
        assertThat(flac.durationMillis()).isEqualTo(1000);
        ResourceOffers.Offer alac = o.offers(track("a.ogg", Format.OGG, 44_100, 44_100, 160),
                profile("p", TranscodeTarget.ALAC)).get(0);
        assertThat(alac.mimeType()).isEqualTo("audio/mp4");
    }

    @Test
    void dsdAsDopIsOnlyOfferedAs24BitPcm() {
        Ffmpeg.configure(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(Ffmpeg.available(), "no ffmpeg on the PATH");
        Ffmpeg.configureDsd(44_100, true);
        ResourceOffers o = new ResourceOffers(service(16, 0));
        Track dsd = track("a.dsf", Format.DSD, 176_400, 176_400, 5000);
        assertThat(o.offers(dsd, profile("p", TranscodeTarget.MP3)).get(0).variant().path()).isEqualTo("lpcm-24");
        assertThat(o.offers(dsd, profile("p", TranscodeTarget.WAV)).get(0).variant().path()).isEqualTo("wav-24");
    }

    // ---- virtual tracks cut from the original ----

    private static Segment seg(long start, long end) {
        return new Segment(1, start * 1000 / 44_100, end * 1000 / 44_100, start, end, "cue");
    }

    private static Track segment(String name, Format f, long start, long end) {
        return new Track(RootRef.parse("m/" + name), 1, 5_000_000, f, TrackTags.EMPTY, 2_000, 1411, 44_100, 2, false,
                end - start, 0, 0, seg(start, end));
    }

    @Test
    void aWavSectionIsOfferedAsTheOriginalWhenPassthroughAllowsIt() throws Exception {
        TranscodeService svc = service(16, 0).passthrough(List.of("wav"));
        ResourceOffers o = new ResourceOffers(svc);
        ClientProfile wavPlayer = new ClientProfile("w", "", ProfileMatch.userAgent("x"),
                Set.of(MediaFormat.MP3, MediaFormat.WAV), ResOrder.TRANSCODED_FIRST, TranscodeTarget.MP3, 0, Set.of());
        Track seg = segment("a.wav", Format.WAV, 44_100, 88_200);
        assertThat(svc.canSlice(seg)).isTrue();
        assertThat(o.offers(seg, wavPlayer)).extracting(x -> x.variant().path()).containsExactly("native");
        assertThat(o.offers(seg, wavPlayer).get(0).durationMillis()).isEqualTo(1000);
        // a client that cannot play WAV still gets the MP3, and the cut is off by default
        assertThat(o.offers(seg, ClientProfile.builtinDefault())).extracting(x -> x.variant().path())
                .contains("mp3");
        assertThat(service(16, 0).canSlice(seg)).isFalse();
        assertThat(svc.canSlice(segment("a.flac", Format.FLAC, 0, 10))).isFalse();
    }

    @Test
    void theWavSectionBodyIsAnExactCutWithItsOwnHeader() throws Exception {
        byte[] pcm = new byte[44_100 * 4 * 3];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 31 + i / 7);
        }
        Path wav = dir.resolve("a.wav");
        try (var in = new org.kendar.dlna.transcoding.WavStream(
                new org.kendar.dlna.transcoding.Transcoder.Rendered(pcm, 44_100).decoder(), 0)) {
            Files.write(wav, in.readAllBytes());
        }
        TranscodeService svc = service(16, 0).passthrough(List.of("wav"));
        Track seg = new Track(RootRef.parse("m/a.wav"), 1, Files.size(wav), Format.WAV, TrackTags.EMPTY, 1_000, 1411,
                44_100, 2, false, 44_100, 0, 0, seg(44_100, 88_200));
        StreamBody body = svc.sliceBody(seg, wav);
        assertThat(body.length()).isEqualTo(44 + 44_100L * 4);
        assertThat(body.mimeType()).isEqualTo("audio/wav");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(0, body.length(), out);
        byte[] bytes = out.toByteArray();
        assertThat(new String(bytes, 0, 4)).isEqualTo("RIFF");
        assertThat(java.util.Arrays.copyOfRange(bytes, 44, bytes.length))
                .isEqualTo(java.util.Arrays.copyOfRange(pcm, 44_100 * 4, 88_200 * 4));
        ByteArrayOutputStream part = new ByteArrayOutputStream();
        body.writeTo(30, 100, part);
        assertThat(part.toByteArray()).isEqualTo(java.util.Arrays.copyOfRange(bytes, 30, 130));
    }

    @Test
    void anMp3SectionIsACutAtFrameBoundaries() throws Exception {
        Transcoder.Rendered r = new Transcoder.Rendered(
                sine(48_000, 48_000 * 3), 48_000);
        byte[] mp3;
        try (var in = Transcoder.open(r, org.kendar.dlna.transcoding.TranscodeOutput.mp3(320), 0)) {
            mp3 = in.readAllBytes();
        }
        Path f = Files.write(dir.resolve("a.mp3"), mp3);
        TranscodeService svc = service(16, 0).passthrough(List.of("mp3"));
        Track seg = new Track(RootRef.parse("m/a.mp3"), 1, mp3.length, Format.MP3, TrackTags.EMPTY, 1_000, 320, 48_000,
                2, false, 48_000, 0, 0, seg(48_000, 96_000));
        StreamBody body = svc.sliceBody(seg, f);
        // frames of 960 bytes and 1152 samples: from frame 41 (47232) to frame 83 (95616..96768)
        assertThat(body.length() % 960).isZero();
        assertThat(body.length()).isBetween(41L * 960, 44L * 960);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(0, body.length(), out);
        assertThat(out.toByteArray()).isEqualTo(java.util.Arrays.copyOfRange(mp3, 41 * 960, 41 * 960 + (int) body.length()));
    }

    private static byte[] sine(int rate, int frames) {
        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short s = (short) Math.round(0.4 * 32767 * Math.sin(2 * Math.PI * 440 * i / rate));
            out[i * 4] = (byte) s;
            out[i * 4 + 1] = (byte) (s >> 8);
            out[i * 4 + 2] = (byte) s;
            out[i * 4 + 3] = (byte) (s >> 8);
        }
        return out;
    }
}
