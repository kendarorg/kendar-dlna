package org.kendar.dlna.adminweb;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.ingestion.ScanAlreadyRunningException;
import org.kendar.dlna.ingestion.ScanReport;
import org.kendar.dlna.ingestion.ScanStatus;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.shuffle.RandomSnapshot;

import com.fasterxml.jackson.databind.JsonNode;

/** REST resources under {@code /admin}: rescan, scan status, unsupported files, invalid settings, streams, ... */
final class OpsResources {

    private static final Logger LOG = Logger.getLogger(OpsResources.class.getName());

    private final AdminServices svc;

    OpsResources(AdminServices svc) {
        this.svc = svc;
    }

    ApiResponse handle(ApiRequest req, List<String> seg) {
        if (seg.size() != 1) {
            return null;
        }
        return switch (seg.get(0)) {
            case "rescan" -> req.method().equals("POST") ? rescan(req) : null;
            case "scan" -> req.isRead() ? ApiResponse.ok(scan()) : null;
            case "unsupported" -> req.isRead() ? unsupported() : null;
            case "invalid-settings" -> req.isRead() ? invalidSettings() : null;
            case "streams" -> req.isRead() ? streams() : null;
            case "snapshots" -> req.isRead() ? snapshots() : null;
            case "version" -> req.isRead() ? version() : null;
            default -> null;
        };
    }

    /** Body (optional): {@code {"path": "root/dir", "force": true}} rescans one directory, else the whole
     * library; {@code force} re-reads the tags of every file in scope (ignoring the mtime + size reuse). */
    private ApiResponse rescan(ApiRequest req) {
        String path = null;
        boolean force = false;
        if (req.body().length > 0) {
            JsonNode n = req.json();
            path = n.hasNonNull("path") ? n.get("path").asText() : null;
            force = n.path("force").asBoolean(false);
        }
        try {
            if (path == null || path.isBlank()) {
                svc.rescans().rescanAll(org.kendar.dlna.ingestion.ScanTrigger.MANUAL, force)
                        .whenComplete((r, e) -> log(r, e));
            } else {
                svc.rescans().rescanFolder(RootRef.parse(path), force).whenComplete((r, e) -> log(r, e));
            }
        } catch (ScanAlreadyRunningException e) {
            throw new ApiException(409, "scan_running", "A scan is already running");
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        }
        return ApiResponse.json(202, Dto.obj("started", true, "force", force, "scope", path == null || path.isBlank() ? null : path));
    }

    private static void log(ScanReport r, Throwable e) {
        if (e != null) {
            LOG.log(Level.WARNING, "Manual rescan failed", e);
        }
    }

    Map<String, Object> scan() {
        ScanStatus s = svc.rescans().status();
        return Dto.obj("running", s.running(), "trigger", s.trigger() == null ? null : s.trigger().name(),
                "scope", s.scope() == null ? null : s.scope().qualified(),
                "startedAt", s.startedAt() == null ? null : s.startedAt().toString(),
                "directories", s.directories(), "audioFiles", s.audioFiles(), "tracksReused", s.tracksReused(),
                "tracksRead", s.tracksRead(), "errors", s.errors(), "last", report(s.lastReport()),
                "scannedAt", svc.catalog().current().scannedAt().toString(),
                "trackCount", svc.catalog().current().tracks().size());
    }

    private static Map<String, Object> report(ScanReport r) {
        if (r == null) {
            return null;
        }
        return Dto.obj("trigger", r.trigger().name(), "scope", r.scope() == null ? null : r.scope().qualified(),
                "startedAt", r.startedAt().toString(), "finishedAt", r.finishedAt().toString(),
                "directories", r.directories(), "audioFiles", r.audioFiles(), "tracksReused", r.tracksReused(),
                "tracksRead", r.tracksRead(), "tracksBefore", r.tracksBefore(), "tracksAfter", r.tracksAfter(),
                "changedContainers", r.changedContainers().size(), "errors", r.errors(), "failure", r.failure());
    }

    private ApiResponse unsupported() {
        List<Object> out = new ArrayList<>();
        for (UnsupportedFile u : svc.catalog().current().unsupported()) {
            out.add(Dto.obj("path", u.ref().qualified(), "size", u.size(), "reason", u.reason()));
        }
        return ApiResponse.ok(Dto.obj("files", out));
    }

    private ApiResponse invalidSettings() {
        List<Object> out = new ArrayList<>();
        for (Folder f : svc.catalog().current().foldersWithSettingsErrors()) {
            out.add(Dto.obj("path", f.ref().qualified(), "error", f.settingsError()));
        }
        return ApiResponse.ok(Dto.obj("folders", out));
    }

    private ApiResponse streams() {
        Snapshot snap = svc.catalog().current();
        List<Object> out = new ArrayList<>();
        for (ActiveStreams.Info i : svc.streams().list()) {
            String title = snap.trackById(i.itemId()).map(DisplayNames::trackTitle).orElse(null);
            String path = snap.trackById(i.itemId()).map(t -> t.ref().qualified()).orElse(null);
            out.add(Dto.obj("id", i.id(), "client", i.client(), "container", i.containerId(), "item", i.itemId(),
                    "title", title, "path", path, "variant", i.variant(), "transcoded", !i.variant().equals("original"),
                    "startedAt", i.startedAt().toString()));
        }
        Map<String, Object> cache = svc.cache() == null ? null
                : Dto.obj("entries", svc.cache().entryCount(), "sizeBytes", svc.cache().sizeBytes());
        return ApiResponse.ok(Dto.obj("streams", out, "transcodeCache", cache, "prefetchJobs",
                svc.prefetchJobs().getAsInt()));
    }

    private ApiResponse snapshots() {
        Snapshot snap = svc.catalog().current();
        List<Object> out = new ArrayList<>();
        for (RandomSnapshot s : svc.shuffle().snapshots()) {
            String dir = snap.folderById(s.directoryId()).map(f -> f.ref().qualified()).orElse(null);
            out.add(Dto.obj("context", s.contextId(), "id", s.id(), "directory", dir,
                    "clientIp", s.client().ip(), "clientAgent", s.client().userAgent(), "requestedSize",
                    s.requestedSize(), "tracks", s.tracks(snap).size(), "createdAt", s.createdAt().toString(),
                    "lastActivity", s.lastActivity().toString(), "activeStreams", s.activeStreams()));
        }
        return ApiResponse.ok(Dto.obj("snapshots", out));
    }

    private ApiResponse version() {
        BuildInfo b = svc.build();
        return ApiResponse.ok(Dto.obj("version", b.version(), "commit", b.commit(), "buildTime", b.buildTime(),
                "java", System.getProperty("java.version"),
                "startedAt", java.time.Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime())
                        .toString()));
    }
}
