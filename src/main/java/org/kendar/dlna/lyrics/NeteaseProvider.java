package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * NetEase Cloud Music: a very large catalogue with time-synced lyrics, through its public web endpoints (no key).
 * An unofficial interface, so it is not in the default list: switch it on with {@code lyrics.provider} (for example
 * {@code "lrclib,lrc.cx,netease"}). Searches for artist and title, keeps the hits whose title matches and whose length
 * is within 5 seconds of the file, and takes the lyrics of the first one that has any. {@code baseUrl} defaults to
 * {@code https://music.163.com/api}.
 */
public final class NeteaseProvider implements LyricsProvider {

    public static final String ID = "netease";
    static final String DEFAULT_URL = "https://music.163.com/api";
    private static final int MAX_HITS = 3;

    private final String baseUrl;
    private final LyricsHttp.Get http;

    public NeteaseProvider(String baseUrl, Duration timeout, String userAgent) {
        this(baseUrl, LyricsHttp.real(timeout, userAgent));
    }

    public NeteaseProvider(String baseUrl, LyricsHttp.Get http) {
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
        String text = ((q.artist() == null ? "" : q.artist().strip() + " ") + q.title().strip());
        String search;
        try {
            search = http.get(URI.create(baseUrl + "/search/get?type=1&limit=10&s="
                    + URLEncoder.encode(text, StandardCharsets.UTF_8)));
        } catch (LyricsHttp.NotFound e) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        String wanted = normalize(q.title());
        for (JsonNode song : Json.mapper().readTree(search).path("result").path("songs")) {
            if (ids.size() >= MAX_HITS) {
                break;
            }
            double seconds = song.path("duration").asDouble(0) / 1000;
            boolean sameLength = q.durationSeconds() <= 0 || seconds <= 0 || Math.abs(seconds - q.durationSeconds()) <= 5;
            String name = normalize(song.path("name").asText(""));
            if (sameLength && (name.contains(wanted) || wanted.contains(name)) && !name.isEmpty()) {
                ids.add(song.path("id").asLong());
            }
        }
        List<Lyrics> out = new ArrayList<>();
        for (long id : ids) {
            String body;
            try {
                body = http.get(URI.create(baseUrl + "/song/lyric?lv=1&kv=1&tv=-1&id=" + id));
            } catch (LyricsHttp.NotFound e) {
                continue;
            }
            String lrc = Json.mapper().readTree(body).path("lrc").path("lyric").asText("");
            if (!lrc.isBlank()) {
                Lyrics l = LrcParser.parse(lrc, ID);
                if (!l.isEmpty()) {
                    out.add(l);
                    break;
                }
            }
        }
        return out;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
