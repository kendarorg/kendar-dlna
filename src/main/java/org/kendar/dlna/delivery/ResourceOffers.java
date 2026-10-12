package org.kendar.dlna.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.clients.ClientProfile;
import org.kendar.dlna.clients.MediaFormat;
import org.kendar.dlna.clients.ResKind;
import org.kendar.dlna.clients.TranscodeTarget;
import org.kendar.dlna.transcoding.LpcmStream;

/**
 * The {@code res} elements to publish for a track to a client with a given profile (plan section 11):
 * MP3 originals alone; other formats get the transcoded MP3 first and the original second; MIDI only the MP3 (or
 * LPCM by profile); profiles can reorder, hide or force LPCM. Sizes and durations are exact for transcoded forms
 * whenever the sample count is known (MIDI: only after its first render).
 */
public final class ResourceOffers {

    /**
     * One {@code res}. {@code sizeBytes} and {@code durationMillis} are 0 when unknown; {@code bytesPerSecond} is the
     * DIDL {@code bitrate} (bytes per second).
     */
    public record Offer(StreamVariant variant, String mimeType, String protocolInfo, long sizeBytes,
            long durationMillis, long bytesPerSecond, int sampleRate, int channels) {
        public boolean transcoded() {
            return variant.transcoded();
        }
    }

    private final TranscodeService transcoding;

    /** @param transcoding null = no transcoding: only native offers (MIDI has none) */
    public ResourceOffers(TranscodeService transcoding) {
        this.transcoding = transcoding;
    }

    public static ResourceOffers nativeOnly() {
        return new ResourceOffers(null);
    }

    public List<Offer> offers(Track t, ClientProfile profile) {
        MediaFormat mf = MediaFormat.valueOf(t.format().name());
        boolean canTranscode = transcoding != null && transcoding.canTranscode(t.format());
        List<Offer> out = new ArrayList<>(2);
        for (ResKind kind : profile.resPlan(mf)) {
            if (kind == ResKind.NATIVE) {
                if (!t.format().rendered() && !t.isSegment()) {
                    out.add(nativeOffer(t));
                }
            } else if (canTranscode) {
                out.add(transcodedOffer(t, profile));
            }
        }
        if (out.isEmpty() && t.isSegment()) {
            if (canTranscode) {
                out.add(transcodedOffer(t, profile));   // virtual tracks only exist transcoded
            }
        } else if (out.isEmpty() && !t.format().rendered()) {
            out.add(nativeOffer(t));   // never leave a playable item without any res
        }
        return out;
    }

    private static Offer nativeOffer(Track t) {
        return new Offer(StreamVariant.NATIVE, MediaTypes.mimeType(t.format()), MediaTypes.protocolInfo(t.format()),
                Math.max(0, t.size()), Math.max(0, t.durationMillis()), t.bitrateKbps() * 125L, t.sampleRate(),
                t.channels());
    }

    private Offer transcodedOffer(Track t, ClientProfile profile) {
        int defaultBitrate = transcoding.defaultBitrateKbps();
        boolean lpcm = profile.transcodeTarget() == TranscodeTarget.LPCM;
        StreamVariant v;
        if (lpcm) {
            v = StreamVariant.LPCM;
        } else {
            int br = profile.bitrateKbps();
            v = StreamVariant.mp3(br == 0 || br == defaultBitrate ? 0 : br);
        }
        StreamVariant resolved = v.resolved(defaultBitrate);
        Optional<TranscodeService.SourceLength> src = transcoding.knownSource(t);
        long size = src.map(s -> TranscodeService.lengthOf(s, resolved)).orElse(0L);
        long duration = src.map(s -> Math.round(s.samples() * 1000.0 / s.sampleRate())).orElse(0L);
        if (duration <= 0) {
            duration = t.format().rendered() && t.renderedMillis() > 0 ? t.renderedMillis() : t.durationMillis();
        }
        int rate = t.format().rendered() ? 48_000 : (src.isPresent() ? src.get().sampleRate() : t.sampleRate());
        if (lpcm) {
            return new Offer(v, LpcmStream.mimeType(rate), MediaTypes.lpcmProtocolInfo(rate), size, duration,
                    rate * 4L, rate, 2);
        }
        return new Offer(v, "audio/mpeg", MediaTypes.transcodedMp3ProtocolInfo(), size, duration,
                resolved.bitrateKbps() * 125L, 48_000, 2);
    }
}
