package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;

/** The static web UI, served from the classpath folder {@code /web}. Path traversal is impossible by construction. */
public final class StaticResources {

    /** A resolved file. */
    public record Asset(byte[] content, String contentType) {
    }

    private static final String BASE = "/web/";

    private StaticResources() {
    }

    /** @param pathInContext e.g. {@code /}, {@code /js/app.js} */
    public static Optional<Asset> find(String pathInContext) {
        String p = pathInContext == null ? "" : pathInContext;
        if (p.isEmpty() || p.equals("/")) {
            p = "/index.html";
        }
        if (!isSafe(p)) {
            return Optional.empty();
        }
        try (InputStream in = StaticResources.class.getResourceAsStream(BASE + p.substring(1))) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new Asset(in.readAllBytes(), contentType(p)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static boolean isSafe(String p) {
        if (!p.startsWith("/") || p.contains("\\") || p.contains("\0") || p.contains("//")) {
            return false;
        }
        for (String seg : p.split("/")) {
            if (seg.equals("..") || seg.equals(".")) {
                return false;
            }
        }
        return !p.endsWith("/");
    }

    static String contentType(String path) {
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "webmanifest" -> "application/manifest+json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }
}
