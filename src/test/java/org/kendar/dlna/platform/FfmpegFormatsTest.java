package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.ingestion.FfmpegTagReader;
import org.kendar.dlna.ingestion.LibraryScanner;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.transcoding.Ffmpeg;
import org.kendar.dlna.transcoding.SourceFormat;
import org.kendar.dlna.transcoding.Transcoder;

/** Runs against a real ffmpeg when one is installed; skipped otherwise. */
class FfmpegFormatsTest {

    @TempDir
    Path tmp;

    private Path wma() throws Exception {
        Path f = tmp.resolve("tone.wma");
        Process p = new ProcessBuilder(Ffmpeg.binary(), "-y", "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=2:sample_rate=44100", "-metadata", "title=Tone", "-metadata",
                "artist=Sine", "-metadata", "album=Waves", "-metadata", "track=3/9", "-metadata", "date=1999",
                f.toString()).redirectErrorStream(true).start();
        try (InputStream in = p.getInputStream()) {
            in.readAllBytes();
        }
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assumeTrue(p.exitValue() == 0, "ffmpeg cannot encode wma here");
        return f;
    }

    @Test
    void wmaIsReadDecodedWithAnExactLengthAndSeeks() throws Exception {
        assumeTrue(Ffmpeg.configure(null) != null, "no ffmpeg on the PATH");
        Path file = wma();
        FfmpegTagReader reader = new FfmpegTagReader(Ffmpeg.binary(), true, "");
        var meta = reader.read(file, Format.WMA);
        assertThat(meta.tags().title()).isEqualTo("Tone");
        assertThat(meta.tags().artist()).isEqualTo("Sine");
        assertThat(meta.tags().album()).isEqualTo("Waves");
        assertThat(meta.tags().trackNo()).isEqualTo(3);
        assertThat(meta.tags().year()).isEqualTo(1999);
        assertThat(meta.sampleRate()).isEqualTo(44100);
        assertThat(meta.durationMillis()).isBetween(1900L, 2200L);

        Transcoder t = new Transcoder(null);
        Transcoder.SourceInfo info = t.probe(SourceFormat.WMA, file);
        assertThat(info.sampleRate()).isEqualTo(44100);
        assertThat(info.totalSamples()).isBetween(86_000L, 90_500L);

        long total = 0;
        try (InputStream in = t.openLpcm(SourceFormat.WMA, file, 0)) {
            total = in.readAllBytes().length;
        }
        assertThat(total).isEqualTo(Transcoder.lpcmLength(info.totalSamples()));
        long skip = 4L * 44100;   // one second in
        try (InputStream in = t.openLpcm(SourceFormat.WMA, file, skip)) {
            assertThat((long) in.readAllBytes().length).isEqualTo(total - skip);
        }
    }

    /** A silent stereo DSD64 file with two blocks per channel, built by hand (ffmpeg only decodes DSD). */
    private Path dsf() throws Exception {
        int block = 4096;
        int blocks = 2;
        long dataBytes = (long) block * 2 * blocks;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate((int) (28 + 52 + 12 + dataBytes))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("DSD ".getBytes()).putLong(28).putLong(b.capacity()).putLong(0);
        b.put("fmt ".getBytes()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2).putInt(2_822_400).putInt(1)
                .putLong((long) block * 8 * blocks).putInt(block).putInt(0);
        b.put("data".getBytes()).putLong(12 + dataBytes);
        while (b.hasRemaining()) {
            b.put((byte) 0x69);   // the DSD silence pattern
        }
        return java.nio.file.Files.write(tmp.resolve("silence.dsf"), b.array());
    }

    @Test
    void dsdIsDecimatedTo44100() throws Exception {
        assumeTrue(Ffmpeg.configure(null) != null, "no ffmpeg on the PATH");
        Path file = dsf();
        var meta = new FfmpegTagReader(Ffmpeg.binary(), true, "").read(file, Format.DSD);
        assertThat(meta.sampleRate()).isEqualTo(44100);
        Transcoder.SourceInfo info = new Transcoder(null).probe(SourceFormat.DSD, file);
        assertThat(info.sampleRate()).isEqualTo(44100);
        assertThat(info.totalSamples()).isBetween(900L, 1100L);
    }

    @Test
    void scanListsWmaAsUnsupportedWithTheReasonWhenFfmpegIsMissing() throws Exception {
        Path music = java.nio.file.Files.createDirectories(tmp.resolve("music"));
        java.nio.file.Files.write(music.resolve("a.wma"), new byte[] { 1, 2, 3 });
        java.nio.file.Files.write(music.resolve("b.ape"), new byte[] { 1, 2, 3 });
        TagReader off = new FfmpegTagReader("no-such-ffmpeg", false, "ffmpeg not found at 'no-such-ffmpeg'");
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("m", music);
        Snapshot s = new LibraryScanner(off).scan(roots, 1);
        assertThat(s.tracks()).isEmpty();
        assertThat(s.unsupported()).hasSize(2).allSatisfy(u -> assertThat(u.reason()).contains("ffmpeg"));
    }
}
