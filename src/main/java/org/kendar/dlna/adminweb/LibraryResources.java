package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.kendar.dlna.catalog.Child;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.curation.FolderService;
import org.kendar.dlna.curation.ListService;
import org.kendar.dlna.ingestion.ListGenerator;
import org.kendar.dlna.ingestion.ListJson;
import org.kendar.dlna.curation.TagEdit;
import org.kendar.dlna.curation.TagService;
import org.kendar.dlna.search.Kind;
import org.kendar.dlna.search.Query;
import org.kendar.dlna.search.SearchEngine;
import org.kendar.dlna.search.SearchHit;
import org.kendar.dlna.search.SearchScope;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.databind.JsonNode;

/** REST resources: {@code /admin/folders/...}, {@code /admin/tracks...}, {@code /admin/search}. */
final class LibraryResources {

    static final int SEARCH_LIMIT = 300;

    private final AdminServices svc;

    LibraryResources(AdminServices svc) {
        this.svc = svc;
    }

    // ---- folders ----

    /** MIDI, Opus and raw AAC files cannot hold album tags: they never count as out of sync. */
    private static List<Track> syncable(List<Track> tracks) {
        return tracks.stream().filter(t -> !t.isSegment() && t.format().editableFields().contains("album")).toList();
    }

    ApiResponse folders(ApiRequest req, List<String> seg) {
        if (seg.size() == 2 && seg.get(0).equals("list") && seg.get(1).equals("generate")) {
            return req.method().equals("POST") ? generateListJson(ref(req)) : null;
        }
        if (seg.size() >= 2 && seg.get(0).equals("list") && seg.get(1).equals("file")) {
            if (seg.size() == 2) {
                return switch (req.method()) {
                    case "GET" -> fileList(ref(req));
                    case "PUT" -> saveFileList(req);
                    default -> null;
                };
            }
            return seg.size() == 3 && seg.get(2).equals("generate") && req.method().equals("POST")
                    ? generateFileList(ref(req)) : null;
        }
        String sub = seg.size() == 1 ? seg.get(0) : "";
        return switch (sub) {
            case "tree" -> req.isRead() ? tree() : null;
            case "contents" -> req.isRead() ? contents(ref(req)) : null;
            case "settings" -> switch (req.method()) {
                case "GET" -> settings(ref(req));
                case "PUT" -> saveSettings(req);
                default -> null;
            };
            case "list" -> switch (req.method()) {
                case "GET" -> listJson(ref(req));
                case "PUT" -> saveListJson(req);
                default -> null;
            };
            case "sync-to-tags" -> req.method().equals("POST") ? syncToTags(req) : null;
            case "sync-from-tags" -> req.method().equals("POST")
                    ? syncFromTags(ref(req), !"true".equals(req.query().get("preview"))) : null;
            case "art" -> switch (req.method()) {
                case "GET" -> artFile(req);
                case "PUT", "POST" -> uploadArt(req);
                default -> null;
            };
            default -> null;
        };
    }

    private static RootRef ref(ApiRequest req) {
        try {
            return RootRef.parse(req.required("path"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "bad_request", "Invalid path: " + req.query().get("path"));
        }
    }

    private ApiResponse tree() {
        Snapshot snap = svc.catalog().current();
        List<Object> roots = new ArrayList<>();
        for (Folder root : snap.roots()) {
            roots.add(node(snap, root));
        }
        return ApiResponse.ok(Dto.obj("roots", roots));
    }

    private Map<String, Object> node(Snapshot snap, Folder f) {
        List<Object> kids = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();
        for (Child c : snap.children(f.ref())) {
            switch (c) {
                case Child.FolderChild fc -> kids.add(node(snap, fc.folder()));
                case Child.TrackChild tc -> tracks.add(tc.track());
            }
        }
        Map<String, Object> m = Dto.folderRef(f);
        m.put("trackCount", tracks.size());
        m.put("settingsError", f.settingsError() != null);
        m.put("listError", f.listError() != null);
        m.put("outOfSync", EffectiveTrack.outOfSync(f.settings(), syncable(tracks)));
        m.put("children", kids);
        return m;
    }

    private ApiResponse contents(RootRef ref) {
        Snapshot snap = svc.catalog().current();
        Folder f = snap.folder(ref).orElseThrow(() -> ApiException.notFound("Unknown directory: " + ref));
        return ApiResponse.ok(folderDetail(snap, f));
    }

    private Map<String, Object> folderDetail(Snapshot snap, Folder f) {
        List<Object> children = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();
        int folders = 0;
        for (Child c : snap.children(f.ref())) {
            switch (c) {
                case Child.FolderChild fc -> {
                    folders++;
                    Map<String, Object> m = Dto.folderRef(fc.folder());
                    m.put("type", "folder");
                    m.put("hasFront", fc.folder().hasFront());
                    m.put("hasList", fc.folder().listHash() != null);
                    m.put("settingsError", fc.folder().settingsError() != null);
                    m.put("listError", fc.folder().listError() != null);
                    int subTracks = 0;
                    for (Child gc : snap.children(fc.folder().ref())) {
                        if (gc instanceof Child.TrackChild) {
                            subTracks++;
                        }
                    }
                    m.put("trackCount", subTracks);
                    children.add(m);
                }
                case Child.TrackChild tc -> {
                    tracks.add(tc.track());
                    children.add(Dto.obj("type", "track", "path", tc.track().ref().qualified(), "name",
                            tc.track().fileName(), "track", Dto.track(tc.track(), f.settings())));
                }
            }
        }
        Map<String, Object> m = Dto.folderRef(f);
        m.put("isRoot", f.isRoot());
        m.put("settings", Dto.settings(f.settings()));
        m.put("settingsError", f.settingsError());
        m.put("outOfSync", EffectiveTrack.outOfSync(f.settings(), syncable(tracks)));
        m.put("hasFront", f.hasFront());
        m.put("hasRear", f.hasRear());
        m.put("hasList", f.listHash() != null);
        m.put("listError", f.listError());
        m.put("missingOrderEntries", snap.missingOrderEntries(f.ref()));
        m.put("trackCount", tracks.size());
        m.put("folderCount", folders);
        m.put("children", children);
        return m;
    }

    private ApiResponse settings(RootRef ref) {
        Folder f = svc.catalog().current().folder(ref)
                .orElseThrow(() -> ApiException.notFound("Unknown directory: " + ref));
        return ApiResponse.ok(Dto.obj("settings", Dto.settings(f.settings()), "settingsError", f.settingsError()));
    }

    private ApiResponse saveSettings(ApiRequest req) {
        RootRef ref = ref(req);
        Folder f = svc.folders().saveSettings(ref, req.json());
        return ApiResponse.ok(Dto.obj("settings", Dto.settings(f.settings()), "settingsError", null));
    }

    // ---- list.json ----

    private static Map<String, Object> listView(ListService.View v) {
        List<Object> entries = new ArrayList<>();
        for (ListJson.Entry e : v.entries()) {
            entries.add(entryView(e));
        }
        return Dto.obj("exists", v.exists(), "version", v.version(), "entries", entries, "files", v.files(),
                "missing", v.missing(), "unknown", v.unknown(), "warnings", v.warnings(), "error", v.error(),
                "sidecars", v.sidecars());
    }

    private static Map<String, Object> fileListView(RootRef ref, ListService.FileView v) {
        List<Object> entries = new ArrayList<>();
        for (ListJson.Entry e : v.entries()) {
            entries.add(entryView(e));
        }
        return Dto.obj("path", ref.qualified(), "fileName", v.fileName(), "sidecar", v.sidecar(), "exists",
                v.exists(), "version", v.version(), "split", v.split(), "entries", entries, "files",
                List.of(v.fileName()), "warnings", v.warnings(), "error", v.error());
    }

    /** The sections list {@code <file>.json} of one audio file ({@code path} = the file). */
    private ApiResponse fileList(RootRef ref) {
        return ApiResponse.ok(fileListView(ref, svc.folders().lists().readFile(ref)));
    }

    /** Preview of the entries the file's cue sheet / chapters give; writes nothing. */
    private ApiResponse generateFileList(RootRef ref) {
        ListGenerator.Result r = svc.folders().lists().generateFile(ref);
        List<Object> entries = new ArrayList<>();
        r.entries().forEach(e -> entries.add(entryView(e)));
        List<Object> sources = new ArrayList<>();
        r.sources().forEach(s -> sources.add(Dto.obj("filename", s.filename(), "origin", s.origin(), "cueFile",
                s.cueFile(), "tracks", s.tracks())));
        return ApiResponse.ok(Dto.obj("entries", entries, "sources", sources, "warnings", r.warnings()));
    }

    private ApiResponse saveFileList(ApiRequest req) {
        RootRef ref = ref(req);
        return ApiResponse.ok(fileListView(ref, svc.folders().lists().saveFile(ref, entriesOf(req.json()),
                versionOf(req.json()))));
    }

    private static Map<String, Object> entryView(ListJson.Entry e) {
        return Dto.obj("filename", e.filename(), "index", e.index(), "start", e.start(), "author", e.author(),
                "partOf", e.partOf(), "title", e.title(), "transcriber", e.transcriber());
    }

    private ApiResponse listJson(RootRef ref) {
        return ApiResponse.ok(listView(svc.folders().lists().read(ref)));
    }

    /** Preview of the entries the directory's cue sheets / chapters give; writes nothing. */
    private ApiResponse generateListJson(RootRef ref) {
        ListGenerator.Result r = svc.folders().lists().generate(ref);
        List<Object> entries = new ArrayList<>();
        r.entries().forEach(e -> entries.add(entryView(e)));
        List<Object> sources = new ArrayList<>();
        r.sources().forEach(s -> sources.add(Dto.obj("filename", s.filename(), "origin", s.origin(), "cueFile",
                s.cueFile(), "tracks", s.tracks())));
        return ApiResponse.ok(Dto.obj("entries", entries, "sources", sources, "warnings", r.warnings()));
    }

    private static Integer integer(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return null;
        }
        if (v.isIntegralNumber() && v.canConvertToInt()) {
            return v.asInt();
        }
        if (v.isTextual() && v.asText().strip().matches("\\d{1,9}")) {
            return Integer.valueOf(v.asText().strip());
        }
        throw new ApiException(400, "bad_request", "\"" + field + "\" must be a whole number");
    }

    /** Body {@code {"version":"...","entries":[{filename,author,partOf,title,transcriber}]}}; no entries deletes the file. */
    private ApiResponse saveListJson(ApiRequest req) {
        RootRef ref = ref(req);
        JsonNode body = req.json();
        return ApiResponse.ok(listView(svc.folders().lists().save(ref, entriesOf(body), versionOf(body))));
    }

    private static String versionOf(JsonNode body) {
        JsonNode version = body.get("version");
        return version == null || version.isNull() ? null : version.asText();
    }

    private static List<ListJson.Entry> entriesOf(JsonNode body) {
        JsonNode arr = body.get("entries");
        if (arr == null || !arr.isArray()) {
            throw new ApiException(400, "bad_request", "\"entries\": an array is required");
        }
        List<ListJson.Entry> entries = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isObject()) {
                throw new ApiException(400, "bad_request", "entries must be objects");
            }
            entries.add(new ListJson.Entry(text(n, "filename"), text(n, "author"), text(n, "partOf"),
                    text(n, "title"), text(n, "transcriber"), integer(n, "index"), text(n, "start")));
        }
        return entries;
    }

    /** Body {@code {"files":["01 a.flac", ...]}}: the files the user confirmed; there is no "write everything". */
    private ApiResponse syncToTags(ApiRequest req) {
        com.fasterxml.jackson.databind.JsonNode files = req.json().get("files");
        if (files == null || !files.isArray()) {
            throw new ApiException(400, "bad_request", "\"files\": the list of files to write is required");
        }
        List<String> names = new ArrayList<>();
        files.forEach(n -> names.add(n.asText()));
        FolderService.ToTagsOutcome o = svc.folders().syncToTags(ref(req), names);
        List<Object> results = new ArrayList<>();
        o.results().forEach(r -> results.add(Dto.obj("path", r.ref().qualified(), "ok", r.ok(), "error", r.error())));
        return ApiResponse.ok(Dto.obj("results", results, "skipped", o.skipped()));
    }

    private ApiResponse syncFromTags(RootRef ref, boolean write) {
        FolderService.FromTagsOutcome o = svc.folders().syncFromTags(ref, write);
        if (!o.ok()) {
            return ApiResponse.error(409, "sync_conflict",
                    "The tracks disagree on " + o.conflicts().size() + " field(s); nothing was written",
                    Dto.obj("conflicts", o.conflicts()));
        }
        return ApiResponse.ok(Dto.obj("settings", Dto.settings(o.settings())));
    }

    private static FolderService.ArtKind kind(ApiRequest req) {
        return FolderService.ArtKind.parse(req.required("kind"))
                .orElseThrow(() -> new ApiException(400, "bad_request", "kind must be front or rear"));
    }

    private ApiResponse uploadArt(ApiRequest req) {
        FolderService.ArtKind kind = kind(req);
        Folder f = svc.folders().saveArt(ref(req), kind, req.body());
        return ApiResponse.ok(Dto.obj("hasFront", f.hasFront(), "hasRear", f.hasRear()));
    }

    private ApiResponse artFile(ApiRequest req) {
        Optional<Path> file = svc.folders().artFile(ref(req), kind(req));
        if (file.isEmpty()) {
            throw ApiException.notFound("No such picture");
        }
        try {
            return ApiResponse.bytes("image/jpeg", Files.readAllBytes(file.get()))
                    .withHeader("Cache-Control", "private, no-cache");
        } catch (IOException e) {
            throw ApiException.notFound("The picture cannot be read");
        }
    }

    // ---- tracks ----

    ApiResponse tracks(ApiRequest req, List<String> seg) {
        if (seg.isEmpty()) {
            return switch (req.method()) {
                case "GET" -> track(req);
                case "PUT" -> writeTrack(req);
                default -> null;
            };
        }
        if (seg.size() == 1 && seg.get(0).equals("batch") && req.method().equals("POST")) {
            return writeBatch(req);
        }
        return null;
    }

    private ApiResponse track(ApiRequest req) {
        RootRef ref = ref(req);
        Snapshot snap = svc.catalog().current();
        Track t = snap.track(ref).orElseThrow(() -> ApiException.notFound("Unknown track: " + ref));
        return ApiResponse.ok(Dto.track(t, snap.folder(t.parentRef()).map(Folder::settings)
                .orElse(DirectorySettings.DEFAULT)));
    }

    private ApiResponse writeTrack(ApiRequest req) {
        RootRef ref = ref(req);
        Track t = svc.tags().writeOne(ref, edit(req.json()));
        Snapshot snap = svc.catalog().current();
        return ApiResponse.ok(Dto.track(t, snap.folder(t.parentRef()).map(Folder::settings)
                .orElse(DirectorySettings.DEFAULT)));
    }

    private ApiResponse writeBatch(ApiRequest req) {
        JsonNode body = req.json();
        JsonNode paths = body.get("paths");
        if (paths == null || !paths.isArray() || paths.isEmpty()) {
            throw new ApiException(400, "bad_request", "paths must be a non-empty array");
        }
        List<RootRef> refs = new ArrayList<>();
        for (JsonNode p : paths) {
            try {
                refs.add(RootRef.parse(p.asText()));
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "bad_request", "Invalid path: " + p.asText());
            }
        }
        List<TagService.Result> results = svc.tags().write(refs, edit(body.get("edit")));
        List<Object> out = new ArrayList<>();
        results.forEach(r -> out.add(Dto.obj("path", r.ref().qualified(), "ok", r.ok(), "error", r.error())));
        long failed = results.stream().filter(r -> !r.ok()).count();
        return ApiResponse.ok(Dto.obj("results", out, "failed", failed));
    }

    /** Fields absent or JSON null are left alone, an empty string clears the tag. */
    static TagEdit edit(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new ApiException(400, "bad_request", "edit must be an object");
        }
        return new TagEdit(text(n, "title"), text(n, "artist"), text(n, "album"), text(n, "albumArtist"),
                text(n, "genre"), text(n, "year"), text(n, "trackNo"), text(n, "discNo"), text(n, "discTotal"),
                text(n, "composer"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    // ---- search ----

    ApiResponse search(ApiRequest req) {
        if (!req.isRead()) {
            return null;
        }
        String q = req.query().getOrDefault("q", "");
        String kindParam = req.query().getOrDefault("kind", "all");
        SearchScope scope = req.query().containsKey("scope") && !req.query().get("scope").isBlank()
                ? SearchScope.below(RootRef.parse(req.query().get("scope"))) : SearchScope.LIBRARY;
        Query query = Query.text(q);
        switch (kindParam) {
            case "track" -> query = new Query.And(List.of(query, new Query.Kinds(EnumSet.of(Kind.TRACK))));
            case "folder" -> query = new Query.And(List.of(query, new Query.Kinds(EnumSet.of(Kind.FOLDER))));
            default -> {
            }
        }
        Snapshot snap = svc.catalog().current();
        List<SearchHit> hits = SearchEngine.search(snap, scope, query);
        List<Object> out = new ArrayList<>();
        for (SearchHit h : hits) {
            if (out.size() >= SEARCH_LIMIT) {
                break;
            }
            switch (h) {
                case SearchHit.TrackHit th -> out.add(Dto.obj("type", "track", "path", th.track().ref().qualified(),
                        "track", Dto.track(th.track(), snap.folder(th.track().parentRef()).map(Folder::settings)
                                .orElse(DirectorySettings.DEFAULT))));
                case SearchHit.FolderHit fh -> {
                    Map<String, Object> m = Dto.folderRef(fh.folder());
                    m.put("type", "folder");
                    out.add(m);
                }
            }
        }
        return ApiResponse.ok(Dto.obj("total", hits.size(), "truncated", hits.size() > out.size(), "hits", out));
    }
}
