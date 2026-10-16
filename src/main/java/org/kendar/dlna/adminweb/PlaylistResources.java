package org.kendar.dlna.adminweb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.ResolvedPlaylist;
import org.kendar.dlna.catalog.Snapshot;

import com.fasterxml.jackson.databind.JsonNode;

/** REST resource: {@code /playlists}. */
final class PlaylistResources {

    private final AdminServices svc;

    PlaylistResources(AdminServices svc) {
        this.svc = svc;
    }

    ApiResponse handle(ApiRequest req, List<String> seg) {
        if (seg.isEmpty()) {
            return switch (req.method()) {
                case "GET" -> list();
                case "POST" -> create(req);
                default -> null;
            };
        }
        String key = seg.get(0);
        if (seg.size() == 1) {
            return switch (req.method()) {
                case "GET" -> detail(key);
                case "PUT" -> update(key, req);
                case "DELETE" -> {
                    svc.playlists().delete(key);
                    yield ApiResponse.noContent();
                }
                default -> null;
            };
        }
        if (seg.size() == 2 && req.method().equals("POST")) {
            return switch (seg.get(1)) {
                case "songs" -> add(key, req);
                case "fix-path" -> fix(key, req);
                default -> null;
            };
        }
        return null;
    }

    private ApiResponse list() {
        List<Object> out = new ArrayList<>();
        for (ResolvedPlaylist p : svc.catalog().current().playlists()) {
            out.add(Dto.obj("key", p.playlist().key(), "name", p.name(), "system", p.playlist().system(), "count", p.entries().size(),
                    "broken", p.brokenCount()));
        }
        return ApiResponse.ok(Dto.obj("playlists", out));
    }

    private ApiResponse create(ApiRequest req) {
        JsonNode body = req.json();
        Playlist p = svc.playlists().create(body.path("name").asText(null));
        return ApiResponse.json(201, detailBody(p.key()));
    }

    private ApiResponse detail(String key) {
        return ApiResponse.ok(detailBody(key));
    }

    private Map<String, Object> detailBody(String key) {
        Snapshot snap = svc.catalog().current();
        ResolvedPlaylist p = snap.playlists().stream().filter(x -> x.playlist().key().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Unknown playlist: " + key));
        List<Object> entries = new ArrayList<>();
        int i = 0;
        for (ResolvedPlaylist.Entry e : p.entries()) {
            Map<String, Object> m = Dto.obj("index", i++, "path", e.path(), "status", e.status().name().toLowerCase());
            if (e.track() != null) {
                m.put("track", Dto.track(e.track(), snap.folder(e.track().parentRef()).map(Folder::settings)
                        .orElse(DirectorySettings.DEFAULT)));
            }
            entries.add(m);
        }
        return Dto.obj("key", p.playlist().key(), "name", p.name(), "system", p.playlist().system(), "entries", entries,
                "broken", p.brokenCount());
    }

    /** Body: {@code name} (rename) and/or {@code songs} (the complete new order). */
    private ApiResponse update(String key, ApiRequest req) {
        JsonNode body = req.json();
        if (!body.has("name") && !body.has("songs")) {
            throw new ApiException(400, "bad_request", "name and/or songs is required");
        }
        if (body.has("songs")) {
            svc.playlists().replaceSongs(key, strings(body.get("songs"), "songs"));
        }
        if (body.has("name")) {
            svc.playlists().rename(key, body.get("name").asText(null));
        }
        return ApiResponse.ok(detailBody(key));
    }

    private ApiResponse add(String key, ApiRequest req) {
        svc.playlists().add(key, strings(req.json().get("paths"), "paths"));
        return ApiResponse.ok(detailBody(key));
    }

    private ApiResponse fix(String key, ApiRequest req) {
        JsonNode body = req.json();
        if (!body.path("index").isInt()) {
            throw new ApiException(400, "bad_request", "index must be an integer");
        }
        svc.playlists().fixPath(key, body.get("index").asInt(), body.path("path").asText(null));
        return ApiResponse.ok(detailBody(key));
    }

    private static List<String> strings(JsonNode n, String field) {
        if (n == null || !n.isArray()) {
            throw new ApiException(400, "bad_request", field + " must be an array of paths");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : n) {
            if (!e.isTextual()) {
                throw new ApiException(400, "bad_request", field + " must contain strings only");
            }
            out.add(e.asText());
        }
        return out;
    }
}
