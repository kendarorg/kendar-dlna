package org.kendar.dlna.dlna;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Persists a stable device UUID in the data directory (file {@code device-uuid}). */
public final class DeviceUuidStore {

    public static final String FILE_NAME = "device-uuid";

    private DeviceUuidStore() {
    }

    public static UUID loadOrCreate(Path dataDir) {
        Path file = dataDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(dataDir);
            if (Files.exists(file)) {
                try {
                    return UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).trim());
                } catch (IllegalArgumentException corrupt) {
                    // fall through: regenerate
                }
            }
            UUID uuid = UUID.randomUUID();
            Path tmp = dataDir.resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, uuid + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return uuid;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot persist device UUID in " + dataDir, e);
        }
    }
}
