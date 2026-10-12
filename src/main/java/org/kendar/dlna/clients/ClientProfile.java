package org.kendar.dlna.clients;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A renderer profile: how to present audio to a matching client.
 *
 * @param id              unique, {@code [a-z0-9][a-z0-9._-]*}
 * @param match           when the profile applies (ignored for the default profile)
 * @param nativeFormats   formats the renderer plays as they are (MIDI is never native)
 * @param resOrder        order when original and transcoded are both offered
 * @param transcodeTarget MP3 or LPCM
 * @param bitrateKbps     MP3 bitrate; 0 = the server's configured default
 * @param quirks          behaviour switches
 */
public record ClientProfile(String id, String description, ProfileMatch match, Set<MediaFormat> nativeFormats,
        ResOrder resOrder, TranscodeTarget transcodeTarget, int bitrateKbps, Set<Mp3Quirk> quirks) {

    public static final String DEFAULT_ID = "default";

    /** MP3 bitrates the encoder accepts (MPEG-1 Layer III). */
    public static final List<Integer> BITRATES = List.of(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256,
            320);

    public ClientProfile {
        description = description == null ? "" : description;
        match = match == null ? new ProfileMatch(null, null, null) : match;
        nativeFormats = nativeFormats == null || nativeFormats.isEmpty() ? EnumSet.noneOf(MediaFormat.class)
                : EnumSet.copyOf(nativeFormats);
        nativeFormats.remove(MediaFormat.MIDI);   // MIDI and ABC are always rendered
        nativeFormats.remove(MediaFormat.ABC);
        nativeFormats = Set.copyOf(nativeFormats);
        resOrder = resOrder == null ? ResOrder.TRANSCODED_FIRST : resOrder;
        transcodeTarget = transcodeTarget == null ? TranscodeTarget.MP3 : transcodeTarget;
        quirks = quirks == null || quirks.isEmpty() ? Set.of() : Set.copyOf(quirks);
    }

    /** MP3 native, everything else transcoded to MP3 with the original offered second (plan section 11). */
    public static ClientProfile builtinDefault() {
        return new ClientProfile(DEFAULT_ID, "Default: MP3 native, everything else transcoded", null,
                Set.of(MediaFormat.MP3), ResOrder.TRANSCODED_FIRST, TranscodeTarget.MP3, 0, Set.of());
    }

    /**
     * The {@code res} elements to offer for a source format, in order; never empty.
     * <ul>
     *   <li>MIDI: only the transcoded form;</li>
     *   <li>native format: the original only (no extra transcode);</li>
     *   <li>otherwise: transcoded, then the original (or the reverse order), unless the original is hidden.</li>
     * </ul>
     */
    public List<ResKind> resPlan(MediaFormat format) {
        List<ResKind> plan = new ArrayList<>(2);
        if (format == MediaFormat.MIDI || format == MediaFormat.ABC) {
            plan.add(ResKind.TRANSCODED);
        } else if (nativeFormats.contains(format)
                && !(format == MediaFormat.MP3 && quirks.contains(Mp3Quirk.AVOID_NATIVE_MP3))) {
            plan.add(ResKind.NATIVE);
        } else if (quirks.contains(Mp3Quirk.HIDE_UNPLAYABLE_ORIGINAL)) {
            plan.add(ResKind.TRANSCODED);
        } else if (resOrder == ResOrder.NATIVE_FIRST) {
            plan.add(ResKind.NATIVE);
            plan.add(ResKind.TRANSCODED);
        } else {
            plan.add(ResKind.TRANSCODED);
            plan.add(ResKind.NATIVE);
        }
        if (quirks.contains(Mp3Quirk.SINGLE_RES) && plan.size() > 1) {
            return List.of(plan.get(0));
        }
        return List.copyOf(plan);
    }
}
