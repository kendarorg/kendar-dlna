package org.kendar.dlna.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;

/**
 * Stable, URL-safe short ids (16 chars of {@code [A-Za-z0-9_-]}) derived from a kind and path parts.
 * The algorithm is frozen: ids are persisted and appear in client-visible URLs.
 */
public final class StableIds {

    private StableIds() {
    }

    public static String id(String kind, String... parts) {
        if (kind == null || !kind.matches("[a-z0-9]{1,8}")) {
            throw new IllegalArgumentException("Invalid id kind: " + kind);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(kind.getBytes(StandardCharsets.UTF_8));
            for (String p : parts) {
                md.update((byte) 0);
                md.update(Normalizer.normalize(p, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(md.digest(), 12));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String forPath(String kind, RootRef ref) {
        return id(kind, ref.qualified());
    }
}
