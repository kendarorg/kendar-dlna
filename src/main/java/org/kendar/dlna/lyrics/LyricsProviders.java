package org.kendar.dlna.lyrics;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** The lyrics providers this server knows, by the id used in {@code lyrics.provider} of config.json. */
public final class LyricsProviders {

    /** Ids of the built-in providers ({@code none} switches lookups off). */
    public static final List<String> KNOWN = List.of(LrclibProvider.ID);

    private LyricsProviders() {
    }

    /**
     * @param settings the provider's own settings ({@code lyrics.providers.<id>} of config.json)
     * @throws IllegalArgumentException for an unknown id
     */
    public static LyricsProvider create(String id, Map<String, String> settings, Duration timeout, String userAgent) {
        if (LrclibProvider.ID.equals(id)) {
            return new LrclibProvider(settings == null ? null : settings.get("baseUrl"), timeout, userAgent);
        }
        throw new IllegalArgumentException("Unknown lyrics provider '" + id + "' (known: none, " + KNOWN + ")");
    }
}
