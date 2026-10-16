package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LRCLIB (lrclib.net): an open, keyless lyrics service with time-synced lyrics. Looks the track up by artist, title,
 * album and duration, then falls back to a search. {@code baseUrl} defaults to {@code https://lrclib.net/api}.
 */
public final class LrclibProvider implements LyricsProvider {

    public static final String ID = "lrclib";
    static final String DEFAULT_URL = "https://lrclib.net/api";
    private static final int MAX_BODY = 2 * 1024 * 1024;

    /** Fetches a URL's body; replaceable in tests. A 404 is reported as {@link NotFound}. */
    public interface Http {
        String get(URI uri) throws IOException;
    }

    /** A 404 from the service: nothing found, not a failure. */
    public static final class NotFound extends IOException {
        private static final long serialVersionUID = 1L;

        public NotFound(String message) {
            super(message);
        }
    }

    private final String baseUrl;
    private final Http http;

    public LrclibProvider(String baseUrl, Duration timeout, String userAgent) {
        this(baseUrl, realHttp(timeout, userAgent));
    }

    public LrclibProvider(String baseUrl, Http http) {
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
        List<Lyrics> out = new ArrayList<>();
        String exact = baseUrl + "/get?track_name=" + enc(q.title()) + "&artist_name=" + enc(q.artist())
                + (q.album() == null || q.album().isBlank() ? "" : "&album_name=" + enc(q.album()))
                + (q.durationSeconds() > 0 ? "&duration=" + q.durationSeconds() : "");
        String body = optional(exact);
        if (body != null) {
            add(out, Json.mapper().readTree(body));
        }
        if (out.isEmpty()) {
            String search = baseUrl + "/search?track_name=" + enc(q.title())
                    + (q.artist() == null || q.artist().isBlank() ? "" : "&artist_name=" + enc(q.artist()));
            String list = optional(search);
            if (list != null) {
                for (JsonNode n : Json.mapper().readTree(list)) {
                    if (out.size() >= 5) {
                        break;
                    }
                    double duration = n.path("duration").asDouble(0);
                    if (q.durationSeconds() > 0 && duration > 0 && Math.abs(duration - q.durationSeconds()) > 5) {
                        continue;   // a different recording
                    }
                    add(out, n);
                }
            }
        }
        out.sort((a, b) -> Boolean.compare(b.synced(), a.synced()));
        return out;
    }

    private static void add(List<Lyrics> out, JsonNode n) {
        String synced = n.path("syncedLyrics").asText("");
        String plain = n.path("plainLyrics").asText("");
        if (!synced.isBlank()) {
            Lyrics l = LrcParser.parse(synced, ID);
            if (!l.isEmpty()) {
                out.add(l);
                return;
            }
        }
        if (!plain.isBlank()) {
            Lyrics l = LrcParser.parse(plain, ID);
            if (!l.isEmpty()) {
                out.add(l);
            }
        }
    }

    /** The body, or null for a 404. */
    private String optional(String url) throws IOException {
        try {
            return http.get(URI.create(url));
        } catch (NotFound e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s.strip(), StandardCharsets.UTF_8);
    }

    private static Http realHttp(Duration timeout, String userAgent) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return uri -> {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", userAgent)
                    .header("Accept", "application/json").GET().build();
            try {
                HttpResponse<java.io.InputStream> r = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() == 404) {
                    r.body().close();
                    throw new NotFound("not found");
                }
                if (r.statusCode() != 200) {
                    r.body().close();
                    throw new IOException("lyrics service answered " + r.statusCode());
                }
                try (java.io.InputStream in = r.body()) {
                    byte[] bytes = in.readNBytes(MAX_BODY + 1);
                    if (bytes.length > MAX_BODY) {
                        throw new IOException("lyrics answer too large");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
