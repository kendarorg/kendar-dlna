package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.kendar.dlna.curation.AtomicFiles;
import org.kendar.dlna.curation.CurationException;
import org.kendar.dlna.shared.Json;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * REST resources {@code /admin/settings/...}: export and import of the global settings, in two parts.
 * {@code library}: the {@code settings.json} of every directory, applied live. {@code server}: {@code config.json}
 * (without the legacy {@code admin} section), written to the config directory and read at the next start.
 */
final class SettingsResources {

    static final int FORMAT_VERSION = 1;
    static final String CONFIG_FILE = "config.json";

    private final AdminServices svc;

    SettingsResources(AdminServices svc) {
        this.svc = svc;
    }

    ApiResponse handle(ApiRequest req, List<String> seg) {
        if (seg.size() == 1 && seg.get(0).equals("navigations")) {
            return switch (req.method()) {
                case "GET" -> getNavigations();
                case "PUT" -> putNavigations(req);
                default -> null;
            };
        }
        if (seg.size() != 2) {
            return null;
        }
        boolean library = seg.get(0).equals("library");
        if (!library && !seg.get(0).equals("server")) {
            return null;
        }
        return switch (seg.get(1)) {
            case "download" -> req.isRead() ? (library ? exportLibrary() : exportServer()) : null;
            case "upload" -> req.method().equals("POST") ? (library ? importLibrary(req) : importServer(req)) : null;
            default -> null;
        };
    }

    // ---- library: the settings.json files ----

    private ApiResponse exportLibrary() {
        ObjectNode out = Json.mapper().createObjectNode();
        out.put("kind", "kendar-library-settings");
        out.put("version", FORMAT_VERSION);
        out.set("folders", svc.folders().exportSettings());
        return ApiResponse.ok(out);
    }

    private ApiResponse importLibrary(ApiRequest req) {
        JsonNode body = req.json();
        JsonNode folders = body.get("folders");
        if (!"kendar-library-settings".equals(body.path("kind").asText())
                || body.path("version").asInt() != FORMAT_VERSION || folders == null || !folders.isObject()) {
            throw new ApiException(400, "bad_request", "Not a library settings export (kind/version/folders)");
        }
        List<Object> results = new ArrayList<>();
        int failed = 0;
        for (var it = folders.fields(); it.hasNext();) {
            var e = it.next();
            String error = null;
            try {
                svc.folders().saveSettings(RootRef.parse(e.getKey()), e.getValue());
            } catch (CurationException | IllegalArgumentException ex) {
                error = ex.getMessage();
                failed++;
            }
            results.add(Dto.obj("path", e.getKey(), "ok", error == null, "error", error));
        }
        return ApiResponse.ok(Dto.obj("results", results, "failed", failed));
    }

    // ---- server: config.json ----

    private Path configFile() {
        if (svc.configDir() == null) {
            throw ApiException.notFound("The config directory is not known to this server");
        }
        return svc.configDir().resolve(CONFIG_FILE);
    }

    private static JsonNode read(Path file) throws IOException {
        return Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private ApiResponse exportServer() {
        Path file = configFile();
        try {
            ObjectNode cfg = (ObjectNode) read(file);
            cfg.remove("admin");   // password hash: never exported
            ObjectNode out = Json.mapper().createObjectNode();
            out.put("kind", "kendar-server-config");
            out.put("version", FORMAT_VERSION);
            out.set("config", cfg);
            return ApiResponse.ok(out);
        } catch (IOException | RuntimeException e) {
            throw new ApiException(500, "io_error", "Cannot read " + file + ": " + e.getMessage());
        }
    }

    /** Validates the exported config and writes it as config.json (the previous one is kept as config.json.bak). */
    private ApiResponse importServer(ApiRequest req) {
        JsonNode body = req.json();
        JsonNode cfg = body.get("config");
        if (!"kendar-server-config".equals(body.path("kind").asText())
                || body.path("version").asInt() != FORMAT_VERSION || cfg == null || !cfg.isObject()) {
            throw new ApiException(400, "bad_request", "Not a server config export (kind/version/config)");
        }
        Path file = configFile();
        ObjectNode next = ((ObjectNode) cfg).deepCopy();
        next.remove("admin");
        try {
            if (svc.configValidator() != null) {
                svc.configValidator().accept(next);
            }
        } catch (RuntimeException e) {
            throw new ApiException(400, "invalid", "Invalid config: " + e.getMessage());
        }
        try {
            if (Files.isRegularFile(file)) {
                JsonNode current = read(file);
                if (current.has("admin")) {
                    next.set("admin", current.get("admin"));   // legacy section: kept as it is
                }
                Files.copy(file, file.resolveSibling(CONFIG_FILE + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            AtomicFiles.write(file, (Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(next) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(500, "io_error", "Cannot write " + file + ": " + e.getMessage());
        }
        return ApiResponse.ok(Dto.obj("restartRequired", true, "backup", CONFIG_FILE + ".bak"));
    }

    // ---- navigations: live ----

    private org.kendar.dlna.catalog.NavigationRegistry registry() {
        if (svc.navigations() == null) {
            throw ApiException.notFound("Navigations are not available on this server");
        }
        return svc.navigations();
    }

    private static Object navigationBody(org.kendar.dlna.catalog.Navigation n) {
        return Dto.obj("name", n.name(), "subitems", n.subitems(), "ranges", n.ranges(), "index", n.index());
    }

    private ApiResponse getNavigations() {
        var st = registry().state();
        List<Object> navs = new ArrayList<>();
        st.navigations().forEach(n -> navs.add(navigationBody(n)));
        return ApiResponse.ok(Dto.obj("version", st.version(), "navigations", navs, "extraTags", st.extraTags(),
                "tags", org.kendar.dlna.catalog.TagNames.TAGS, "virtualTags", org.kendar.dlna.catalog.TagNames.VIRTUAL,
                "discovered", discoveredTags()));
    }

    /** Body {@code {version, navigations: [{name, subitems?, ranges?, index}], extraTags?}}: validated, saved, applied. */
    private ApiResponse putNavigations(ApiRequest req) {
        var registry = registry();
        JsonNode body = req.json();
        if (body.has("version") && body.get("version").asLong(-1) != registry.state().version()) {
            throw new ApiException(409, "conflict", "The navigations were changed meanwhile; reload them first");
        }
        JsonNode navNodes = body.get("navigations");
        if (navNodes == null || !navNodes.isArray()) {
            throw new ApiException(400, "bad_request", "navigations must be an array");
        }
        List<String> extra = new ArrayList<>(registry.state().extraTags());
        if (body.has("extraTags")) {
            if (!body.get("extraTags").isArray()) {
                throw new ApiException(400, "bad_request", "extraTags must be an array of tag names");
            }
            extra = new ArrayList<>();
            for (JsonNode t : body.get("extraTags")) {
                extra.add(t.asText());
            }
        }
        List<org.kendar.dlna.catalog.Navigation> navs = new ArrayList<>();
        for (JsonNode n : navNodes) {
            List<String> index = new ArrayList<>();
            if (n.has("index") && n.get("index").isArray()) {
                n.get("index").forEach(t -> index.add(t.asText()));
            }
            navs.add(new org.kendar.dlna.catalog.Navigation(n.path("name").asText(""),
                    n.hasNonNull("subitems") ? n.get("subitems").asInt() : null,
                    n.hasNonNull("ranges") ? n.get("ranges").asInt() : null, index));
        }
        List<org.kendar.dlna.catalog.Navigation> valid;
        try {
            valid = org.kendar.dlna.catalog.NavigationRules.validate(navs, extra);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid", e.getMessage());
        }
        boolean persisted = persistNavigations(valid, extra);
        var st = registry.set(valid, extra);
        var snap = svc.catalog().current();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (var f : snap.allFolders()) {
            if (f.isRoot() || snap.effectiveSettings(f.ref()).navigations() != null) {
                keys.add(org.kendar.dlna.catalog.ContainerKeys.virtual(f.id()));
            }
        }
        svc.catalog().touch(keys);   // renderers re-read the containers that list navigations
        return ApiResponse.ok(Dto.obj("version", st.version(), "persisted", persisted));
    }

    /** Writes navigations / extraTags into config.json (the previous file is kept as config.json.bak). */
    private boolean persistNavigations(List<org.kendar.dlna.catalog.Navigation> navs, List<String> extra) {
        if (svc.configDir() == null) {
            return false;
        }
        Path file = configFile();
        try {
            ObjectNode cfg = Files.isRegularFile(file) ? (ObjectNode) read(file) : Json.mapper().createObjectNode();
            var arr = cfg.putArray("navigations");
            for (var n : navs) {
                ObjectNode o = arr.addObject();
                o.put("name", n.name());
                if (n.subitems() != null) {
                    o.put("subitems", n.subitems());
                }
                if (n.ranges() != null) {
                    o.put("ranges", n.ranges());
                }
                var idx = o.putArray("index");
                n.index().forEach(idx::add);
            }
            if (extra.isEmpty()) {
                cfg.remove("extraTags");
            } else {
                var et = cfg.putArray("extraTags");
                extra.forEach(et::add);
            }
            if (svc.configValidator() != null) {
                svc.configValidator().accept(cfg);
            }
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(CONFIG_FILE + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            AtomicFiles.write(file, (Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(cfg) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | RuntimeException e) {
            throw new ApiException(500, "io_error", "Cannot write " + file + ": " + e.getMessage());
        }
    }

    /** The tags found in the library by the last scan ({@code tags.json}): name and number of tracks. */
    private Object discoveredTags() {
        List<Object> out = new ArrayList<>();
        if (svc.navigations() == null) {
            return out;
        }
        svc.navigations().discovered().forEach((name, count) -> out.add(Dto.obj("name", name, "count", count)));
        return out;
    }
}
