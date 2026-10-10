package org.kendar.dlna.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.kendar.dlna.identity.IdentityService.LegacyAdmin;
import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Read-only access to the phase-1 admin account of config.json ({@code admin} section: {@code username},
 * {@code passwordHash}, {@code mustChange}). It is only used to migrate the admin into the user store at the
 * first start; afterwards the section is ignored.
 */
final class ConfigAccountStore {

    private final Path file;

    ConfigAccountStore(Path configDir) {
        this.file = configDir.resolve(ConfigLoader.CONFIG_FILE);
    }

    Optional<LegacyAdmin> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode a = root == null ? null : root.get("admin");
            if (a == null || !a.isObject()) {
                return Optional.empty();
            }
            return Optional.of(new LegacyAdmin(a.path("username").asText(null), a.path("passwordHash").asText(null),
                    a.path("mustChange").asBoolean(false)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
