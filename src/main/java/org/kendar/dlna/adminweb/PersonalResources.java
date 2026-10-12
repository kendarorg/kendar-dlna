package org.kendar.dlna.adminweb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.kendar.dlna.identity.User;
import org.kendar.dlna.personal.PersonalException;
import org.kendar.dlna.personal.PersonalService;
import org.kendar.dlna.personal.Precondition;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * HTTP adapter of the Personal context: {@code /me/playlists|favorites|queue|history}, {@code /shared/...} and the
 * moderation endpoints {@code /admin/shared-playlists}. Single resources carry their {@code version} as ETag;
 * writes honor {@code If-Match} (412) or a {@code version} in the body (409).
 */
final class PersonalResources {

    private final PersonalService personal;

    PersonalResources(PersonalService personal) {
        this.personal = personal;
    }

    PersonalService service() {
        return personal;
    }

    /** True for the {@code /me} sub-resources owned by this adapter. */
    static boolean isMeResource(String first) {
        return first.equals("playlists") || first.equals("favorites") || first.equals("queue")
                || first.equals("history");
    }

    ApiResponse me(ApiRequest req, List<String> seg, User user) {
        try {
            return switch (seg.get(0)) {
                case "playlists" -> playlists(req, seg.subList(1, seg.size()), user);
                case "favorites" -> favorites(req, seg.size() == 1, user);
                case "queue" -> seg.size() == 1 ? queue(req, user) : null;
                case "history" -> history(req, seg.subList(1, seg.size()), user);
                default -> null;
            };
        } catch (PersonalException e) {
            throw convert(e);
        }
    }

    ApiResponse shared(ApiRequest req, List<String> seg, User user) {
        try {
            int n = seg.size();
            if (n == 0) {
                return null;
            }
            String m = req.method();
            return switch (seg.get(0)) {
                case "playlists" -> {
                    if (n == 1 && req.isRead()) {
                        yield ApiResponse.ok(personal.sharedPlaylists(user));
                    }
                    if (n == 3 && req.isRead()) {
                        yield etagged(req, personal.sharedPlaylist(user, seg.get(1), seg.get(2), req.query()));
                    }
                    if (n == 4 && m.equals("POST") && seg.get(3).equals("copy")) {
                        yield ApiResponse.json(201, personal.copyPlaylist(user, seg.get(1), seg.get(2), name(req)));
                    }
                    yield null;
                }
                case "system" -> {
                    if (n == 1 && req.isRead()) {
                        yield ApiResponse.ok(personal.systemPlaylists(user));
                    }
                    if (n == 2 && req.isRead()) {
                        yield ApiResponse.ok(personal.systemPlaylist(user, seg.get(1), req.query()));
                    }
                    if (n == 3 && m.equals("POST") && seg.get(2).equals("copy")) {
                        yield ApiResponse.json(201, personal.copySystem(user, seg.get(1), name(req)));
                    }
                    yield null;
                }
                default -> null;
            };
        } catch (PersonalException e) {
            throw convert(e);
        }
    }

    /** {@code /admin/shared-playlists}: moderation of shared playlists only. */
    ApiResponse moderation(ApiRequest req, List<String> seg) {
        try {
            if (seg.isEmpty() && req.isRead()) {
                return ApiResponse.ok(personal.adminSharedPlaylists());
            }
            if (seg.size() == 2 && req.method().equals("DELETE")) {
                personal.adminDelete(seg.get(0), seg.get(1));
                return ApiResponse.noContent();
            }
            if (seg.size() == 3 && req.method().equals("POST") && seg.get(2).equals("unshare")) {
                personal.adminUnshare(seg.get(0), seg.get(1));
                return ApiResponse.noContent();
            }
            return null;
        } catch (PersonalException e) {
            throw convert(e);
        }
    }

    // ---- /me/playlists ----

    private ApiResponse playlists(ApiRequest req, List<String> seg, User user) {
        String m = req.method();
        int n = seg.size();
        if (n == 0) {
            return switch (m) {
                case "GET" -> ApiResponse.ok(personal.playlists(user));
                case "POST" -> {
                    JsonNode b = req.json();
                    yield ApiResponse.json(201, personal.createPlaylist(user, text(b, "name"), text(b, "description"),
                            b.has("tracks") ? strings(b, "tracks") : null));
                }
                default -> null;
            };
        }
        if (n == 1 && seg.get(0).equals("copy") && m.equals("POST")) {
            JsonNode b = req.json();
            String source = b.path("source").asText("");
            return ApiResponse.json(201, switch (source) {
                case "shared" -> personal.copyPlaylist(user, b.path("owner").asText(""), b.path("id").asText(""),
                        text(b, "name"));
                case "system" -> personal.copySystem(user, b.path("key").asText(""), text(b, "name"));
                default -> throw ApiException.badRequest("source must be shared or system");
            });
        }
        String id = seg.get(0);
        if (n == 1) {
            return switch (m) {
                case "GET" -> etagged(req, personal.playlist(user, id, req.query()));
                case "PUT" -> {
                    JsonNode b = req.json();
                    yield etag(ApiResponse.ok(personal.updatePlaylist(user, id, pre(req, b), text(b, "name"),
                            text(b, "description"), b.has("tracks") ? strings(b, "tracks") : null)));
                }
                case "DELETE" -> {
                    personal.deletePlaylist(user, id, pre(req, null));
                    yield ApiResponse.noContent();
                }
                default -> null;
            };
        }
        if (n != 2) {
            return null;
        }
        String action = seg.get(1);
        return switch (action) {
            case "tracks" -> switch (m) {
                case "POST" -> {
                    JsonNode b = req.json();
                    yield etag(ApiResponse.ok(personal.addTracks(user, id, pre(req, b), strings(b, "paths"),
                            b.has("position") ? b.get("position").asInt() : null)));
                }
                case "PUT" -> {
                    JsonNode b = req.json();
                    yield etag(ApiResponse.ok(personal.updatePlaylist(user, id, pre(req, b), null, null,
                            strings(b, "paths"))));
                }
                default -> null;
            };
            case "share", "unshare" -> m.equals("POST")
                    ? etag(ApiResponse.ok(personal.share(user, id, action.equals("share"), pre(req, optJson(req)))))
                    : null;
            case "copy" -> m.equals("POST")
                    ? ApiResponse.json(201, personal.copyPlaylist(user, user.id(), id, name(req))) : null;
            case "fix-path" -> {
                if (!m.equals("POST")) {
                    yield null;
                }
                JsonNode b = req.json();
                if (!b.path("index").isInt()) {
                    throw ApiException.badRequest("index must be an integer");
                }
                yield etag(ApiResponse.ok(personal.fixPath(user, id, pre(req, b), b.get("index").asInt(),
                        b.path("path").asText(""))));
            }
            default -> null;
        };
    }

    // ---- /me/favorites ----

    private ApiResponse favorites(ApiRequest req, boolean root, User user) {
        if (!root) {
            return null;
        }
        return switch (req.method()) {
            case "GET" -> etagged(req, personal.favorites(user, req.query()));
            case "POST" -> {
                JsonNode b = req.json();
                yield etag(ApiResponse.ok(personal.addFavorites(user, pre(req, b), strings(b, "paths"))));
            }
            case "PUT" -> {
                JsonNode b = req.json();
                yield etag(ApiResponse.ok(personal.setFavorites(user, pre(req, b), strings(b, "paths"))));
            }
            case "DELETE" -> {
                JsonNode b = req.body().length == 0 ? null : req.json();
                List<String> paths = b != null ? strings(b, "paths") : List.of(req.required("path"));
                yield etag(ApiResponse.ok(personal.removeFavorites(user, pre(req, b), paths)));
            }
            default -> null;
        };
    }

    // ---- /me/queue ----

    private ApiResponse queue(ApiRequest req, User user) {
        return switch (req.method()) {
            case "GET" -> etagged(req, personal.queue(user));
            case "PUT" -> {
                JsonNode b = req.json();
                PersonalService.QueueInput in = new PersonalService.QueueInput(strings(b, "items"),
                        b.path("index").asInt(0), b.path("positionSeconds").asDouble(0), b.path("shuffle").asBoolean(),
                        b.path("repeat").isTextual() ? b.get("repeat").asText() : null,
                        b.has("source") && b.get("source").isObject() ? b.get("source") : null,
                        b.path("deviceId").isTextual() ? b.get("deviceId").asText() : null);
                yield etag(ApiResponse.ok(personal.putQueue(user, pre(req, b), in)));
            }
            default -> null;
        };
    }

    // ---- /me/history ----

    private ApiResponse history(ApiRequest req, List<String> seg, User user) {
        if (seg.isEmpty() && req.method().equals("POST")) {
            JsonNode b = req.json();
            if (!b.path("path").isTextual()) {
                throw ApiException.badRequest("path is required");
            }
            return ApiResponse.ok(personal.reportPlay(user, b.get("path").asText(),
                    b.path("playedSeconds").asDouble(0), b.path("durationSeconds").asDouble(0),
                    b.path("force").asBoolean(false)));
        }
        if (seg.size() == 1 && req.method().equals("DELETE")) {
            String p = req.query().get("path");
            if (p == null || p.isBlank()) {
                throw ApiException.badRequest("path is required");
            }
            switch (seg.get(0)) {
                case "recent" -> personal.forgetRecent(user, p);
                case "most-played" -> personal.forgetCount(user, p);
                default -> {
                    return null;
                }
            }
            return ApiResponse.noContent();
        }
        if (seg.size() == 1 && req.isRead()) {
            return switch (seg.get(0)) {
                case "recent" -> ApiResponse.ok(personal.recentlyPlayed(user, req.query()));
                case "most-played" -> ApiResponse.ok(personal.mostPlayed(user, req.query()));
                default -> null;
            };
        }
        return null;
    }

    // ---- helpers ----

    private static Precondition pre(ApiRequest req, JsonNode body) {
        Precondition h = Precondition.ifMatch(req.header("if-match"));
        if (h.version() != null) {
            return h;
        }
        return body != null && body.path("version").isIntegralNumber() ? Precondition.body(body.get("version").asLong())
                : Precondition.NONE;
    }

    private static JsonNode optJson(ApiRequest req) {
        return req.body().length == 0 ? null : req.json();
    }

    private static String name(ApiRequest req) {
        JsonNode b = optJson(req);
        return b == null ? null : text(b, "name");
    }

    private static String text(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw ApiException.badRequest(field + " must be a string");
        }
        return v.asText();
    }

    private static List<String> strings(JsonNode b, String field) {
        JsonNode a = b.get(field);
        if (a == null || !a.isArray()) {
            throw ApiException.badRequest(field + " must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : a) {
            if (!e.isTextual()) {
                throw ApiException.badRequest(field + " must be an array of strings");
            }
            out.add(e.asText());
        }
        return out;
    }

    private static ApiResponse etag(ApiResponse r) {
        JsonNode v = r.status() == 200 || r.status() == 201 ? r.jsonBody().get("version") : null;
        return v == null ? r : r.withHeader("ETag", "\"" + v.asLong() + "\"");
    }

    /** A read answered with its ETag; 304 when If-None-Match matches. */
    private static ApiResponse etagged(ApiRequest req, Map<String, Object> body) {
        ApiResponse r = etag(ApiResponse.ok(body));
        String tag = r.headers().get("ETag");
        String inm = req.header("if-none-match");
        if (tag != null && inm != null && (inm.equals(tag) || inm.equals("W/" + tag))) {
            return new ApiResponse(304, null, null, null, null).withHeader("ETag", tag);
        }
        return r;
    }

    private static ApiException convert(PersonalException e) {
        int status = switch (e.code()) {
            case "not_found" -> 404;
            case "conflict", "too_many" -> 409;
            case "precondition_failed" -> 412;
            case "rate_limited" -> 429;
            default -> 400;
        };
        return new ApiException(status, e.code(), e.getMessage(), e.details());
    }
}
