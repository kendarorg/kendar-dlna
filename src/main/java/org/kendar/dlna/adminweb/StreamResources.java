package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.delivery.KeyedLimiter;
import org.kendar.dlna.delivery.MediaFiles;
import org.kendar.dlna.delivery.MediaTypes;
import org.kendar.dlna.delivery.Prefetcher;
import org.kendar.dlna.delivery.StreamBody;
import org.kendar.dlna.delivery.StreamPlan;
import org.kendar.dlna.delivery.StreamResolver;
import org.kendar.dlna.delivery.StreamVariant;
import org.kendar.dlna.delivery.TranscodeService;
import org.kendar.dlna.identity.Access;
import org.kendar.dlna.identity.User;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * HTTP adapter of the web player's stream group (plan section 7): {@code /stream} for role user and admin.
 *
 * <ul>
 *   <li>{@code GET|HEAD /stream?path=[&formats=]}: the track with format negotiation (the native file when its format
 *       is among the client's, else an MP3 transcode; MIDI always MP3), exact Content-Length, Range.</li>
 *   <li>{@code GET|HEAD /stream/download?path=}: the original with {@code Content-Disposition}; needs the download
 *       permission.</li>
 *   <li>{@code POST /stream/formats}: the formats the client plays, kept for the session.</li>
 *   <li>{@code POST /stream/prefetch}: warms the transcode cache for the next queue item (only when it needs
 *       transcoding).</li>
 * </ul>
 *
 * The allowed-roots check ({@link Access#requireRoot}) runs per request before the existence of the file is
 * revealed. Transcodes share the global pool with DLNA and are capped per user.
 */
final class StreamResources {

    /** Concurrent transcodes (live encodes and MIDI renders) per user. */
    static final int MAX_TRANSCODES_PER_USER = 2;
    static final Set<String> KNOWN_FORMATS = Set.of("mp3", "ogg", "flac", "wav", "aiff", "opus", "aac", "alac",
            "adts");
    static final Set<String> DEFAULT_FORMATS = Set.of("mp3", "ogg", "flac", "wav", "opus");
    private static final int MAX_SESSIONS = 10_000;
    /** Container id of web streams in the stream bookkeeping (not a browsing context). */
    private static final String CONTEXT = "web";

    private final Supplier<Snapshot> snapshot;
    private final MediaFiles files;
    private final StreamResolver resolver;
    private final TranscodeService transcoding;
    private final Prefetcher prefetcher;
    private final ActiveStreams active;
    private final KeyedLimiter limiter;
    private final Map<String, Set<String>> sessionFormats = new ConcurrentHashMap<>();

    /**
     * @param transcoding null = originals only; @param prefetcher null = no prefetch; @param active null = streams
     *                    are not listed for the admin
     */
    StreamResources(Supplier<Snapshot> snapshot, MediaFiles files, StreamResolver resolver,
            TranscodeService transcoding, Prefetcher prefetcher, ActiveStreams active, int maxTranscodesPerUser) {
        this.snapshot = snapshot;
        this.files = files;
        this.resolver = resolver;
        this.transcoding = transcoding;
        this.prefetcher = prefetcher;
        this.active = active;
        this.limiter = new KeyedLimiter(maxTranscodesPerUser);
    }

    ApiResponse handle(ApiRequest req, List<String> seg, User user, String sessionToken) {
        if (seg.isEmpty()) {
            return req.isRead() ? stream(req, user, sessionToken) : null;
        }
        if (seg.size() != 1) {
            return null;
        }
        return switch (seg.get(0)) {
            case "download" -> req.isRead() ? download(req, user) : null;
            case "formats" -> req.method().equals("POST") ? setFormats(req, sessionToken)
                    : req.isRead() ? ApiResponse.ok(Map.of("formats", sorted(formatsOf(req, sessionToken)))) : null;
            case "prefetch" -> req.method().equals("POST") ? prefetch(req, user, sessionToken) : null;
            default -> null;
        };
    }

    // ---- negotiation ----

    private Set<String> formatsOf(ApiRequest req, String sessionToken) {
        String q = req.query().get("formats");
        if (q != null && !q.isBlank()) {
            return parseFormats(List.of(q.split(",")));
        }
        return sessionFormats.getOrDefault(sessionToken, DEFAULT_FORMATS);
    }

    static Set<String> parseFormats(Iterable<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            String f = v.strip().toLowerCase(Locale.ROOT);
            if (KNOWN_FORMATS.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private static List<String> sorted(Set<String> formats) {
        return new ArrayList<>(new java.util.TreeSet<>(formats));
    }

    private ApiResponse setFormats(ApiRequest req, String sessionToken) {
        JsonNode body = req.json();
        JsonNode list = body.isArray() ? body : body.path("formats");
        if (!list.isArray() || list.size() > 50) {
            throw new ApiException(400, "bad_request", "formats must be a list of format names");
        }
        List<String> raw = new ArrayList<>();
        list.forEach(n -> raw.add(n.asText("")));
        Set<String> formats = parseFormats(raw);
        if (sessionFormats.size() >= MAX_SESSIONS && !sessionFormats.containsKey(sessionToken)) {
            sessionFormats.clear();   // stale sessions only cost a re-send by their client
        }
        sessionFormats.put(sessionToken, formats);
        return ApiResponse.ok(Map.of("formats", sorted(formats)));
    }

    /** Native when the client plays the format (never MIDI) or nothing can transcode it; else MP3. */
    StreamVariant choose(Track t, Set<String> formats) {
        boolean nativeOk = t.format() != Format.MIDI && !t.isSegment() && formats.contains(t.format().name().toLowerCase(Locale.ROOT));
        if (nativeOk) {
            return StreamVariant.NATIVE;
        }
        if (transcoding != null && transcoding.canTranscode(t.format())) {
            return StreamVariant.mp3(0);
        }
        if (t.format() == Format.MIDI) {
            throw new ApiException(415, "unsupported_format", "This file cannot be played: MIDI needs transcoding");
        }
        return StreamVariant.NATIVE;   // transcoding is off: the original is all there is
    }

    // ---- locations ----

    private Track track(User user, String path) {
        if (path == null || path.isEmpty()) {
            throw new ApiException(400, "bad_request", "Missing query parameter: path");
        }
        RootRef ref;
        try {
            ref = RootRef.parse(path);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "bad_request", "Invalid path: " + path);
        }
        Access.requireRoot(user, ref);   // before the existence of the file is revealed
        return snapshot.get().track(ref).orElseThrow(() -> ApiException.notFound("No such track: " + path));
    }

    // ---- GET /stream ----

    private ApiResponse stream(ApiRequest req, User user, String sessionToken) {
        Track t = track(user, req.query().get("path"));
        StreamVariant variant = choose(t, formatsOf(req, sessionToken));
        Optional<KeyedLimiter.Permit> permit = Optional.empty();
        if (variant.transcoded()) {
            permit = limiter.tryAcquire(user.id());
            if (permit.isEmpty()) {
                throw new ApiException(429, "too_many_transcodes", "At most " + limiter.max()
                        + " tracks per user can be transcoded at the same time",
                        Dto.obj("limit", limiter.max()));
            }
        }
        StreamResolver.Resolved r;
        try {
            r = resolver.resolve(req.method(), "/" + CONTEXT + "/" + t.id() + "/" + variant.path(),
                    req.header("range"), req.remoteAddr());
        } catch (RuntimeException e) {
            permit.ifPresent(KeyedLimiter.Permit::close);
            throw e;
        }
        StreamPlan plan = r.plan();
        if (plan.status() == 404) {
            permit.ifPresent(KeyedLimiter.Permit::close);
            r.lease().close();
            throw ApiException.notFound("The file is no longer available: " + t.ref().qualified());
        }
        if (plan.status() >= 500) {
            permit.ifPresent(KeyedLimiter.Permit::close);
            r.lease().close();
            throw new ApiException(500, "internal_error", "The track cannot be played");
        }
        // a cached transcode (or nothing to send) is cheap: only live encodes and renders hold a permit
        if (r.body() == null || r.body().file() != null) {
            permit.ifPresent(KeyedLimiter.Permit::close);
            permit = Optional.empty();
        }
        ActiveStreams.Handle open = r.body() != null && active != null
                ? active.open(req.remoteAddr(), CONTEXT, t.id(), variant.path()) : () -> {
                };
        Optional<KeyedLimiter.Permit> held = permit;
        Runnable done = () -> {
            held.ifPresent(KeyedLimiter.Permit::close);
            r.lease().close();
            open.close();
        };
        String format = variant.transcoded() ? "mp3" : t.format().name().toLowerCase(Locale.ROOT);
        return respond(plan, r.body(), done, Dto.obj("X-Stream-Format", format, "X-Stream-Transcoded",
                String.valueOf(variant.transcoded())), null);
    }

    // ---- GET /stream/download ----

    private ApiResponse download(ApiRequest req, User user) {
        Track t = track(user, req.query().get("path"));
        Access.requireDownload(user);
        Path file = files.resolve(t.ref());
        long size;
        try {
            if (file == null) {
                throw new NoSuchFileException(t.ref().qualified());
            }
            size = Files.size(file);
        } catch (IOException e) {
            throw ApiException.notFound("The file is no longer available: " + t.ref().qualified());
        }
        StreamBody body = new StreamBody.FileBody(file, size, MediaTypes.mimeType(t.format()),
                MediaTypes.contentFeatures(t.format()));
        StreamPlan plan = StreamPlan.of(req.method(), req.header("range"), size, body.mimeType(), body.contentFeatures());
        return respond(plan, body, () -> {
        }, Dto.obj(), contentDisposition(t.fileName()));
    }

    /** {@code attachment} with an ASCII fallback name and the RFC 5987 UTF-8 name. */
    static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "'").replace("\\", "_");
        StringBuilder enc = new StringBuilder();
        for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 128 && (Character.isLetterOrDigit(c) || "!#$&+-.^_`|~".indexOf(c) >= 0)) {
                enc.append((char) c);
            } else {
                enc.append('%').append(String.format("%02X", c));
            }
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + enc;
    }

    private static ApiResponse respond(StreamPlan plan, StreamBody body, Runnable done, Map<String, Object> extra,
            String disposition) {
        Map<String, String> headers = new LinkedHashMap<>();
        String contentType = null;
        for (var h : plan.headers().entrySet()) {
            switch (h.getKey()) {
                case "Content-Type" -> contentType = h.getValue();
                case "transferMode.dlna.org", "contentFeatures.dlna.org" -> {
                }
                default -> headers.put(h.getKey(), h.getValue());
            }
        }
        headers.put("Cache-Control", "private, no-cache");
        extra.forEach((k, v) -> headers.put(k, String.valueOf(v)));
        if (disposition != null) {
            headers.put("Content-Disposition", disposition);
        }
        if (plan.status() == 405 || contentType == null) {
            done.run();
            return new ApiResponse(plan.status(), null, null, headers, null);
        }
        if (!plan.sendBody() || plan.length() == 0 || body == null) {
            done.run();
            return new ApiResponse(plan.status(), contentType, null, headers, null);
        }
        return new ApiResponse(plan.status(), contentType, null, headers, null,
                new ApiResponse.Streamed(body, plan.start(), plan.length(), done));
    }

    // ---- POST /stream/prefetch ----

    private ApiResponse prefetch(ApiRequest req, User user, String sessionToken) {
        JsonNode body = req.json();
        Track t = track(user, body.path("path").asText(""));
        Set<String> formats;
        if (body.path("formats").isArray()) {
            List<String> raw = new ArrayList<>();
            body.get("formats").forEach(n -> raw.add(n.asText("")));
            formats = parseFormats(raw);
        } else {
            formats = sessionFormats.getOrDefault(sessionToken, DEFAULT_FORMATS);
        }
        StreamVariant variant = choose(t, formats);
        if (!variant.transcoded()) {
            return ApiResponse.json(202, Dto.obj("prefetching", false, "reason", "native"));
        }
        if (prefetcher == null) {
            return ApiResponse.json(202, Dto.obj("prefetching", false, "reason", "disabled"));
        }
        boolean started = prefetcher.hint(user.id(), t, variant);
        return ApiResponse.json(202, Dto.obj("prefetching", started, "reason", started ? "queued" : "cached"));
    }
}
