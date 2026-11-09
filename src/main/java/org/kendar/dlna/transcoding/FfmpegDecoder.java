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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final Map<String, Object> PROBE_LOCKS = new ConcurrentHashMap<>();

    /** An ffmpeg that produces nothing for this long is killed, so its reader fails instead of waiting forever. */
    static final long STALL_SECONDS = 30;
    private static final ScheduledExecutorService WATCHDOG = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ffmpeg-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** Kills the process unless closed within {@link #STALL_SECONDS}; one guard per blocking read. */
    private static final class Guard implements AutoCloseable {
        private final AtomicBoolean fired = new AtomicBoolean();
        private final ScheduledFuture<?> task;

        Guard(Process p) {
            task = WATCHDOG.schedule(() -> {
                fired.set(true);
                p.destroyForcibly();
            }, STALL_SECONDS, TimeUnit.SECONDS);
        }

        void check() throws IOException {
            if (fired.get()) {
                throw new IOException("ffmpeg stalled: no output for " + STALL_SECONDS + " s");
            }
        }

        @Override
        public void close() {
            task.cancel(false);
        }
    }

    /** One read of the process output that cannot block longer than the stall limit. */
    private static int guardedRead(Process p, InputStream in, byte[] buf, int off, int len) throws IOException {
        try (Guard g = new Guard(p)) {
            int n;
            try {
                n = in.read(buf, off, len);
            } catch (IOException e) {
                g.check();
                throw e;
            }
            g.check();
            return n;
        }
    }

    private final Path file;
    private final int rate;
    private final boolean wide;
    private Process process;
    private InputStream out;

    static FfmpegDecoder open(Path file) throws IOException {
        return open(file, false);
    }

    /** @param wide deliver stereo s24le (6-byte frames) instead of s16le */
    static FfmpegDecoder open(Path file, boolean wide) throws IOException {
        if (!Ffmpeg.available()) {
            throw new IOException("ffmpeg is not available (" + Ffmpeg.binary() + "): cannot decode "
                    + file.getFileName());
        }
        Info info = info(file);
        if (info.frames() <= 0) {
            throw new IOException("no audio in " + file.getFileName());
        }
        return new FfmpegDecoder(file, info, wide);
    }

    private FfmpegDecoder(Path file, Info info, boolean wide) {
        super(info.frames(), wide ? 6 : 4);
        this.wide = wide;
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
        return guardedRead(process, out, buf, off, len);
    }

    @Override
    public void close() {
        stop();
    }

    private void start(long frame) throws IOException {
        process = decodeProcess(file, frame, rate, wide);
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
        // one probe per file: concurrent opens (prefetch, retries, other clients) wait for it
        synchronized (PROBE_LOCKS.computeIfAbsent(key, k -> new Object())) {
            known = INFOS.get(key);
            if (known != null) {
                return known;
            }
            return probe(file, key);
        }
    }

    private static Info probe(Path file, String key) throws IOException {
        int rate = sampleRate(file);
        long bytes = 0;
        Process p = decodeProcess(file, 0, rate, false);
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = guardedRead(p, in, buf, 0, buf.length)) >= 0) {
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

    static int outputRate(int hz) {
        return Ffmpeg.outputRate(hz);
    }

    /** The sample rate of the first audio stream, from the banner ffmpeg prints on stderr. */
    private static int sampleRate(Path file) throws IOException {
        Process p = new ProcessBuilder(Ffmpeg.binary(), "-nostdin", "-hide_banner", "-i", file.toString())
                .redirectErrorStream(true).start();
        p.getOutputStream().close();
        String text;
        try (Guard g = new Guard(p)) {
            try {
                text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                g.check();
                throw e;
            }
            g.check();
        } finally {
            p.destroyForcibly();
        }
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

    private static Process decodeProcess(Path file, long frame, int rate, boolean wide) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Ffmpeg.binary(), "-nostdin", "-hide_banner", "-v", "error"));
        if (frame > 0) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.ROOT, "%.6f", frame / (double) rate));
        }
        cmd.addAll(List.of("-i", file.toString(), "-vn", "-map", "0:a:0", "-ac", "2", "-ar", Integer.toString(rate),
                "-f", wide ? "s24le" : "s16le", "-"));
        return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
}
