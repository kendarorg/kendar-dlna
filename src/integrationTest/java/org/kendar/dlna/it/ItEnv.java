package org.kendar.dlna.it;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings of the integration tests. Every setting is a system property ({@code -Dkendar.it.xxx=...}, forwarded by
 * the Gradle task) or an environment variable; see docs/integration-tests.md.
 */
final class ItEnv {

    private ItEnv() {
    }

    static String get(String property, String env) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** Target of an already running server ({@code http://host:port}); null = start a container. */
    static String baseUrl() {
        String v = get("kendar.it.baseUrl", "KENDAR_IT_BASE_URL");
        return v == null ? null : v.replaceAll("/+$", "");
    }

    /** Explicit device description URL (external mode); otherwise SSDP unicast to the base URL's host. */
    static String descriptionUrl() {
        return get("kendar.it.descriptionUrl", "KENDAR_IT_DESCRIPTION_URL");
    }

    static String adminUser() {
        String v = get("kendar.it.adminUser", "KENDAR_IT_ADMIN_USER");
        return v == null ? "admin" : v;
    }

    /** External mode: the admin password; null = the admin tests are skipped. */
    static String adminPassword() {
        return get("kendar.it.adminPassword", "KENDAR_IT_ADMIN_PASSWORD");
    }

    /** An existing image to run instead of building the Dockerfile. */
    static String image() {
        return get("kendar.it.image", "KENDAR_IT_IMAGE");
    }

    /** The optional multicast discovery tests run only when this is true. */
    static boolean ssdp() {
        String v = get("kendar.it.ssdp", "KENDAR_IT_SSDP");
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1"));
    }

    /** At most this many items are streamed in the external mode (the generated library is small). */
    static int maxStreamedItems() {
        String v = get("kendar.it.maxItems", "KENDAR_IT_MAX_ITEMS");
        return v == null ? 150 : Integer.parseInt(v);
    }

    static Path projectDir() {
        String v = System.getProperty("kendar.it.projectDir");
        Path p = Path.of(v != null ? v : "").toAbsolutePath();
        if (!Files.isRegularFile(p.resolve("Dockerfile"))) {
            throw new IllegalStateException("Cannot find the project directory (Dockerfile) from " + p
                    + ": set -Dkendar.it.projectDir");
        }
        return p;
    }
}
