package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.shared.RootRef;

class LibraryRootsTest {

    @TempDir
    Path dir;

    @Test
    void resolvesInsideRootAndKeepsConfiguredOrder() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("zeta", dir.resolve("z").toString());
        cfg.put("alpha", dir.resolve("a").toString());
        LibraryRoots roots = new LibraryRoots(cfg);

        assertThat(roots.roots().keySet()).containsExactly("zeta", "alpha");
        assertThat(roots.resolve(RootRef.parse("alpha/x/y.mp3")))
                .isEqualTo(dir.resolve("a").toAbsolutePath().resolve("x/y.mp3"));
        assertThat(roots.resolve(RootRef.parse("unknown/x"))).isNull();
    }

    @Test
    void localAddressUsesConfiguredValue() {
        assertThat(LocalAddress.baseUrl("192.168.1.9", 8200)).isEqualTo("http://192.168.1.9:8200");
        assertThat(LocalAddress.resolve(null)).isNotBlank();
    }

    @Test
    void configValidationRejectsBadRoots() throws Exception {
        Path cfgDir = dir.resolve("cfg");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("config.json"), "{\"library\":{\"roots\":{\"a/b\":\"/music\"}}}");
        assertThatThrownBy(() -> ConfigLoader.load(cfgDir, k -> null))
                .isInstanceOf(ConfigLoader.ConfigException.class).hasMessageContaining("invalid root name");

        Files.writeString(cfgDir.resolve("config.json"), "{\"library\":{\"roots\":{\"ok\":\" \"}}}");
        assertThatThrownBy(() -> ConfigLoader.load(cfgDir, k -> null))
                .isInstanceOf(ConfigLoader.ConfigException.class).hasMessageContaining("must have a path");

        Files.writeString(cfgDir.resolve("config.json"), "{\"library\":{\"roots\":{\"ok\":\"/music\"}}}");
        assertThat(ConfigLoader.load(cfgDir, k -> null).library().roots()).containsEntry("ok", "/music");
    }
}
