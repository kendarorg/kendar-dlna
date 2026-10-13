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

    private static volatile int dsdPcmRate = 44_100;
    private static volatile boolean dsdDop;

    private Ffmpeg() {
    }

    /** DSD to PCM: the 44.1 kHz-family rate DSD is decimated to (44100, 88200 or 176400; the 48 kHz family scales with it). */
    public static void configureDsd(int pcmRate, boolean dop) {
        dsdPcmRate = pcmRate;
        dsdDop = dop;
    }

    public static int dsdPcmRate() {
        return dsdPcmRate;
    }

    /** Whether DSD is delivered as DoP (DSD over PCM, 24-bit) instead of decimated PCM. */
    public static boolean dsdDop() {
        return dsdDop;
    }

    /**
     * The PCM rate delivered for a stream of {@code hz}: the source rate, except for DSD (megahertz rates), which is
     * decimated to {@link #dsdPcmRate()} (scaled to the 48 kHz family for multiples of 48 kHz).
     */
    public static int outputRate(int hz) {
        if (hz <= 192_000) {
            return hz;
        }
        boolean family48 = hz % 48_000 == 0 && hz % 44_100 != 0;
        return family48 ? (int) (dsdPcmRate * 48_000L / 44_100) : dsdPcmRate;
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
