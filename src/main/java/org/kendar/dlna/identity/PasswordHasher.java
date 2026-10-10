package org.kendar.dlna.identity;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 password hashing with the JDK only. The stored form is self-describing:
 * {@code pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>}.
 */
public final class PasswordHasher {

    public static final int DEFAULT_ITERATIONS = 310_000;
    static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int MAX_ITERATIONS = 5_000_000;

    private final int iterations;
    private final SecureRandom random;

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS, new SecureRandom());
    }

    public PasswordHasher(int iterations, SecureRandom random) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        this.iterations = iterations;
        this.random = random;
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] h = derive(password, salt, iterations);
        Base64.Encoder enc = Base64.getEncoder();
        return PREFIX + "$" + iterations + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(h);
    }

    /** Constant-time check against a stored hash; a malformed stored value never matches. */
    public boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals(PREFIX)) {
            return false;
        }
        try {
            int iter = Integer.parseInt(p[1]);
            if (iter < 1 || iter > MAX_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iter));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
