package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The external {@code ffmpeg} binary, used for the formats that have no Java decoder (WMA, APE). The binary is
 * located once at startup ({@link #configure}); nothing else in the server needs it, so without it only those files
 * are unavailable.
 */
public final class Ffmpeg {

    /** The name looked up on the PATH when no location is configured. */
    public static final String DEFAULT_BINARY = "ffmpeg";

    private static volatile String binary = DEFAULT_BINARY;
    private static volatile String version;

    private Ffmpeg() {
    }

    /**
     * Sets the binary ({@code null} / blank = {@link #DEFAULT_BINARY}) and checks that it runs.
     *
     * @return the first line of {@code ffmpeg -version}, or null when the binary cannot be run
     */
    public static String configure(String path) {
        binary = path == null || path.isBlank() ? DEFAULT_BINARY : path.strip();
        version = detect(binary);
        return version;
    }

    public static String binary() {
        return binary;
    }

    /** True when the configured binary was found and ran at {@link #configure}. */
    public static boolean available() {
        return version != null;
    }

    public static String version() {
        return version;
    }

    private static String detect(String bin) {
        try {
            Process p = new ProcessBuilder(bin, "-hide_banner", "-version")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            String first = out.lines().findFirst().orElse("").strip();
            return first.isEmpty() ? bin : first;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
