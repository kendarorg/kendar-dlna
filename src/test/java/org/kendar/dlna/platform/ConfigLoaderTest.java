package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    private static final UnaryOperator<String> NONE = k -> null;

    @TempDir
    Path dir;

    @Test
    void writesTemplateWhenMissingAndUsesDefaults() throws Exception {
        Path cfgDir = dir.resolve("config");
        AppConfig cfg = ConfigLoader.load(cfgDir, NONE);

        assertThat(cfgDir.resolve("config.json")).exists();
        assertThat(cfg.network().port()).isEqualTo(AppConfig.DEFAULT_PORT);
        assertThat(cfg.server().friendlyName()).isEqualTo(AppConfig.DEFAULT_FRIENDLY_NAME);
        assertThat(cfg.library().dataDir()).isEqualTo("/data");
        assertThat(Files.readString(cfgDir.resolve("config.json"))).contains("friendlyName");
        // second load reads the template back
        assertThat(ConfigLoader.load(cfgDir, NONE)).isEqualTo(cfg);
    }

    @Test
    void templateUsesDefaultRootWhenGiven() throws Exception {
        Path cfgDir = dir.resolve("cfg2");
        AppConfig cfg = ConfigLoader.load(cfgDir, k -> "defaultRoot".equals(k) ? "/music" : null);

        assertThat(cfg.library().roots()).containsExactly(Map.entry("Music", "/music"));
        assertThat(Files.readString(cfgDir.resolve("config.json"))).contains("\"Music\"");
        // an existing file is never rewritten
        assertThat(ConfigLoader.load(cfgDir, k -> "defaultRoot".equals(k) ? "/other" : null).library().roots())
                .containsExactly(Map.entry("Music", "/music"));
        assertThat(ConfigLoader.lookup(new Properties(), Map.of("KENDAR_DEFAULT_ROOT", "/music"))
                .apply("defaultRoot")).isEqualTo("/music");
    }

    @Test
    void mainRootDefaultsToTheFirstRootAndMustBeConfigured() throws Exception {
        Files.writeString(dir.resolve("config.json"), "{\"library\":{\"roots\":{\"a\":\"/a\",\"b\":\"/b\"}}}");
        assertThat(ConfigLoader.load(dir, NONE).library().effectiveMainRoot()).isEqualTo("a");

        Files.writeString(dir.resolve("config.json"),
                "{\"library\":{\"roots\":{\"a\":\"/a\",\"b\":\"/b\"},\"mainRoot\":\"b\"}}");
        AppConfig cfg = ConfigLoader.load(dir, k -> "port".equals(k) ? "9001" : null);   // overrides keep it
        assertThat(cfg.library().effectiveMainRoot()).isEqualTo("b");

        Files.writeString(dir.resolve("config.json"),
                "{\"library\":{\"roots\":{\"a\":\"/a\"},\"mainRoot\":\"zzz\"}}");
        assertThatThrownBy(() -> ConfigLoader.load(dir, NONE)).isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("mainRoot");

        Files.writeString(dir.resolve("config.json"), "{}");
        assertThat(ConfigLoader.load(dir, NONE).library().effectiveMainRoot()).isNull();
    }

    @Test
    void readsFileValuesAndIgnoresUnknownSections() throws Exception {
        Files.writeString(dir.resolve("config.json"), """
                {"server":{"friendlyName":"Salotto"},"network":{"port":9000},
                 "library":{"roots":{"main":"/music"},"dataDir":"/var/d"},"transcode":{"enabled":true}}""");
        AppConfig cfg = ConfigLoader.load(dir, NONE);

        assertThat(cfg.server().friendlyName()).isEqualTo("Salotto");
        assertThat(cfg.network().port()).isEqualTo(9000);
        assertThat(cfg.library().roots()).containsEntry("main", "/music");
        assertThat(cfg.library().dataDir()).isEqualTo("/var/d");
    }

    @Test
    void relativePathsInTheFileAreResolvedAgainstTheConfigFileDirectory() throws Exception {
        Files.writeString(dir.resolve("config.json"), """
                {"library":{"roots":{"main":"../music","abs":"/music"},"playlistsDir":"playlists","dataDir":"./data"},
                 "transcode":{"soundFontPath":"sf/GeneralUser.sf2"}}""");
        AppConfig cfg = ConfigLoader.load(dir, NONE);

        Path base = dir.toAbsolutePath().normalize();
        assertThat(cfg.library().roots()).containsEntry("main", base.resolve("../music").normalize().toString());
        assertThat(cfg.library().roots()).containsEntry("abs", "/music");
        assertThat(cfg.library().playlistsDir()).isEqualTo(base.resolve("playlists").toString());
        assertThat(cfg.library().dataDir()).isEqualTo(base.resolve("data").toString());
        assertThat(cfg.transcode().soundFontPath()).isEqualTo(base.resolve("sf/GeneralUser.sf2").toString());
    }

    @Test
    void relativeCommandLineOverridesStayRelativeToTheWorkingDirectory() throws Exception {
        Files.writeString(dir.resolve("config.json"), "{}");
        Properties props = new Properties();
        props.setProperty("kendar.dataDir", "rel/data");

        AppConfig cfg = ConfigLoader.load(dir, ConfigLoader.lookup(props, Map.of()));
        assertThat(cfg.library().dataDir()).isEqualTo("rel/data");
    }

    @Test
    void overridesWinOverFile() throws Exception {
        Files.writeString(dir.resolve("config.json"), "{\"network\":{\"port\":9000}}");
        Properties props = new Properties();
        props.setProperty("kendar.port", "9100");
        props.setProperty("kendar.dataDir", "C:/tmp/data");
        UnaryOperator<String> s = ConfigLoader.lookup(props, Map.of("KENDAR_FRIENDLY_NAME", "Env Name"));

        AppConfig cfg = ConfigLoader.load(dir, s);
        assertThat(cfg.network().port()).isEqualTo(9100);
        assertThat(cfg.library().dataDir()).isEqualTo("C:/tmp/data");
        assertThat(cfg.server().friendlyName()).isEqualTo("Env Name");
    }

    @Test
    void lookupPrefersSystemPropertyOverEnvAndTreatsBlankAsUnset() {
        Properties props = new Properties();
        props.setProperty("kendar.configDir", "/from/prop");
        props.setProperty("kendar.dataDir", "  ");
        UnaryOperator<String> s = ConfigLoader.lookup(props,
                Map.of("KENDAR_CONFIG_DIR", "/from/env", "KENDAR_DATA_DIR", "/env/data"));

        assertThat(s.apply("configDir")).isEqualTo("/from/prop");
        assertThat(s.apply("dataDir")).isEqualTo("/env/data");
        assertThat(s.apply("port")).isNull();
    }

    @Test
    void configDirDefaultsToSlashConfig() {
        assertThat(ConfigLoader.configDir(NONE)).isEqualTo(Path.of("/config"));
    }

    @Test
    void rejectsInvalidValues() throws Exception {
        Files.writeString(dir.resolve("config.json"), "{\"network\":{\"port\":70000}}");
        assertThatThrownBy(() -> ConfigLoader.load(dir, NONE)).isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("port");

        Files.writeString(dir.resolve("config.json"), "{\"server\":{\"friendlyName\":\" \"}}");
        assertThatThrownBy(() -> ConfigLoader.load(dir, NONE)).isInstanceOf(ConfigLoader.ConfigException.class);

        Files.writeString(dir.resolve("config.json"), "{}");
        assertThatThrownBy(() -> ConfigLoader.load(dir, k -> k.equals("port") ? "abc" : null))
                .isInstanceOf(ConfigLoader.ConfigException.class);
    }

    @Test
    void randomAndViewsSectionsHaveDefaultsAndCanBeConfigured() throws Exception {
        assertThat(new AppConfig(null, null, null).random().ttlMinutes()).isEqualTo(30);
        assertThat(new AppConfig(null, null, null).views().bucketThreshold()).isEqualTo(500);

        Files.writeString(dir.resolve("config.json"),
                "{\"random\":{\"ttlMinutes\":5},\"views\":{\"bucketThreshold\":50}}");
        AppConfig cfg = ConfigLoader.load(dir, NONE);

        assertThat(cfg.random().ttlMinutes()).isEqualTo(5);
        assertThat(cfg.views().bucketThreshold()).isEqualTo(50);
    }

    @Test
    void rejectsInvalidRandomAndViewsValues() throws Exception {
        Files.writeString(dir.resolve("config.json"), "{\"random\":{\"ttlMinutes\":0}}");
        assertThatThrownBy(() -> ConfigLoader.load(dir, NONE)).isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("random.ttlMinutes");

        Files.writeString(dir.resolve("config.json"), "{\"views\":{\"bucketThreshold\":0}}");
        assertThatThrownBy(() -> ConfigLoader.load(dir, NONE)).isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("views.bucketThreshold");
    }

    @Test
    void scanSectionHasDefaultsAndIsValidatedAtStartup() throws Exception {
        AppConfig defaults = new AppConfig(null, null, null);
        assertThat(defaults.scan().cron()).isEqualTo("0 */12 * * *");
        assertThat(defaults.scan().catchUpThresholdHours()).isEqualTo(12);

        Files.writeString(dir.resolve("config.json"),
                "{\"scan\":{\"cron\":\"30 3 * * sun\",\"catchUpThresholdHours\":6}}");
        AppConfig cfg = ConfigLoader.load(dir, NONE);
        assertThat(cfg.scan().cron()).isEqualTo("30 3 * * sun");
        assertThat(cfg.scan().catchUpThresholdHours()).isEqualTo(6);

        Files.writeString(dir.resolve("config.json"), "{\"scan\":{\"cron\":\"every day\"}}");
        assertThatThrownBy(() -> ConfigLoader.load(dir, NONE)).isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("scan.cron");

        Files.writeString(dir.resolve("config.json"), "{\"scan\":{\"catchUpThresholdHours\":0}}");
        assertThatThrownBy(() -> ConfigLoader.load(dir, NONE)).isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("scan.catchUpThresholdHours");
    }
}
