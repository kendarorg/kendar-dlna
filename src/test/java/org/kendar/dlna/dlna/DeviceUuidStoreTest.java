package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceUuidStoreTest {

    @TempDir
    Path dir;

    @Test
    void createsAndThenReusesUuid() {
        Path data = dir.resolve("nested/data");
        UUID first = DeviceUuidStore.loadOrCreate(data);
        UUID second = DeviceUuidStore.loadOrCreate(data);

        assertThat(second).isEqualTo(first);
        assertThat(data.resolve(DeviceUuidStore.FILE_NAME)).exists();
        assertThat(data.resolve(DeviceUuidStore.FILE_NAME + ".tmp")).doesNotExist();
    }

    @Test
    void readsExistingUuid() throws Exception {
        UUID u = UUID.randomUUID();
        Files.writeString(dir.resolve(DeviceUuidStore.FILE_NAME), u + "\n");
        assertThat(DeviceUuidStore.loadOrCreate(dir)).isEqualTo(u);
    }

    @Test
    void regeneratesWhenCorrupt() throws Exception {
        Files.writeString(dir.resolve(DeviceUuidStore.FILE_NAME), "garbage");
        UUID u = DeviceUuidStore.loadOrCreate(dir);
        assertThat(DeviceUuidStore.loadOrCreate(dir)).isEqualTo(u);
    }
}
