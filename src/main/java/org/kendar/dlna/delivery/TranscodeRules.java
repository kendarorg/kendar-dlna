package org.kendar.dlna.delivery;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.clients.ClientProfile;

/**
 * Ordered rules that decide how a track is offered, per source format, per device (client profile) and by the
 * sample rate and bitrate of the source: for example "FLAC above 96 kHz goes to the TV as 48 kHz WAV" or "WAV is
 * sent as MP3 to this phone". The first rule whose conditions all hold wins; without a match the client profile
 * alone decides.
 */
public final class TranscodeRules {

    /** No rules. */
    public static final TranscodeRules NONE = new TranscodeRules(List.of());

    /**
     * One rule. A condition left at 0 (or an empty set) is not checked.
     *
     * @param action              the form to offer, or null for "the original only"
     * @param bitrateKbps         MP3 bitrate when the action is MP3 (0 = the default)
     * @param outputMaxRate       sample rate cap of the PCM based forms; null = the server default
     * @param outputBits          16 or 24; null = the server default
     * @param hideOriginal        drop the original from the offers
     */
    public record Rule(Set<Format> formats, Set<String> profiles, int minSampleRate, int maxSampleRate,
            int minBitrateKbps, int maxBitrateKbps, StreamVariant.Kind action, int bitrateKbps, Integer outputMaxRate,
            Integer outputBits, boolean hideOriginal) {

        public boolean nativeOnly() {
            return action == null;
        }

        boolean matches(Track t, ClientProfile profile) {
            if (!formats.isEmpty() && !formats.contains(t.format())) {
                return false;
            }
            if (!profiles.isEmpty() && !profiles.contains(profile.id())) {
                return false;
            }
            int rate = t.sampleRate();
            if (minSampleRate > 0 && rate < minSampleRate) {
                return false;
            }
            if (maxSampleRate > 0 && (rate <= 0 || rate > maxSampleRate)) {
                return false;
            }
            int kbps = t.bitrateKbps();
            if (minBitrateKbps > 0 && kbps < minBitrateKbps) {
                return false;
            }
            return !(maxBitrateKbps > 0 && (kbps <= 0 || kbps > maxBitrateKbps));
        }
    }

    private final List<Rule> rules;

    public TranscodeRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public Optional<Rule> match(Track t, ClientProfile profile) {
        for (Rule r : rules) {
            if (r.matches(t, profile)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
