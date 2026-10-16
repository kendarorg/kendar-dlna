package org.kendar.dlna.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;

/**
 * The values of a named tag for a track, as used by the navigations: file tags, virtual tags computed from the
 * track (nothing is stored) and the configured extra tags. A track can have several values for a tag (multi-value
 * artist, genre, composer); no value means "Unknown <tag>".
 */
public final class TagValues {

    /** The digital / analog split of the {@code type} virtual tag: synthesized (MIDI, ABC) versus recorded audio. */
    public static final String DIGITAL = "digital";
    public static final String ANALOG = "analog";

    private TagValues() {
    }

    /** The values of {@code tag} (canonical spelling) for the track; never null, possibly empty. */
    public static List<String> of(EffectiveTrack et, String tag) {
        Track t = et.track();
        return switch (tag) {
            case "title" -> single(t.tags().title());
            case "artist" -> clean(!et.artists().isEmpty() ? et.artists() : et.albumArtists());
            case "albumArtist" -> clean(!et.albumArtists().isEmpty() ? et.albumArtists() : et.artists());
            case "album" -> single(et.album());
            case "genre" -> clean(et.genres());
            case "year" -> et.year() == null || et.year() <= 0 ? List.of() : List.of(String.valueOf(et.year()));
            case "composer" -> clean(et.composers());
            case "type" -> List.of(type(t.format()));
            case "format" -> List.of(t.format().name());
            case "bitrate" -> List.of(bitrateBand(t));
            default -> extra(t, tag);
        };
    }

    /** {@code digital} for the formats that only exist rendered (MIDI, ABC), {@code analog} for recorded audio. */
    public static String type(Format f) {
        return f.rendered() ? DIGITAL : ANALOG;
    }

    /** A coarse bitrate band; synthesized files have none, lossless formats are told by format. */
    public static String bitrateBand(Track t) {
        Format f = t.format();
        if (f.rendered()) {
            return "n/a";
        }
        if (lossless(f)) {
            return "lossless";
        }
        int k = t.bitrateKbps();
        if (k <= 0) {
            return "unknown";
        }
        if (k < 128) {
            return "under 128 kbps";
        }
        if (k < 160) {
            return "128 kbps";
        }
        if (k <= 224) {
            return "160-224 kbps";
        }
        if (k <= 288) {
            return "256 kbps";
        }
        return "320 kbps";
    }

    static boolean lossless(Format f) {
        return switch (f) {
            case FLAC, WAV, AIFF, ALAC, APE, DSD, WAVPACK -> true;
            default -> false;
        };
    }

    private static List<String> extra(Track t, String tag) {
        return clean(t.extra(tag.toLowerCase(Locale.ROOT)));
    }

    private static List<String> single(String v) {
        return v == null || v.isBlank() ? List.of() : List.of(v.strip());
    }

    private static List<String> clean(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.strip());
            }
        }
        return out;
    }
}
