package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parses a playlist file: {@code {"name": "...", "songs": ["root/path/a.mp3", ...]}}. Never throws: a broken
 * file yields no playlist and an error message.
 */
public final class PlaylistParser {

    /** Exactly one of {@code playlist} and {@code error} is non-null. */
    public record Result(Playlist playlist, String error) {
    }

    private PlaylistParser() {
    }

    public static Result parseFile(Path file) {
        String key = keyOf(file.getFileName().toString());
        try {
            return parse(key, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return new Result(null, "cannot read file: " + e.getMessage());
        }
    }

    /** The playlist key: the file name without its {@code .json} extension. */
    public static String keyOf(String fileName) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    public static Result parse(String key, String json) {
        try {
            JsonNode n = Json.mapper().readTree(json);
            if (n == null || !n.isObject()) {
                return new Result(null, "playlist must be a JSON object");
            }
            String name = n.hasNonNull("name") ? n.get("name").asText() : null;
            List<String> songs = new ArrayList<>();
            if (n.hasNonNull("songs")) {
                if (!n.get("songs").isArray()) {
                    return new Result(null, "songs must be an array of paths");
                }
                for (JsonNode s : n.get("songs")) {
                    if (!s.isTextual() || s.asText().isBlank()) {
                        return new Result(null, "songs entries must be non-empty strings");
                    }
                    songs.add(s.asText().strip());
                }
            }
            return new Result(new Playlist(key, name, songs), null);
        } catch (IOException | RuntimeException e) {
            return new Result(null, "malformed JSON: " + e.getMessage().lines().findFirst().orElse(""));
        }
    }
}
