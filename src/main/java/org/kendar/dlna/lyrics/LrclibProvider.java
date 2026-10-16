package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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

    private final String baseUrl;
    private final LyricsHttp.Get http;

    public LrclibProvider(String baseUrl, Duration timeout, String userAgent) {
        this(baseUrl, LyricsHttp.real(timeout, userAgent));
    }

    public LrclibProvider(String baseUrl, LyricsHttp.Get http) {
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
        // three tries: the exact lookup, a search by track and artist, then a free text search (q=) that also finds
        // titles tagged a little differently. A try that fails (the service is often busy) does not stop the next
        // one; the failure is reported only when nothing was found at all.
        IOException failure = null;
        String named = baseUrl + "/search?track_name=" + enc(q.title())
                + (q.artist() == null || q.artist().isBlank() ? "" : "&artist_name=" + enc(q.artist()));
        String free = baseUrl + "/search?q=" + enc((q.artist() == null ? "" : q.artist().strip() + " ") + q.title());
        for (String url : List.of(exact, named, free)) {
            if (!out.isEmpty()) {
                break;
            }
            try {
                String body = optional(url);
                if (body == null) {
                    continue;
                }
                JsonNode json = Json.mapper().readTree(body);
                if (url == exact) {
                    add(out, json);
                } else {
                    for (JsonNode n : json) {
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
            } catch (IOException e) {
                failure = failure == null ? e : failure;
            }
        }
        if (out.isEmpty() && failure != null) {
            throw failure;
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
        } catch (LyricsHttp.NotFound e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s.strip(), StandardCharsets.UTF_8);
    }
}
