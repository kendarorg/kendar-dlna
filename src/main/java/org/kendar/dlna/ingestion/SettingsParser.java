package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.SortMode;
import org.kendar.dlna.catalog.ViewKind;
import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parses a directory's {@code settings.json}. Never throws: any problem yields the default settings plus an
 * error message (browsing must not fail).
 */
public final class SettingsParser {

    /** {@code error} is null on success (or when there is no file). */
    public record Result(DirectorySettings settings, String error) {
    }

    private SettingsParser() {
    }

    public static Result parseFile(Path file) {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return failure("cannot read file: " + e.getMessage());
        }
        return parse(json);
    }

    public static Result parse(String json) {
        try {
            JsonNode n = Json.mapper().readTree(json);
            if (n == null || n.isMissingNode() || n.isNull()) {
                return new Result(DirectorySettings.DEFAULT, null);   // empty file
            }
            if (!n.isObject()) {
                return failure("settings must be a JSON object");
            }
            SortMode sort = SortMode.FILENAME;
            if (n.has("sort")) {
                sort = SortMode.parse(n.get("sort").asText()).orElse(null);
                if (sort == null || !n.get("sort").isTextual()) {
                    return failure("invalid sort: " + n.get("sort") + " (expected filename|title|track|mtime|explicit)");
                }
            }
            List<String> order = new ArrayList<>();
            if (n.has("order") && !n.get("order").isNull()) {
                if (!n.get("order").isArray()) {
                    return failure("order must be an array of names");
                }
                for (JsonNode e : n.get("order")) {
                    if (!e.isTextual()) {
                        return failure("order entries must be strings");
                    }
                    order.add(e.asText());
                }
            }
            AlbumInfo album = AlbumInfo.NONE;
            if (n.has("album") && !n.get("album").isNull()) {
                JsonNode a = n.get("album");
                if (!a.isObject()) {
                    return failure("album must be an object");
                }
                album = new AlbumInfo(text(a, "title"), text(a, "albumArtist"), integer(a, "year"),
                        text(a, "genre"), integer(a, "discNumber"), integer(a, "discTotal"));
            }
            int random = 0;
            if (n.has("random") && !n.get("random").isNull()) {
                if (!n.get("random").isInt() || n.get("random").asInt() < 0) {
                    return failure("random must be a non-negative integer");
                }
                random = n.get("random").asInt();
            }
            int recent = 0;
            if (n.has("recent") && !n.get("recent").isNull()) {
                if (!n.get("recent").isInt() || n.get("recent").asInt() < 0) {
                    return failure("recent must be a non-negative integer");
                }
                recent = n.get("recent").asInt();
            }
            List<ViewKind> views = new ArrayList<>();
            if (n.has("views") && !n.get("views").isNull()) {
                if (!n.get("views").isArray()) {
                    return failure("views must be an array");
                }
                for (JsonNode v : n.get("views")) {
                    ViewKind k = v.isTextual() ? ViewKind.parse(v.asText()).orElse(null) : null;
                    if (k == null) {
                        return failure("invalid view: " + v + " (expected album|artist|genre|title|composer)");
                    }
                    if (!views.contains(k)) {
                        views.add(k);
                    }
                }
            }
            String displayName = null;
            if (n.has("displayName") && !n.get("displayName").isNull()) {
                if (!n.get("displayName").isTextual()) {
                    return failure("displayName must be a string");
                }
                displayName = n.get("displayName").asText();
            }
            boolean keepWhole = false;
            if (n.has("keepWholeFiles") && !n.get("keepWholeFiles").isNull()) {
                if (!n.get("keepWholeFiles").isBoolean()) {
                    return failure("keepWholeFiles must be true or false");
                }
                keepWhole = n.get("keepWholeFiles").asBoolean();
            }
            Integer bucket = null;
            if (n.has("bucketThreshold") && !n.get("bucketThreshold").isNull()) {
                if (!n.get("bucketThreshold").isInt() || n.get("bucketThreshold").asInt() < 1) {
                    return failure("bucketThreshold must be an integer of at least 1");
                }
                bucket = n.get("bucketThreshold").asInt();
            }
            return new Result(new DirectorySettings(sort, order, album, random, views, recent, displayName,
                    keepWhole, bucket), null);
        } catch (IOException | RuntimeException e) {
            return failure("malformed JSON: " + e.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static Result failure(String message) {
        return new Result(DirectorySettings.DEFAULT, message);
    }

    private static String text(JsonNode o, String field) {
        JsonNode v = o.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer integer(JsonNode o, String field) {
        JsonNode v = o.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isInt()) {
            return v.asInt();
        }
        if (v.isTextual()) {
            try {
                return Integer.valueOf(v.asText().strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("album." + field + " is not a number: " + v.asText());
            }
        }
        throw new IllegalArgumentException("album." + field + " is not a number");
    }
}
