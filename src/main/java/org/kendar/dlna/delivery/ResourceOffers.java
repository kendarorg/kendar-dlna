package org.kendar.dlna.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.clients.ClientProfile;
import org.kendar.dlna.clients.MediaFormat;
import org.kendar.dlna.clients.ResKind;

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
    private final TranscodeRules rules;

    /** @param transcoding null = no transcoding: only native offers (MIDI has none) */
    public ResourceOffers(TranscodeService transcoding) {
        this(transcoding, TranscodeRules.NONE);
    }

    /** @param rules decide per track and device how it is offered; checked before the client profile's plan */
    public ResourceOffers(TranscodeService transcoding, TranscodeRules rules) {
        this.transcoding = transcoding;
        this.rules = rules == null ? TranscodeRules.NONE : rules;
    }

    public static ResourceOffers nativeOnly() {
        return new ResourceOffers(null);
    }

    public List<Offer> offers(Track t, ClientProfile profile) {
        MediaFormat mf = MediaFormat.valueOf(t.format().name());
        boolean canTranscode = transcoding != null && transcoding.canTranscode(t.format());
        List<ResKind> plan = new ArrayList<>(profile.resPlan(mf));
        StreamVariant ruled = null;
        Optional<TranscodeRules.Rule> rule = transcoding == null ? Optional.empty() : rules.match(t, profile);
        if (rule.isPresent()) {
            TranscodeRules.Rule r = rule.get();
            if (r.nativeOnly()) {
                plan = new ArrayList<>(List.of(ResKind.NATIVE));
            } else if (canTranscode) {
                ruled = ruledVariant(t, r);
                if (!plan.contains(ResKind.TRANSCODED)) {
                    plan.add(ResKind.TRANSCODED);
                }
                if (r.hideOriginal()) {
                    plan.remove(ResKind.NATIVE);
                }
            }
        }
        List<Offer> out = new ArrayList<>(2);
        for (ResKind kind : plan) {
            if (kind == ResKind.NATIVE) {
                if (!t.format().rendered() && !t.isSegment()) {
                    out.add(nativeOffer(t));
                } else if (sliceable(t)) {
                    out.add(sliceOffer(t));
                }
            } else if (canTranscode) {
                out.add(ruled != null ? transcodedOffer(t, ruled) : transcodedOffer(t, profile));
            }
        }
        if (out.isEmpty() && t.isSegment()) {
            if (canTranscode) {
                out.add(ruled != null ? transcodedOffer(t, ruled) : transcodedOffer(t, profile));   // virtual tracks only exist transcoded
            }
        } else if (out.isEmpty() && !t.format().rendered()) {
            out.add(nativeOffer(t));   // never leave a playable item without any res
        }
        return out;
    }

    private boolean sliceable(Track t) {
        return transcoding != null && transcoding.canSlice(t);
    }

    private static Offer nativeOffer(Track t) {
        return new Offer(StreamVariant.NATIVE, MediaTypes.mimeType(t.format()), MediaTypes.protocolInfo(t.format()),
                Math.max(0, t.size()), Math.max(0, t.durationMillis()), t.bitrateKbps() * 125L, t.sampleRate(),
                t.channels());
    }

    /** A virtual track served as a cut of its file: the length is only known once the file header was read. */
    private static Offer sliceOffer(Track t) {
        long duration = t.sampleRate() > 0 && t.sampleCount() > 0
                ? Math.round(t.sampleCount() * 1000.0 / t.sampleRate()) : Math.max(0, t.durationMillis());
        return new Offer(StreamVariant.NATIVE, MediaTypes.mimeType(t.format()), MediaTypes.protocolInfo(t.format()), 0,
                duration, t.bitrateKbps() * 125L, t.sampleRate(), t.channels());
    }

    /** The form a rule asks for, with the server's bit depth and rate cap where the rule says nothing. */
    private StreamVariant ruledVariant(Track t, TranscodeRules.Rule r) {
        StreamVariant v;
        if (r.action() == StreamVariant.Kind.MP3) {
            int br = r.bitrateKbps();
            v = StreamVariant.mp3(br == 0 || br == transcoding.defaultBitrateKbps() ? 0 : br);
        } else {
            v = StreamVariant.pcm(r.action(), r.outputBits() != null ? r.outputBits() : transcoding.defaultBits(),
                    r.outputMaxRate() != null ? r.outputMaxRate() : transcoding.defaultMaxRate());
        }
        return adapt(t, v);
    }

    /** The transcoded form a profile gets of a track: its target, with the server's bit depth and rate cap. */
    StreamVariant variantFor(Track t, ClientProfile profile) {
        int defaultBitrate = transcoding.defaultBitrateKbps();
        StreamVariant.Kind kind = switch (profile.transcodeTarget()) {
            case MP3 -> StreamVariant.Kind.MP3;
            case LPCM -> StreamVariant.Kind.LPCM;
            case WAV -> StreamVariant.Kind.WAV;
            case FLAC -> StreamVariant.Kind.FLAC;
            case ALAC -> StreamVariant.Kind.ALAC;
        };
        StreamVariant v;
        if (kind == StreamVariant.Kind.MP3) {
            int br = profile.bitrateKbps();
            v = StreamVariant.mp3(br == 0 || br == defaultBitrate ? 0 : br);
        } else {
            int cap = transcoding.defaultMaxRate();
            if (profile.maxSampleRate() > 0) {
                cap = cap > 0 ? Math.min(cap, profile.maxSampleRate()) : profile.maxSampleRate();
            }
            v = StreamVariant.pcm(kind, transcoding.defaultBits(), cap);
        }
        return adapt(t, v);
    }

    /** Makes a variant producible: DSD as DoP needs 24-bit PCM, FLAC / ALAC need ffmpeg (else MP3). */
    private StreamVariant adapt(Track t, StreamVariant v) {
        if (t.format() == Format.DSD && org.kendar.dlna.transcoding.Ffmpeg.dsdDop() && (!v.kind().pcm() || v.bits() < 24)) {
            StreamVariant.Kind k = v.kind().pcm() ? v.kind() : StreamVariant.Kind.LPCM;
            v = StreamVariant.pcm(k, 24, v.maxRate());
        }
        if (!transcoding.canProduce(t.format(), v)) {
            v = StreamVariant.mp3(0);
        }
        return v;
    }

    Offer transcodedOffer(Track t, ClientProfile profile) {
        return transcodedOffer(t, variantFor(t, profile));
    }

    Offer transcodedOffer(Track t, StreamVariant v) {
        int defaultBitrate = transcoding.defaultBitrateKbps();
        StreamVariant resolved = v.resolved(defaultBitrate);
        Optional<TranscodeService.SourceLength> src = transcoding.knownSource(t);
        long size = src.map(s -> TranscodeService.lengthOf(s, resolved)).filter(l -> l > 0).orElse(0L);
        long duration = src.map(s -> Math.round(s.samples() * 1000.0 / s.sampleRate())).orElse(0L);
        if (duration <= 0) {
            duration = t.format().rendered() && t.renderedMillis() > 0 ? t.renderedMillis() : t.durationMillis();
        }
        int rate = t.format().rendered() ? 48_000 : (src.isPresent() ? src.get().sampleRate() : t.sampleRate());
        int outRate = TranscodeService.outputRate(rate, resolved);
        long bytesPerSecond = switch (resolved.kind()) {
            case MP3 -> resolved.bitrateKbps() * 125L;
            case LPCM, WAV -> outRate * (resolved.bits() == 24 ? 6L : 4L);
            default -> 0L;
        };
        return new Offer(v, MediaTypes.mimeType(resolved, outRate), MediaTypes.protocolInfo(resolved, outRate), size,
                duration, bytesPerSecond, outRate, 2);
    }
}
