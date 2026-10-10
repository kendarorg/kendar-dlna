package org.kendar.dlna.adminweb;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build identification shown in the admin page (generated into {@code build-info.properties} by Gradle). */
public record BuildInfo(String version, String commit, String buildTime) {

    public static final BuildInfo UNKNOWN = new BuildInfo("dev", "unknown", "unknown");

    public static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in == null) {
                return UNKNOWN;
            }
            Properties p = new Properties();
            p.load(in);
            return new BuildInfo(p.getProperty("version", "dev"), clean(p.getProperty("commit")),
                    clean(p.getProperty("buildTime")));
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    private static String clean(String s) {
        return s == null || s.isBlank() || s.startsWith("@") ? "unknown" : s.strip();
    }
}
