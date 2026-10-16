package org.kendar.dlna.lyrics;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The lyrics providers this server knows, by the id used in {@code lyrics.provider} of config.json. */
public final class LyricsProviders {

    /** Ids of the built-in providers ({@code none} switches lookups off). */
    public static final List<String> KNOWN = List.of(LrclibProvider.ID, LrcCxProvider.ID,
            LyricsOvhProvider.ID, NeteaseProvider.ID);

    private LyricsProviders() {
    }

    /** The ids of a {@code lyrics.provider} value: one id or a comma separated list, in order, without repeats. */
    public static List<String> ids(String spec) {
        if (spec == null) {
            return List.of();
        }
        return Arrays.stream(spec.split(",")).map(x -> x.strip().toLowerCase(Locale.ROOT)).filter(x -> !x.isEmpty())
                .distinct().toList();
    }

    /**
     * The provider(s) of a {@code lyrics.provider} value; several ids give a {@link ChainProvider} that asks them in
     * order.
     *
     * @param settings {@code lyrics.providers} of config.json, by provider id
     * @throws IllegalArgumentException for an empty value or an unknown id
     */
    public static LyricsProvider create(String spec, Map<String, Map<String, String>> settings, Duration timeout,
            String userAgent) {
        List<LyricsProvider> list = ids(spec).stream()
                .<LyricsProvider>map(id -> createOne(id, settings == null ? null : settings.get(id), timeout,
                        userAgent))
                .toList();
        if (list.isEmpty()) {
            throw new IllegalArgumentException("No lyrics provider in '" + spec + "'");
        }
        return list.size() == 1 ? list.get(0) : new ChainProvider(list);
    }

    /**
     * @param settings the provider's own settings ({@code lyrics.providers.<id>} of config.json)
     * @throws IllegalArgumentException for an unknown id
     */
    public static LyricsProvider createOne(String id, Map<String, String> settings, Duration timeout,
            String userAgent) {
        String baseUrl = settings == null ? null : settings.get("baseUrl");
        if (LrclibProvider.ID.equals(id)) {
            return new LrclibProvider(baseUrl, timeout, userAgent);
        }
        if (LrcCxProvider.ID.equals(id)) {
            return new LrcCxProvider(baseUrl, timeout, userAgent);
        }
        if (NeteaseProvider.ID.equals(id)) {
            return new NeteaseProvider(baseUrl, timeout, userAgent);
        }
        if (LyricsOvhProvider.ID.equals(id)) {
            return new LyricsOvhProvider(baseUrl, timeout, userAgent);
        }
        throw new IllegalArgumentException("Unknown lyrics provider '" + id + "' (known: none, " + KNOWN + ")");
    }
}
