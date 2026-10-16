package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.kendar.dlna.shared.Json;

/**
 * lyrics.ovh: a free, keyless service with plain (not time-synced) lyrics, found by artist and title only. Used as a
 * fallback after LRCLIB. {@code baseUrl} defaults to {@code https://api.lyrics.ovh/v1}.
 */
public final class LyricsOvhProvider implements LyricsProvider {

    public static final String ID = "lyrics.ovh";
    static final String DEFAULT_URL = "https://api.lyrics.ovh/v1";

    private final String baseUrl;
    private final LyricsHttp.Get http;

    public LyricsOvhProvider(String baseUrl, Duration timeout, String userAgent) {
        this(baseUrl, LyricsHttp.real(timeout, userAgent));
    }

    public LyricsOvhProvider(String baseUrl, LyricsHttp.Get http) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl.replaceAll("/+$", "");
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Lyrics> find(Query q) throws IOException {
        if (q.title() == null || q.title().isBlank() || q.artist() == null || q.artist().isBlank()) {
            return List.of();   // the service needs both
        }
        String body;
        try {
            body = http.get(URI.create(baseUrl + "/" + enc(q.artist()) + "/" + enc(q.title())));
        } catch (LyricsHttp.NotFound e) {
            return List.of();
        }
        String text = Json.mapper().readTree(body).path("lyrics").asText("");
        text = text.replace("\r\n", "\n").strip();
        if (text.startsWith("Paroles de la chanson")) {
            int nl = text.indexOf('\n');   // the service sometimes prefixes a French header line
            text = nl < 0 ? "" : text.substring(nl + 1).strip();
        }
        if (text.isBlank()) {
            return List.of();
        }
        Lyrics l = LrcParser.parse(text, ID);
        return l.isEmpty() ? List.of() : List.of(l);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
