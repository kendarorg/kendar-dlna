package org.kendar.dlna.ingestion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Fingerprint of the configured library roots (names, paths, order): a persisted index only fits the same roots. */
public final class RootsFingerprint {

    private RootsFingerprint() {
    }

    public static String of(Map<String, Path> roots) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            roots.forEach((name, dir) -> {
                md.update(name.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(dir.toAbsolutePath().normalize().toString().replace('\\', '/')
                        .getBytes(StandardCharsets.UTF_8));
                md.update((byte) 1);
            });
            return HexFormat.of().formatHex(md.digest(), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
