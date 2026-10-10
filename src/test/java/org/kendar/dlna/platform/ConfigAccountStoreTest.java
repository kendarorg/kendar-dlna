package org.kendar.dlna.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.identity.IdentityService.LegacyAdmin;

class ConfigAccountStoreTest {

    @Test
    void readsTheLegacyAdminSectionAndNeverWrites(@TempDir Path dir) throws IOException {
        String json = "{\"server\":{\"friendlyName\":\"Mine\"},\"admin\":{\"username\":\"boss\","
                + "\"passwordHash\":\"pbkdf2-sha256$1$AA==$AA==\",\"mustChange\":true}}";
        Files.writeString(dir.resolve("config.json"), json);

        assertThat(new ConfigAccountStore(dir).load())
                .contains(new LegacyAdmin("boss", "pbkdf2-sha256$1$AA==$AA==", true));

        assertThat(Files.readString(dir.resolve("config.json"))).isEqualTo(json);
    }

    @Test
    void noAdminSectionOrNoFileMeansNothingToMigrate(@TempDir Path dir) throws IOException {
        assertThat(new ConfigAccountStore(dir).load()).isEmpty();
        Files.writeString(dir.resolve("config.json"), "{\"server\":{}}");
        assertThat(new ConfigAccountStore(dir).load()).isEmpty();
    }
}
