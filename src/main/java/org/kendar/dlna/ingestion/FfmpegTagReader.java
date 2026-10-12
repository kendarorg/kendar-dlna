package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.TrackTags;

/**
 * Tags and properties of WMA and APE files, read with one run of the external {@code ffmpeg}
 * ({@code -i file -f ffmetadata -}: the tags on stdout, duration / sample rate / bitrate in the banner on stderr).
 * The exact sample count is measured later by decoding (see {@code ProbingTagReader}). When ffmpeg is missing
 * (or transcoding is off, which these formats need) the files are listed as unsupported with the reason.
 */
public final class FfmpegTagReader implements TagReader {

    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Pattern AUDIO = Pattern.compile("Audio: .*?(\\d{4,8}) Hz,([^,]+)");
    private static final Pattern BITRATE = Pattern.compile("(\\d+) kb/s");
    private static final Pattern YEAR = Pattern.compile("(?<![\\p{N}])(\\d{4})(?![\\p{N}])");

    private final String binary;
    private final boolean usable;
    private final String unavailableReason;

    /**
     * @param binary            the ffmpeg to run
     * @param usable            whether the formats can be served at all (ffmpeg found and transcoding on)
     * @param unavailableReason shown in the unsupported-files list when not usable
     */
    public FfmpegTagReader(String binary, boolean usable, String unavailableReason) {
        this.binary = binary;
        this.usable = usable;
        this.unavailableReason = unavailableReason;
    }

    @Override
    public boolean supports(Format format) {
        return format.needsFfmpeg() && usable;
    }

    @Override
    public boolean owns(Format format) {
        return format.needsFfmpeg();
    }

    @Override
    public String unsupportedReason(Format format) {
        return format + " needs ffmpeg: " + unavailableReason;
    }

    @Override
    public TrackMeta read(Path file, Format format) throws TagReadException {
        String stdout;
        String stderr;
        try {
            Process p = new ProcessBuilder(binary, "-nostdin", "-hide_banner", "-i", file.toString(), "-f",
                    "ffmetadata", "-").start();
            p.getOutputStream().close();
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new TagReadException("ffmpeg timed out reading " + file.getFileName(), null);
            }
            stderr = err.get(5, TimeUnit.SECONDS);
            if (p.exitValue() != 0) {
                throw new TagReadException("Cannot read " + file.getFileName() + ": " + lastLine(stderr), null);
            }
        } catch (IOException e) {
            throw new TagReadException("Cannot run ffmpeg (" + binary + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TagReadException("Interrupted while reading " + file.getFileName(), e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new TagReadException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        Matcher audio = AUDIO.matcher(stderr);
        if (!audio.find()) {
            throw new TagReadException("no audio stream in " + file.getFileName(), null, true);
        }
        int rate = outputRate(Integer.parseInt(audio.group(1)));
        int channels = channels(audio.group(2));
        long millis = 0;
        Matcher d = DURATION.matcher(stderr);
        if (d.find()) {
            millis = Math.round((Long.parseLong(d.group(1)) * 3600 + Long.parseLong(d.group(2)) * 60
                    + Double.parseDouble(d.group(3))) * 1000);
        }
        int bitrate = 0;
        Matcher b = BITRATE.matcher(stderr.substring(Math.max(0, stderr.indexOf("Duration:"))));
        if (b.find()) {
            bitrate = Integer.parseInt(b.group(1));
        }
        return new TrackMeta(tags(stdout), millis, bitrate, rate, channels, false);
    }

    /** Same mapping as the decoder: DSD (megahertz rates) is decimated to 44.1 kHz, or 48 kHz for that family. */
    private static int outputRate(int hz) {
        if (hz <= 192_000) {
            return hz;
        }
        return hz % 48_000 == 0 && hz % 44_100 != 0 ? 48_000 : 44_100;
    }

    private static String lastLine(String text) {
        List<String> lines = text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        return lines.isEmpty() ? "unreadable" : lines.get(lines.size() - 1);
    }

    private static int channels(String layout) {
        String l = layout.strip().toLowerCase(Locale.ROOT);
        if (l.startsWith("mono")) {
            return 1;
        }
        if (l.startsWith("stereo")) {
            return 2;
        }
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)").matcher(l);
        if (m.find()) {
            return Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2));
        }
        Matcher n = Pattern.compile("(\\d+) channels").matcher(l);
        return n.find() ? Integer.parseInt(n.group(1)) : 2;
    }

    /** Parses ffmetadata ({@code key=value} lines, {@code \=} {@code \;} {@code \#} {@code \\} escapes). */
    static TrackTags tags(String ffmetadata) {
        Map<String, String> m = new HashMap<>();
        for (String line : ffmetadata.split("\\R")) {
            if (line.startsWith("[")) {
                break;   // chapters / streams
            }
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            int eq = indexOfUnescaped(line, '=');
            if (eq <= 0) {
                continue;
            }
            m.putIfAbsent(unescape(line.substring(0, eq)).toLowerCase(Locale.ROOT), unescape(line.substring(eq + 1)).strip());
        }
        Integer year = null;
        String date = first(m, "date", "year", "originaldate");
        if (date != null) {
            Matcher y = YEAR.matcher(date);
            if (y.find()) {
                year = Integer.valueOf(y.group(1));
            }
        }
        List<String> artists = split(first(m, "artist", "author"));
        return new TrackTags(first(m, "title"), first(m, "artist", "author"), first(m, "album"),
                first(m, "album_artist", "albumartist", "album artist"), first(m, "genre"), year,
                number(first(m, "track", "tracknumber")), number(first(m, "disc", "discnumber")),
                total(first(m, "disc", "discnumber")), artists, List.of(), List.of(), first(m, "composer"),
                List.of(), null);
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s != null && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    private static String first(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** "3" or "3/12" -> 3. */
    private static Integer number(String s) {
        if (s == null) {
            return null;
        }
        try {
            int n = Integer.parseInt(s.split("/")[0].strip());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "1/2" -> 2. */
    private static Integer total(String s) {
        if (s == null || !s.contains("/")) {
            return null;
        }
        try {
            int n = Integer.parseInt(s.split("/")[1].strip());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indexOfUnescaped(String s, char c) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++;
            } else if (s.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
