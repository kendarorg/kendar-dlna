package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.ingestion.TagReader;
import org.kendar.dlna.ingestion.TrackMeta;
import org.kendar.dlna.testutil.AudioFiles;

/** The transcode and clients configuration sections, SoundFont lookup and MIDI availability. */
class TranscodeConfigTest {

    private static final UnaryOperator<String> NONE = k -> null;

    @TempDir
    Path dir;

    private AppConfig load(String json) throws Exception {
        Files.writeString(dir.resolve("config.json"), json);
        return ConfigLoader.load(dir, NONE);
    }

    @Test
    void defaultsMatchThePlan() throws Exception {
        AppConfig.Transcode t = load("{}").transcode();
        assertThat(t.enabled()).isTrue();
        assertThat(t.bitrateKbps()).isEqualTo(320);
        assertThat(t.prefetch()).isTrue();
        assertThat(t.prefetchJobs()).isEqualTo(1);
        assertThat(t.cacheMaxMb()).isEqualTo(AppConfig.DEFAULT_CACHE_MB);
        assertThat(t.cacheTtlHours()).isEqualTo(AppConfig.DEFAULT_CACHE_TTL_HOURS);
        assertThat(t.soundFontPath()).isNull();
        assertThat(t.midiSilenceThreshold()).isEqualTo(16);
        assertThat(t.midiMaxTailSeconds()).isEqualTo(10);
        assertThat(load("{}").clients().builtins()).isTrue();
    }

    @Test
    void valuesAreReadAndTheTemplateRoundTrips() throws Exception {
        AppConfig cfg = load("""
                {"transcode":{"enabled":false,"bitrateKbps":256,"prefetch":false,"prefetchJobs":2,"cacheMaxMb":64,
                 "cacheTtlHours":3,"soundFontPath":" /sf/gm.sf2 ","midiSilenceThreshold":32,"midiMaxTailSeconds":4},
                 "clients":{"builtins":false,"profiles":[{"id":"x","match":{"userAgentRegex":"X"},
                 "nativeFormats":["FLAC"],"transcodeTarget":"LPCM"}]}}""");
        AppConfig.Transcode t = cfg.transcode();
        assertThat(t.enabled()).isFalse();
        assertThat(t.bitrateKbps()).isEqualTo(256);
        assertThat(t.prefetch()).isFalse();
        assertThat(t.prefetchJobs()).isEqualTo(2);
        assertThat(t.soundFontPath()).isEqualTo("/sf/gm.sf2");
        assertThat(cfg.clients().profiles()).hasSize(1);
        // the file template written for a missing config reads back identically
        Path other = Files.createDirectories(dir.resolve("fresh"));
        AppConfig written = ConfigLoader.load(other, NONE);
        assertThat(ConfigLoader.load(other, NONE)).isEqualTo(written);
        assertThat(Files.readString(other.resolve("config.json"))).contains("\"transcode\"").contains("bitrateKbps");
    }


    @Test
    void outputRulesAndPassthroughAreReadAndValidated() throws Exception {
        AppConfig.Transcode t = load("""
                {"transcode":{"ffmpegPath":"ff","output":{"maxSampleRate":96000,"bitDepth":24,"dsdPcmRate":176400,
                 "dsdMode":"DoP"},"passthrough":["WAV","mp3"],
                 "rules":[{"formats":["flac","dsd"],"profiles":["tv"],"minSampleRate":96000,"action":"WAV",
                 "outputMaxSampleRate":48000,"outputBitDepth":16,"hideOriginal":true}]}}""").transcode();
        assertThat(t.output().maxSampleRate()).isEqualTo(96000);
        assertThat(t.output().bitDepth()).isEqualTo(24);
        assertThat(t.output().dsdPcmRate()).isEqualTo(176400);
        assertThat(t.output().dop()).isTrue();
        assertThat(t.passthrough()).containsExactly("wav", "mp3");
        assertThat(t.rules()).singleElement().satisfies(r -> {
            assertThat(r.formats()).containsExactly("FLAC", "DSD");
            assertThat(r.action()).isEqualTo("wav");
            assertThat(r.outputMaxSampleRate()).isEqualTo(48000);
            assertThat(r.hideOriginal()).isTrue();
        });
        assertThat(Main.transcodeRules(t).match(
                new org.kendar.dlna.catalog.Track(org.kendar.dlna.shared.RootRef.parse("m/a.flac"), 1, 1, Format.FLAC,
                        null, 1, 1, 96000, 2, false),
                new org.kendar.dlna.clients.ClientProfile("tv", "", org.kendar.dlna.clients.ProfileMatch.userAgent("x"),
                        java.util.Set.of(), null, null, 0, java.util.Set.of()))).isPresent();
        AppConfig defaults = load("{}");
        assertThat(defaults.transcode().output().maxSampleRate()).isZero();
        assertThat(defaults.transcode().output().bitDepth()).isEqualTo(16);
        assertThat(defaults.transcode().output().dsdPcmRate()).isEqualTo(44100);
        assertThat(defaults.transcode().output().dop()).isFalse();
        assertThat(defaults.transcode().passthrough()).containsExactly("wav");
        assertThat(defaults.transcode().rules()).isEmpty();
        // relative ffmpeg path and rules survive path resolution
        assertThat(load("{\"transcode\":{\"rules\":[{\"action\":\"native\"}]}}").transcode().rules()).hasSize(1);
    }

    @Test
    void invalidOutputRulesAndPassthroughFailFast() {
        assertThatThrownBy(() -> load("{\"transcode\":{\"output\":{\"bitDepth\":20}}}"))
                .hasMessageContaining("transcode.output.bitDepth");
        assertThatThrownBy(() -> load("{\"transcode\":{\"output\":{\"dsdPcmRate\":48000}}}"))
                .hasMessageContaining("dsdPcmRate");
        assertThatThrownBy(() -> load("{\"transcode\":{\"output\":{\"dsdMode\":\"x\"}}}"))
                .hasMessageContaining("dsdMode");
        assertThatThrownBy(() -> load("{\"transcode\":{\"output\":{\"maxSampleRate\":100}}}"))
                .hasMessageContaining("maxSampleRate");
        assertThatThrownBy(() -> load("{\"transcode\":{\"passthrough\":[\"flac\"]}}"))
                .hasMessageContaining("passthrough");
        assertThatThrownBy(() -> load("{\"transcode\":{\"rules\":[{\"action\":\"ogg\"}]}}"))
                .hasMessageContaining("rules[1].action");
        assertThatThrownBy(() -> load("{\"transcode\":{\"rules\":[{\"action\":\"mp3\",\"formats\":[\"xyz\"]}]}}"))
                .hasMessageContaining("unknown format");
        assertThatThrownBy(() -> load("{\"transcode\":{\"rules\":[{\"action\":\"mp3\",\"bitrateKbps\":300}]}}"))
                .hasMessageContaining("bitrateKbps");
        assertThatThrownBy(() -> load("{\"transcode\":{\"rules\":[{\"action\":\"wav\",\"outputBitDepth\":8}]}}"))
                .hasMessageContaining("outputBitDepth");
    }
    @Test
    void invalidValuesFailFastWithAClearMessage() {
        assertThatThrownBy(() -> load("{\"transcode\":{\"bitrateKbps\":300}}"))
                .isInstanceOf(ConfigLoader.ConfigException.class).hasMessageContaining("transcode.bitrateKbps");
        assertThatThrownBy(() -> load("{\"transcode\":{\"prefetchJobs\":5}}"))
                .hasMessageContaining("transcode.prefetchJobs");
        assertThatThrownBy(() -> load("{\"transcode\":{\"cacheMaxMb\":1}}"))
                .hasMessageContaining("transcode.cacheMaxMb");
        assertThatThrownBy(() -> load("{\"transcode\":{\"cacheTtlHours\":0}}"))
                .hasMessageContaining("transcode.cacheTtlHours");
        assertThatThrownBy(() -> load("{\"transcode\":{\"midiSilenceThreshold\":-1}}"))
                .hasMessageContaining("transcode.midiSilenceThreshold");
        assertThatThrownBy(() -> load("{\"transcode\":{\"midiMaxTailSeconds\":500}}"))
                .hasMessageContaining("transcode.midiMaxTailSeconds");
        assertThatThrownBy(() -> load("{\"clients\":{\"profiles\":[{\"id\":\"x\",\"match\":"
                + "{\"userAgentRegex\":\"(\"}}]}}")).isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("clients:").hasMessageContaining("userAgentRegex");
        assertThatThrownBy(() -> load("{\"clients\":{\"profiles\":[{\"id\":\"x\",\"match\":"
                + "{\"userAgentRegex\":\"x\"},\"transcodeTarget\":\"OGG\"}]}}"))
                .isInstanceOf(ConfigLoader.ConfigException.class).hasMessageContaining("config.json");
    }

    @Test
    void clientsCanLiveInASeparateFile() throws Exception {
        Files.writeString(dir.resolve("clients.json"),
                "{\"profiles\":[{\"id\":\"tv\",\"match\":{\"userAgentRegex\":\"TV\"}}]}");
        AppConfig cfg = load("{\"clients\":{\"profilesFile\":\"clients.json\"}}");
        assertThat(cfg.clients().profilesFile()).isEqualTo("clients.json");
        assertThatThrownBy(() -> load("{\"clients\":{\"profilesFile\":\"missing.json\"}}"))
                .isInstanceOf(ConfigLoader.ConfigException.class).hasMessageContaining("missing.json");
    }

    @Test
    void soundFontComesFromTheConfiguredPathElseTheBundledResourceElseNone() throws Exception {
        Path sf = Files.write(dir.resolve("my.sf2"), new byte[1234]);
        Path data = dir.resolve("data");
        Optional<SoundFonts.Located> configured = SoundFonts.locate(sf.toString(), data, null);
        assertThat(configured).isPresent();
        assertThat(configured.get().file()).isEqualTo(sf);
        assertThat(configured.get().stamp()).contains("1234");

        assertThat(SoundFonts.locate(dir.resolve("absent.sf2").toString(), data, null)).isEmpty();
        assertThat(SoundFonts.locate(null, data, null)).isEmpty();

        // a bundled resource is extracted once into the data directory
        Path bundledFile = Files.write(dir.resolve("bundled.sf2"), new byte[777]);
        Optional<SoundFonts.Located> bundled = SoundFonts.locate(null, data, bundledFile.toUri().toURL());
        assertThat(bundled).isPresent();
        assertThat(bundled.get().file()).isEqualTo(data.resolve("soundfont").resolve("GeneralUser-GS.sf2"));
        assertThat(Files.size(bundled.get().file())).isEqualTo(777);
        long stamp = Files.getLastModifiedTime(bundled.get().file()).toMillis();
        assertThat(SoundFonts.locate(null, data, bundledFile.toUri().toURL())).isPresent();
        assertThat(Files.getLastModifiedTime(bundled.get().file()).toMillis()).as("not extracted again")
                .isEqualTo(stamp);
        // a missing configured file falls back to the bundled one
        assertThat(SoundFonts.locate(dir.resolve("absent.sf2").toString(), data, bundledFile.toUri().toURL()))
                .isPresent();
    }

    @Test
    void midiNeedsTranscodingAndASoundFontAndOtherwiseIsListedAsUnsupportedWithTheReason() throws Exception {
        Path data = dir.resolve("data");
        AppConfig.Transcode off = new AppConfig.Transcode(false, null, null, null, null, null, null, null, null);
        TranscodingWiring disabled = TranscodingWiring.create(off, data);
        assertThat(disabled.midiAvailable).isFalse();
        assertThat(disabled.midiReason()).contains("disabled");

        AppConfig.Transcode noFont = new AppConfig.Transcode(null, null, null, null, null, null, null, null, null);
        TranscodingWiring none = TranscodingWiring.create(noFont, data);
        // without a bundled resource in the test classpath there is no SoundFont
        if (SoundFonts.class.getResource(SoundFonts.BUNDLED_RESOURCE) == null) {
            assertThat(none.midiAvailable).isFalse();
            assertThat(none.midiReason()).contains("SoundFont");
            TagReader reader = none.tagReader();
            assertThat(reader.supports(Format.MIDI)).isFalse();
            assertThat(reader.unsupportedReason(Format.MIDI)).contains("MIDI rendering unavailable")
                    .contains("SoundFont");
        }
        assertThat(none.fingerprintSuffix()).startsWith("|v12");

        // a configured file that exists enables MIDI (any file: the loader reads it lazily at the first render)
        Path sf = Files.write(dir.resolve("x.sf2"), new byte[10]);
        AppConfig.Transcode withFont = new AppConfig.Transcode(null, null, null, null, null, null, sf.toString(), null,
                null);
        TranscodingWiring on = TranscodingWiring.create(withFont, data);
        assertThat(on.midiAvailable).isTrue();
        assertThat(on.transcoder.midiAvailable()).isTrue();
        assertThat(on.fingerprintSuffix()).contains("+midi");
        assertThat(on.tagReader().supports(Format.MIDI)).isTrue();
        assertThat(on.tagReader().supports(Format.OPUS)).isTrue();
    }

    @Test
    void probingReaderAddsExactSampleCountAndRate() throws Exception {
        TranscodingWiring w = TranscodingWiring.create(
                new AppConfig.Transcode(null, null, null, null, null, null, null, null, null), dir.resolve("data"));
        TagReader reader = w.tagReader();
        Path wav = AudioFiles.sineWav(dir.resolve("a.wav"), 22050, 1, 5000, 440);
        TrackMeta m = reader.read(wav, Format.WAV);
        assertThat(m.sampleCount()).isEqualTo(5000);
        assertThat(m.sampleRate()).isEqualTo(22050);
        Path flac = AudioFiles.fixture("sweep.flac", dir.resolve("b.flac"));
        assertThat(reader.read(flac, Format.FLAC).sampleCount()).isEqualTo(88200);
        Path ogg = AudioFiles.fixture("sweep.ogg", dir.resolve("c.ogg"));
        assertThat(reader.read(ogg, Format.OGG).sampleCount()).isEqualTo(88200);
        Path opus = AudioFiles.fixture("sweep.opus", dir.resolve("d.opus"));
        assertThat(reader.read(opus, Format.OPUS).sampleCount()).isEqualTo(96000);
        Path mp3 = AudioFiles.fixture("sweep.mp3", dir.resolve("e.mp3"));
        assertThat(reader.read(mp3, Format.MP3).sampleCount()).as("MP3 is probed lazily").isZero();
    }
}
