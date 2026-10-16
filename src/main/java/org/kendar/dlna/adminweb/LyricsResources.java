package org.kendar.dlna.adminweb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.kendar.dlna.identity.Access;
import org.kendar.dlna.identity.User;
import org.kendar.dlna.lyrics.LrcParser;
import org.kendar.dlna.lyrics.Lyrics;
import org.kendar.dlna.lyrics.LyricsService;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * REST resources for lyrics. Signed-in users read what a track already has ({@code GET /library/lyrics?path=},
 * confined to their roots); admins look lyrics up at the configured provider, save them as sidecar files and run
 * the folder job ({@code /admin/lyrics/...}).
 */
final class LyricsResources {

    private final LyricsService lyrics;

    LyricsResources(LyricsService lyrics) {
        this.lyrics = lyrics;
    }

    private LyricsService service() {
        if (lyrics == null) {
            throw ApiException.notFound("Lyrics are not available on this server");
        }
        return lyrics;
    }

    static Map<String, Object> body(Lyrics l) {
        List<Object> lines = new ArrayList<>();
        for (Lyrics.Line line : l.lines()) {
            lines.add(Dto.obj("t", line.startMillis(), "text", line.text()));
        }
        return Dto.obj("source", l.source(), "synced", l.synced(), "lines", lines);
    }

    private static RootRef ref(String path) {
        try {
            return RootRef.parse(path);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "bad_request", "Invalid path: " + path);
        }
    }

    /** {@code GET /library/lyrics?path=}: the lyrics a track already has, or {@code {"lyrics": null}}. */
    ApiResponse user(ApiRequest req, User user) {
        if (!req.isRead()) {
            return null;
        }
        RootRef ref = ref(req.required("path"));
        Access.requireRoot(user, ref);
        var found = service().local(ref);
        return ApiResponse.ok(Dto.obj("lyrics", found.map(LyricsResources::body).orElse(null),
                "canLookUp", service().providerId() != null));
    }

    /** {@code /admin/lyrics/...}. */
    ApiResponse admin(ApiRequest req, List<String> seg) {
        if (seg.isEmpty()) {
            return null;
        }
        return switch (seg.get(0)) {
            case "status" -> req.isRead() ? ApiResponse.ok(Dto.obj("provider", service().providerId())) : null;
            case "lookup" -> req.method().equals("POST") ? lookup(req) : null;
            case "save" -> req.method().equals("POST") ? save(req) : null;
            case "folder" -> seg.size() == 2 ? folder(req, seg.get(1)) : null;
            default -> null;
        };
    }

    private ApiResponse lookup(ApiRequest req) {
        RootRef ref = ref(req.json().path("path").asText(""));
        List<Object> candidates = new ArrayList<>();
        service().lookup(ref).forEach(l -> candidates.add(body(l)));
        var local = service().local(ref);
        return ApiResponse.ok(Dto.obj("path", ref.qualified(), "candidates", candidates,
                "existing", local.map(LyricsResources::body).orElse(null)));
    }

    /** Body {@code {path, text}}: LRC (time tags make it synced) or plain text; written next to the audio file. */
    private ApiResponse save(ApiRequest req) {
        JsonNode b = req.json();
        RootRef ref = ref(b.path("path").asText(""));
        String text = b.path("text").asText("");
        if (text.length() > 1_000_000) {
            throw new ApiException(400, "bad_request", "The lyrics are too long");
        }
        Lyrics l = LrcParser.parse(text, "saved");
        String file = service().save(ref, l);
        return ApiResponse.ok(Dto.obj("file", file, "synced", l.synced()));
    }

    private ApiResponse folder(ApiRequest req, String action) {
        return switch (action) {
            case "start" -> {
                if (!req.method().equals("POST")) {
                    yield null;
                }
                JsonNode b = req.json();
                var st = service().startFolder(ref(b.path("path").asText("")), b.path("replaceExisting").asBoolean(false));
                yield ApiResponse.json(202, jobBody(st));
            }
            case "job" -> req.isRead() ? ApiResponse.ok(jobBody(service().status())) : null;
            case "apply" -> {
                if (!req.method().equals("POST")) {
                    yield null;
                }
                List<String> paths = new ArrayList<>();
                req.json().path("paths").forEach(p -> paths.add(p.asText()));
                List<String> written = service().applyFolder(paths);
                yield ApiResponse.ok(Dto.obj("written", written, "job", jobBody(service().status())));
            }
            default -> null;
        };
    }

    private static Map<String, Object> jobBody(LyricsService.Status s) {
        List<Object> items = new ArrayList<>();
        for (LyricsService.Item i : s.items()) {
            Lyrics c = i.candidate();
            String preview = c == null ? null : c.lines().stream().map(Lyrics.Line::text).filter(t -> !t.isBlank())
                    .limit(2).reduce((a, x) -> a + " / " + x).orElse(null);
            items.add(Dto.obj("path", i.path(), "title", i.title(), "outcome", i.outcome().name().toLowerCase(),
                    "synced", c != null && c.synced(), "preview", preview, "error", i.error(), "applied", i.applied()));
        }
        return Dto.obj("running", s.running(), "directory", s.directory(), "total", s.total(), "done", s.done(),
                "items", items, "startedAt", s.startedAt() == null ? null : s.startedAt().toString(),
                "finishedAt", s.finishedAt() == null ? null : s.finishedAt().toString(), "error", s.error());
    }
}
