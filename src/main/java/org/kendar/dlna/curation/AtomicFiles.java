package org.kendar.dlna.curation;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Safe writes: temp file in the same directory, then atomic rename over the target. */
public final class AtomicFiles {

    /** Prefix of the hidden temp files (the scanner skips hidden files). */
    public static final String TEMP_PREFIX = ".kdlna-";

    private AtomicFiles() {
    }

    /** Hidden temp name next to {@code target}; keeps the extension (tag libraries decide the format by it). */
    public static Path tempSibling(Path target) {
        return target.resolveSibling(TEMP_PREFIX + UUID.randomUUID().toString().substring(0, 8) + "-"
                + target.getFileName());
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        Path tmp = tempSibling(target);
        try {
            Files.write(tmp, bytes);
            replace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Moves {@code tmp} over {@code target} atomically (plain replace where the file system cannot). */
    public static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
