package org.kendar.dlna.dlna;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.kendar.dlna.artwork.Art;
import org.kendar.dlna.artwork.ArtworkService;

/**
 * Pure request logic of the art endpoint (no Jetty): {@code /art/t/<trackId>[/tn]} and
 * {@code /art/f/<folderId>[/tn]}. Handles GET/HEAD, ETag revalidation and the DLNA headers.
 */
public final class ArtEndpoint {

    public static final String PATH_PREFIX = "/art";

    static final String TN_FEATURES = "DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=00;DLNA.ORG_FLAGS=00D00000000000000000000000000000";
    static final String FULL_FEATURES = "DLNA.ORG_OP=00;DLNA.ORG_FLAGS=00D00000000000000000000000000000";
    static final String CACHE_CONTROL = "public, max-age=3600";

    /** {@code body} is null for HEAD, 304 and errors. */
    public record Response(int status, Map<String, String> headers, byte[] body) {
    }

    private final ArtworkService artwork;

    public ArtEndpoint(ArtworkService artwork) {
        this.artwork = artwork;
    }

    /** URL path (below the base) of a track's art. */
    public static String trackPath(String trackId, boolean thumbnail) {
        return PATH_PREFIX + "/t/" + trackId + (thumbnail ? "/tn" : "");
    }

    public static String folderPath(String folderId, boolean thumbnail) {
        return PATH_PREFIX + "/f/" + folderId + (thumbnail ? "/tn" : "");
    }

    /** @param pathInContext e.g. {@code /t/<id>/tn} (the context is mounted at {@value #PATH_PREFIX}) */
    public Response handle(String method, String pathInContext, String ifNoneMatch) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!m.equals("GET") && !m.equals("HEAD")) {
            return new Response(405, Map.of("Allow", "GET, HEAD"), null);
        }
        String[] parts = pathInContext == null ? new String[0] : pathInContext.replaceFirst("^/+", "").split("/");
        if (parts.length < 2 || parts.length > 3 || parts[1].isEmpty()
                || (parts.length == 3 && !parts[2].equals("tn"))) {
            return notFound();
        }
        boolean tn = parts.length == 3;
        Optional<Art> art = switch (parts[0]) {
            case "t" -> artwork.trackArt(parts[1], tn);
            case "f" -> artwork.folderArt(parts[1], tn);
            default -> Optional.empty();
        };
        if (art.isEmpty()) {
            return notFound();
        }
        Art a = art.get();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("ETag", a.etag());
        h.put("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(a.lastModified()).atOffset(ZoneOffset.UTC)));
        h.put("Cache-Control", CACHE_CONTROL);
        h.put("transferMode.dlna.org", "Interactive");
        h.put("contentFeatures.dlna.org", a.thumbnail() ? TN_FEATURES : FULL_FEATURES);
        if (matches(ifNoneMatch, a.etag())) {
            return new Response(304, h, null);
        }
        h.put("Content-Type", a.contentType());
        h.put("Content-Length", Integer.toString(a.data().length));
        return new Response(200, h, m.equals("GET") ? a.data() : null);
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.strip();
            if (c.equals("*") || c.equals(etag) || c.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    private static Response notFound() {
        return new Response(404, Map.of(), null);
    }
}
