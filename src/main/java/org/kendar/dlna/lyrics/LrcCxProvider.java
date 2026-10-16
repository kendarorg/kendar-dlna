package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * lrc.cx: a free, keyless service that answers with the {@code .lrc} text (time-synced when it can) for a title and
 * an artist. {@code baseUrl} defaults to {@code https://api.lrc.cx}. A community service: it may change or go away.
 */
public final class LrcCxProvider implements LyricsProvider {

    public static final String ID = "lrc.cx";
    static final String DEFAULT_URL = "https://api.lrc.cx";

    private final String baseUrl;
    private final LyricsHttp.Get http;

    public LrcCxProvider(String baseUrl, Duration timeout, String userAgent) {
        this(baseUrl, LyricsHttp.real(timeout, userAgent));
    }

    public LrcCxProvider(String baseUrl, LyricsHttp.Get http) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_URL : baseUrl.replaceAll("/+$", "");
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Lyrics> find(Query q) throws IOException {
        if (q.title() == null || q.title().isBlank()) {
            return List.of();
        }
        String url = baseUrl + "/lyrics?title=" + enc(q.title())
                + (q.artist() == null || q.artist().isBlank() ? "" : "&artist=" + enc(q.artist()))
                + (q.album() == null || q.album().isBlank() ? "" : "&album=" + enc(q.album()));
        String body;
        try {
            body = http.get(URI.create(url));
        } catch (LyricsHttp.NotFound e) {
            return List.of();
        }
        if (body.isBlank()) {
            return List.of();
        }
        Lyrics l = LrcParser.parse(body, ID);
        return l.isEmpty() ? List.of() : List.of(l);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.strip(), StandardCharsets.UTF_8);
    }
}
