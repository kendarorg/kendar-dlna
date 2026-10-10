package org.kendar.dlna.identity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Token store persisted as JSON, used for invites ({@code invites.json}, one-time, 7 days) and remember-me
 * tokens ({@code sessions.json}, 30 days). Only the SHA-256 of a token is stored; the random token (256 bit) is
 * returned once by {@link #issue}. Thread-safe.
 */
final class TokenStore {

    /** A stored entry: {@code hash} of the token, owner and expiry. */
    record Entry(String hash, String userId, Instant createdAt, Instant expiresAt) {
    }

    private final Path file;
    private final String arrayName;
    private final Clock clock;
    private final SecureRandom random;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    TokenStore(Path file, String arrayName, Clock clock, SecureRandom random) {
        this.file = file;
        this.arrayName = arrayName;
        this.clock = clock;
        this.random = random;
        JsonNode root = JsonFiles.read(file);
        if (root != null) {
            for (JsonNode n : root.path(arrayName)) {
                Entry e = new Entry(n.path("hash").asText(), n.path("userId").asText(),
                        Instant.parse(n.path("createdAt").asText()), Instant.parse(n.path("expiresAt").asText()));
                entries.put(e.hash(), e);
            }
        }
        synchronized (this) {
            if (sweep()) {
                save();
            }
        }
    }

    static String sha256(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Creates an entry and returns the raw token (never stored). */
    synchronized String issue(String userId, Duration lifetime) {
        sweep();
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Instant now = clock.instant();
        Entry e = new Entry(sha256(token), userId, now, now.plus(lifetime));
        entries.put(e.hash(), e);
        save();
        return token;
    }

    /** The live entry of a token (an expired entry is dropped), without consuming it. */
    synchronized Optional<Entry> peek(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        Entry e = entries.get(sha256(token));
        if (e == null) {
            return Optional.empty();
        }
        if (!e.expiresAt().isAfter(clock.instant())) {
            entries.remove(e.hash());
            save();
            return Optional.empty();
        }
        return Optional.of(e);
    }

    /** Consumes a token: returns its live entry and removes it (one-time use / rotation). */
    synchronized Optional<Entry> take(String token) {
        Optional<Entry> e = peek(token);
        e.ifPresent(x -> {
            entries.remove(x.hash());
            save();
        });
        return e;
    }

    synchronized int removeWhere(Predicate<Entry> p) {
        int before = entries.size();
        entries.values().removeIf(p);
        int removed = before - entries.size();
        if (removed > 0) {
            save();
        }
        return removed;
    }

    synchronized int removeUser(String userId) {
        return removeWhere(e -> e.userId().equals(userId));
    }

    synchronized List<Entry> forUser(String userId) {
        sweep();
        return entries.values().stream().filter(e -> e.userId().equals(userId)).toList();
    }

    synchronized int size() {
        return entries.size();
    }

    private boolean sweep() {
        Instant now = clock.instant();
        return entries.values().removeIf(e -> !e.expiresAt().isAfter(now));
    }

    private void save() {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("version", 1);
        ArrayNode arr = root.putArray(arrayName);
        new ArrayList<>(entries.values()).forEach(e -> {
            ObjectNode n = arr.addObject();
            n.put("hash", e.hash());
            n.put("userId", e.userId());
            n.put("createdAt", e.createdAt().toString());
            n.put("expiresAt", e.expiresAt().toString());
        });
        JsonFiles.write(file, root);
    }
}
