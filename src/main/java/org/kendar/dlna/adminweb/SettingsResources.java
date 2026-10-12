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
}
