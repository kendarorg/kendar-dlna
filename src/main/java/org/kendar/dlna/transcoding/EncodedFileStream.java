package org.kendar.dlna.transcoding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FLAC or ALAC (in an MP4 file) made by the external ffmpeg from the PCM of a decoder. ffmpeg writes a temporary file
 * (the MP4 muxer and the FLAC header need a seekable output); the constructor encodes everything and the stream then
 * reads that file, which is deleted on close. The result for a given source is deterministic, so a stream from any
 * offset equals the slice of the whole one.
 */
final class EncodedFileStream extends InputStream {

    private final InputStream in;
    private final Path tmp;

    EncodedFileStream(AudioDecoder decoder, TranscodeTarget target, long byteOffset) throws IOException {
        if (!Ffmpeg.available()) {
            decoder.close();
            throw new IOException("ffmpeg is not available (" + Ffmpeg.binary() + "): cannot encode " + target);
        }
        Path file = null;
        try {
            file = Files.createTempFile("kendar-encode-", target == TranscodeTarget.FLAC ? ".flac" : ".m4a");
            encode(decoder, target, file);
            InputStream s = Files.newInputStream(file);
            long skipped = 0;
            while (skipped < byteOffset) {
                long n = s.skip(byteOffset - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            this.in = s;
            this.tmp = file;
        } catch (IOException | RuntimeException e) {
            if (file != null) {
                Files.deleteIfExists(file);
            }
            throw e;
        } finally {
            decoder.close();
        }
    }

    private static void encode(AudioDecoder d, TranscodeTarget target, Path out) throws IOException {
        boolean wide = d.frameBytes() == 6;
        List<String> cmd = new java.util.ArrayList<>(List.of(Ffmpeg.binary(), "-nostdin", "-hide_banner", "-v", "error",
                "-y", "-f", wide ? "s24le" : "s16le", "-ar", Integer.toString(d.sampleRate()), "-ac", "2", "-i", "pipe:0",
                "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact"));
        if (target == TranscodeTarget.FLAC) {
            cmd.addAll(List.of("-c:a", "flac", "-sample_fmt", wide ? "s32" : "s16", "-f", "flac"));
        } else {
            cmd.addAll(List.of("-c:a", "alac", "-sample_fmt", wide ? "s32p" : "s16p", "-f", "ipod"));
        }
        cmd.add(out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try (OutputStream stdin = p.getOutputStream()) {
            d.seek(0);
            byte[] buf = new byte[64 * 1024 / d.frameBytes() * d.frameBytes()];
            int n;
            while ((n = d.read(buf, 0, buf.length)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("encode cancelled");
                }
                stdin.write(buf, 0, n);
            }
        } catch (IOException e) {
            p.destroyForcibly();
            throw e;
        }
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg did not finish encoding " + target);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("encode cancelled");
        }
        if (p.exitValue() != 0) {
            throw new IOException("ffmpeg failed to encode " + target + " (exit " + p.exitValue() + ")");
        }
    }

    @Override
    public int read() throws IOException {
        return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return in.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        try {
            in.close();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
