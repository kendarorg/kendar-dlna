package org.kendar.dlna.identity;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The users, in memory and persisted as JSON ({@code <dataDir>/users/users.json}); every change is written
 * atomically (temp file + rename). Thread-safe.
 */
public final class UserStore {

    public static final String FILE_NAME = "users.json";

    private final Path file;
    private final Map<String, User> byId = new LinkedHashMap<>();

    public UserStore(Path file) {
        this.file = file;
        JsonNode root = JsonFiles.read(file);
        if (root != null) {
            for (JsonNode n : root.path("users")) {
                User u = fromJson(n);
                byId.put(u.id(), u);
            }
        }
    }

    public synchronized List<User> all() {
        return List.copyOf(byId.values());
    }

    public synchronized boolean isEmpty() {
        return byId.isEmpty();
    }

    public synchronized Optional<User> byId(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public synchronized Optional<User> byUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        String key = username.strip().toLowerCase(Locale.ROOT);
        return byId.values().stream().filter(u -> u.username().toLowerCase(Locale.ROOT).equals(key)).findFirst();
    }

    /** Inserts or replaces the user (same id) and persists. */
    public synchronized void put(User user) {
        byId.put(user.id(), user);
        save();
    }

    /** Replaces several users in one write. */
    public synchronized void putAll(List<User> users) {
        users.forEach(u -> byId.put(u.id(), u));
        save();
    }

    public synchronized boolean remove(String id) {
        boolean removed = byId.remove(id) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    private void save() {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("version", 1);
        ArrayNode arr = root.putArray("users");
        byId.values().forEach(u -> arr.add(toJson(u)));
        JsonFiles.write(file, root);
    }

    static ObjectNode toJson(User u) {
        ObjectNode n = Json.mapper().createObjectNode();
        n.put("id", u.id());
        n.put("username", u.username());
        n.put("displayName", u.displayName());
        n.put("role", u.role().wire());
        n.put("passwordHash", u.passwordHash());
        n.put("mustChange", u.mustChange());
        ArrayNode roots = n.putArray("allowedRoots");
        u.allowedRoots().forEach(roots::add);
        n.put("downloadAllowed", u.downloadAllowed());
        n.put("enabled", u.enabled());
        n.put("createdAt", u.createdAt().toString());
        n.put("lastLogin", u.lastLogin() == null ? null : u.lastLogin().toString());
        return n;
    }

    static User fromJson(JsonNode n) {
        List<String> roots = new ArrayList<>();
        n.path("allowedRoots").forEach(r -> roots.add(r.asText()));
        String last = n.path("lastLogin").asText(null);
        return new User(n.path("id").asText(), n.path("username").asText(), n.path("displayName").asText(null),
                Role.parse(n.path("role").asText("user")), n.path("passwordHash").asText(null),
                n.path("mustChange").asBoolean(false), roots, n.path("downloadAllowed").asBoolean(false),
                n.path("enabled").asBoolean(true), Instant.parse(n.path("createdAt").asText()),
                last == null || last.isBlank() ? null : Instant.parse(last));
    }
}
