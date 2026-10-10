package org.kendar.dlna.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;

/** Reads a JSON file (missing = null) and writes it atomically: temp file next to it, then rename. */
final class JsonFiles {

    private JsonFiles() {
    }

    static JsonNode read(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    static void write(Path file, JsonNode node) {
        Path tmp = file.resolveSibling("." + file.getFileName() + "." + UUID.randomUUID().toString().substring(0, 8)
                + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(tmp, Json.mapper().writeValueAsString(node) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
