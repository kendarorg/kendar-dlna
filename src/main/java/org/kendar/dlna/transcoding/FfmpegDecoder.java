package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes through the external {@code ffmpeg} (WMA, APE): the first audio stream as stereo s16le at the source
 * sample rate, read from the process output. The exact length is learnt once per file by decoding it to the end
 * (kept in a small cache keyed by path, size and modification time). Seeking restarts ffmpeg at the position.
 */
final class FfmpegDecoder extends FixedLengthDecoder {

    private record Info(int sampleRate, long frames) {
    }

    private static final int MAX_CACHED = 20_000;
    private static final Pattern HZ = Pattern.compile("(\\d{4,8}) Hz");
    private static final Map<String, Info> INFOS = new ConcurrentHashMap<>();

    private final Path file;
    private final int rate;
    private Process process;
    private InputStream out;

    static FfmpegDecoder open(Path file) throws IOException {
        if (!Ffmpeg.available()) {
            throw new IOException("ffmpeg is not available (" + Ffmpeg.binary() + "): cannot decode "
                    + file.getFileName());
        }
        Info info = info(file);
        if (info.frames() <= 0) {
            throw new IOException("no audio in " + file.getFileName());
        }
        return new FfmpegDecoder(file, info);
    }

    private FfmpegDecoder(Path file, Info info) {
        super(info.frames());
        this.file = file;
        this.rate = info.sampleRate();
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    protected void doSeek(long frame) throws IOException {
        stop();
        start(frame);
    }

    @Override
    protected int doRead(byte[] buf, int off, int len) throws IOException {
        if (out == null) {
            start(0);
        }
        return out.read(buf, off, len);
    }

    @Override
    public void close() {
        stop();
    }

    private void start(long frame) throws IOException {
        process = decodeProcess(file, frame, rate);
        out = process.getInputStream();
    }

    private void stop() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
            out = null;
        }
    }

    // ---- probing ----

    private static Info info(Path file) throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
        String key = file.toAbsolutePath() + "|" + a.size() + "|" + a.lastModifiedTime().toMillis();
        Info known = INFOS.get(key);
        if (known != null) {
            return known;
        }
        int rate = sampleRate(file);
        long bytes = 0;
        Process p = decodeProcess(file, 0, rate);
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bytes += n;
            }
        } finally {
            p.destroyForcibly();
        }
        Info info = new Info(rate, bytes / 4);
        if (INFOS.size() >= MAX_CACHED) {
            INFOS.clear();
        }
        INFOS.put(key, info);
        return info;
    }

    /**
     * The PCM rate delivered for a stream of {@code hz}: the source rate, except for DSD (megahertz rates) which is
     * decimated to 44.1 kHz (48 kHz for the 48 kHz family).
     */
    static int outputRate(int hz) {
        if (hz <= 192_000) {
            return hz;
        }
        return hz % 48_000 == 0 && hz % 44_100 != 0 ? 48_000 : 44_100;
    }

    /** The sample rate of the first audio stream, from the banner ffmpeg prints on stderr. */
    private static int sampleRate(Path file) throws IOException {
        Process p = new ProcessBuilder(Ffmpeg.binary(), "-nostdin", "-hide_banner", "-i", file.toString())
                .redirectErrorStream(true).start();
        p.getOutputStream().close();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.destroyForcibly();
        for (String line : text.split("\\R")) {
            if (line.contains("Audio:")) {
                Matcher m = HZ.matcher(line);
                if (m.find()) {
                    return outputRate(Integer.parseInt(m.group(1)));
                }
            }
        }
        throw new IOException("ffmpeg found no audio stream in " + file.getFileName());
    }

    private static Process decodeProcess(Path file, long frame, int rate) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Ffmpeg.binary(), "-nostdin", "-hide_banner", "-v", "error"));
        if (frame > 0) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.ROOT, "%.6f", frame / (double) rate));
        }
        cmd.addAll(List.of("-i", file.toString(), "-vn", "-map", "0:a:0", "-ac", "2", "-ar", Integer.toString(rate),
                "-f", "s16le", "-"));
        return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
}
