package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.ingestion.LibraryScanner;
import org.kendar.dlna.ingestion.ScanProgress;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.ingestion.TrackMeta;
import org.kendar.dlna.shared.RootRef;
import org.kendar.dlna.testutil.AudioFiles;

/** AAC, ALAC and raw ADTS through the production tag reader chain (jaudiotagger + decoder probe). */
class Mp4AudioIngestionTest {

    @TempDir
    Path dir;

    TagReader reader;

    @BeforeEach
    void wiring() {
        reader = TranscodingWiring.create(
                new AppConfig.Transcode(null, null, null, null, null, null, null, null, null), dir.resolve("data"))
                .tagReader();
    }

    @Test
    void aacM4aGivesTagsArtExactLengthAndDuration() throws Exception {
        Path f = AudioFiles.fixture("sweep.m4a", dir.resolve("a.m4a"));
        assertThat(reader.supports(Format.AAC)).isTrue();
        TrackMeta m = reader.read(f, Format.AAC);
        assertThat(m.format()).isEqualTo(Format.AAC);
        assertThat(m.sampleCount()).isEqualTo(88200);
        assertThat(m.sampleRate()).isEqualTo(44100);
        assertThat(m.durationMillis()).isEqualTo(2000);
        assertThat(m.channels()).isEqualTo(2);
        assertThat(m.bitrateKbps()).isBetween(100, 140);
        assertThat(m.embeddedArt()).isTrue();
        assertThat(m.tags().title()).isEqualTo("AAC Title");
        assertThat(m.tags().artist()).isEqualTo("AAC Artist");
        assertThat(m.tags().album()).isEqualTo("AAC Album");
        assertThat(m.tags().albumArtist()).isEqualTo("AAC AlbumArtist");
        assertThat(m.tags().composer()).isEqualTo("AAC Composer");
        assertThat(m.tags().genre()).isEqualTo("Rock");
        assertThat(m.tags().year()).isEqualTo(2020);
        assertThat(m.tags().trackNo()).isEqualTo(3);
        assertThat(m.tags().discNo()).isEqualTo(1);
        assertThat(m.tags().discTotal()).isEqualTo(2);
    }

    @Test
    void anM4aWithAppleLosslessIsRecognisedByItsCodecNotItsExtension() throws Exception {
        Path f = AudioFiles.fixture("sweep-alac.m4a", dir.resolve("b.m4a"));
        TrackMeta m = reader.read(f, Format.AAC);   // the scanner only knows the extension
        assertThat(m.format()).isEqualTo(Format.ALAC);
        assertThat(m.sampleCount()).isEqualTo(88200);
        assertThat(m.durationMillis()).isEqualTo(2000);
        assertThat(m.tags().title()).isEqualTo("ALAC Title");
        assertThat(m.tags().composer()).isEqualTo("ALAC Composer");
        assertThat(m.embeddedArt()).isTrue();
        // and reading it again as ALAC (the stored format, as the tag editor does) gives the same
        assertThat(reader.read(f, Format.ALAC).format()).isEqualTo(Format.ALAC);
    }

    @Test
    void highResolutionAlacKeepsItsRateAndSampleCount() throws Exception {
        Path f = AudioFiles.fixture("hires-alac.m4a", dir.resolve("hi.m4a"));
        TrackMeta m = reader.read(f, Format.AAC);
        assertThat(m.format()).isEqualTo(Format.ALAC);
        assertThat(m.sampleRate()).isEqualTo(96000);
        assertThat(m.sampleCount()).isEqualTo(14400);
        assertThat(m.durationMillis()).isEqualTo(150);
    }

    @Test
    void rawAdtsHasNoTagsButExactLength() throws Exception {
        Path f = AudioFiles.fixture("transient.aac", dir.resolve("raw.aac"));
        TrackMeta m = reader.read(f, Format.ADTS);
        assertThat(m.tags().title()).isNull();
        assertThat(m.sampleRate()).isEqualTo(44100);
        assertThat(m.sampleCount()).isEqualTo(19456);
        assertThat(m.durationMillis()).isEqualTo(Math.round(19456 * 1000.0 / 44100));
        assertThat(m.embeddedArt()).isFalse();
        assertThat(m.bitrateKbps()).as("computed from size and duration").isBetween(60, 120);
    }

    @Test
    void mp4ExtensionAndMultichannelAndMonoAreAccepted() throws Exception {
        assertThat(reader.read(AudioFiles.fixture("sweep.mp4", dir.resolve("v.mp4")), Format.AAC).sampleCount())
                .isEqualTo(88200);
        assertThat(reader.read(AudioFiles.fixture("surround.m4a", dir.resolve("s.m4a")), Format.AAC).sampleCount())
                .isEqualTo(48000);
        assertThat(reader.read(AudioFiles.fixture("mono.m4a", dir.resolve("m.m4a")), Format.AAC).sampleCount())
                .isEqualTo(44100);
    }

    @Test
    void heAacIsReportedAsUnsupportedWithTheReason() throws Exception {
        Path f = AudioFiles.fixture("he-aac.m4a", dir.resolve("he.m4a"));
        assertThatThrownBy(() -> reader.read(f, Format.AAC)).isInstanceOfSatisfying(TagReader.TagReadException.class,
                e -> {
                    assertThat(e.unsupported()).isTrue();
                    assertThat(e.getMessage()).contains("HE-AAC").contains("AAC-LC");
                });
    }

    @Test
    void aBrokenM4aIsUnreadableNotUnsupported() throws Exception {
        Path f = dir.resolve("bad.m4a");
        Files.writeString(f, "not an mp4");
        assertThatThrownBy(() -> reader.read(f, Format.AAC)).isInstanceOfSatisfying(TagReader.TagReadException.class,
                e -> assertThat(e.unsupported()).isFalse());
        // a cut-off real file
        byte[] real = Files.readAllBytes(AudioFiles.fixture("sweep.m4a", dir.resolve("x.m4a")));
        Path cut = dir.resolve("cut.m4a");
        Files.write(cut, java.util.Arrays.copyOf(real, 3000));
        assertThatThrownBy(() -> reader.read(cut, Format.AAC)).isInstanceOf(TagReader.TagReadException.class);
    }

    @Test
    void theScannerClassifiesMixedFilesAndDsdStaysUnsupported() throws Exception {
        Path music = Files.createDirectories(dir.resolve("music/Album"));
        AudioFiles.fixture("sweep.m4a", music.resolve("1 aac.m4a"));
        AudioFiles.fixture("sweep-alac.m4a", music.resolve("2 alac.m4a"));
        AudioFiles.fixture("transient.aac", music.resolve("3 raw.aac"));
        AudioFiles.fixture("sweep.mp4", music.resolve("4 plain.mp4"));
        AudioFiles.fixture("he-aac.m4a", music.resolve("5 he.m4a"));
        Files.write(music.resolve("6 broken.m4a"), new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'});
        Files.writeString(music.resolve("7 disc.dsf"), "DSD ");
        Files.writeString(music.resolve("8 disc.dff"), "FRM8");
        Files.writeString(music.resolve("9 book.m4b"), "x");
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("main", dir.resolve("music"));

        LibraryScanner scanner = new LibraryScanner(reader);
        Snapshot s = scanner.scan(roots, 1);

        assertThat(s.tracks()).extracting(t -> t.ref().name(), Track::format).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("1 aac.m4a", Format.AAC),
                org.assertj.core.groups.Tuple.tuple("2 alac.m4a", Format.ALAC),
                org.assertj.core.groups.Tuple.tuple("3 raw.aac", Format.ADTS),
                org.assertj.core.groups.Tuple.tuple("4 plain.mp4", Format.AAC));
        Map<String, String> reasons = new LinkedHashMap<>();
        s.unsupported().forEach(u -> reasons.put(u.ref().name(), u.reason()));
        assertThat(reasons.keySet()).containsExactlyInAnyOrder("5 he.m4a", "6 broken.m4a", "7 disc.dsf",
                "8 disc.dff", "9 book.m4b");
        assertThat(reasons.get("5 he.m4a")).contains("HE-AAC");
        assertThat(reasons.get("6 broken.m4a")).startsWith("unreadable");
        assertThat(reasons.get("7 disc.dsf")).isNotBlank();
        assertThat(reasons.get("8 disc.dff")).isNotBlank();

        Track alac = s.track(RootRef.parse("main/Album/2 alac.m4a")).orElseThrow();
        assertThat(alac.sampleCount()).isEqualTo(88200);
        assertThat(alac.embeddedArt()).isTrue();
        assertThat(alac.tags().title()).isEqualTo("ALAC Title");

        // a second pass reuses the unchanged tracks, including the ALAC one whose extension says AAC
        ScanProgress progress = new ScanProgress();
        Snapshot again = scanner.rescan(roots, null, s, null, progress);
        assertThat(progress.tracksReused()).isEqualTo(4);
        assertThat(again.track(RootRef.parse("main/Album/2 alac.m4a")).orElseThrow().format())
                .isEqualTo(Format.ALAC);
    }

    @Test
    void heAacInTheUnsupportedListDoesNotCountAsAScanError() throws Exception {
        Path music = Files.createDirectories(dir.resolve("music"));
        AudioFiles.fixture("he-aac.m4a", music.resolve("he.m4a"));
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("main", music);
        ScanProgress progress = new ScanProgress();
        Snapshot s = new LibraryScanner(reader).rescan(roots, null,
                new LibraryScanner(reader).scan(roots, 1), null, progress);
        assertThat(s.unsupported()).hasSize(1);
        assertThat(progress.errorCount()).isZero();
    }
}
