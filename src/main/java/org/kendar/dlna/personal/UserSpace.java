package org.kendar.dlna.personal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The in-memory state and files of one user: {@code playlists/<id>.json}, {@code favorites.json},
 * {@code queue.json}, {@code history.json}. Every part is loaded lazily on first use and written atomically (temp
 * file + rename). Not thread-safe by itself: {@link PersonalService} synchronizes on the instance.
 */
final class UserSpace {

    static final int HISTORY_SIZE = 1000;
    private static final Logger LOG = Logger.getLogger(UserSpace.class.getName());

    /** An ordered list of track references with a version. */
    static class TrackList {
        List<String> tracks = new ArrayList<>();
        long version = 1;
        Instant updated = Instant.EPOCH;
    }

    static final class PlaylistData extends TrackList {
        String id;
        String name;
        String description = "";
        boolean shared;
        Instant created = Instant.EPOCH;
    }

    static final class QueueState {
        List<String> items = new ArrayList<>();
        int index;
        double positionSeconds;
        boolean shuffle;
        String repeat = "off";
        JsonNode source;
        String deviceId;
        Instant updated = Instant.EPOCH;
        long version;
    }

    static final class History {
        /** Newest last. */
        final ArrayDeque<Map.Entry<String, Long>> recent = new ArrayDeque<>();
        /** path -> {count, lastPlayedMillis} */
        final Map<String, long[]> counts = new LinkedHashMap<>();
        boolean dirty;
        Instant lastFlush = Instant.EPOCH;
    }

    final String userId;
    final Path dir;
    Instant lastAccess;
    boolean evicted;
    final ArrayDeque<Instant> reports = new ArrayDeque<>();

    private Map<String, PlaylistData> playlists;
    private TrackList favorites;
    private QueueState queue;
    private History history;

    UserSpace(String userId, Path dir, Instant now) {
        this.userId = userId;
        this.dir = dir;
        this.lastAccess = now;
    }

    // ---- playlists ----

    Map<String, PlaylistData> playlists() {
        if (playlists == null) {
            Map<String, PlaylistData> m = new LinkedHashMap<>();
            Path pd = dir.resolve("playlists");
            if (Files.isDirectory(pd)) {
                try (Stream<Path> s = Files.list(pd)) {
                    List<Path> files = s.filter(f -> f.getFileName().toString().endsWith(".json")
                            && !f.getFileName().toString().startsWith(".")).sorted().toList();
                    for (Path f : files) {
                        try {
                            PlaylistData p = parsePlaylist(read(f));
                            m.put(p.id, p);
                        } catch (RuntimeException e) {
                            LOG.log(Level.WARNING, "Skipping unreadable playlist " + f, e);
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            playlists = m;
        }
        return playlists;
    }

    void save(PlaylistData p) {
        ObjectNode n = Json.mapper().createObjectNode();
        n.put("id", p.id).put("name", p.name).put("description", p.description).put("shared", p.shared);
        n.set("tracks", strings(p.tracks));
        n.put("created", p.created.toString()).put("updated", p.updated.toString());
        n.put("version", p.version);
        write(dir.resolve("playlists").resolve(p.id + ".json"), n);
    }

    void remove(String id) {
        playlists().remove(id);
        try {
            Files.deleteIfExists(dir.resolve("playlists").resolve(id + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PlaylistData parsePlaylist(JsonNode n) {
        PlaylistData p = new PlaylistData();
        p.id = n.path("id").asText();
        if (!PersonalService.validId(p.id)) {
            throw new IllegalArgumentException("bad id");
        }
        p.name = n.path("name").asText(p.id);
        p.description = n.path("description").asText("");
        p.shared = n.path("shared").asBoolean(false);
        p.tracks = readStrings(n.path("tracks"));
        p.created = instant(n.path("created"));
        p.updated = instant(n.path("updated"));
        p.version = n.path("version").asLong(1);
        return p;
    }

    // ---- favorites ----

    TrackList favorites() {
        if (favorites == null) {
            TrackList f = new TrackList();
            JsonNode n = readIfExists(dir.resolve("favorites.json"));
            if (n != null) {
                f.tracks = readStrings(n.path("tracks"));
                f.version = n.path("version").asLong(1);
                f.updated = instant(n.path("updated"));
            }
            favorites = f;
        }
        return favorites;
    }

    void saveFavorites() {
        ObjectNode n = Json.mapper().createObjectNode();
        n.set("tracks", strings(favorites.tracks));
        n.put("version", favorites.version).put("updated", favorites.updated.toString());
        write(dir.resolve("favorites.json"), n);
    }

    // ---- queue ----

    QueueState queue() {
        if (queue == null) {
            QueueState q = new QueueState();
            JsonNode n = readIfExists(dir.resolve("queue.json"));
            if (n != null) {
                q.items = readStrings(n.path("items"));
                q.index = n.path("index").asInt(0);
                q.positionSeconds = n.path("positionSeconds").asDouble(0);
                q.shuffle = n.path("shuffle").asBoolean(false);
                q.repeat = n.path("repeat").asText("off");
                q.source = n.has("source") && !n.get("source").isNull() ? n.get("source") : null;
                q.deviceId = n.path("deviceId").isTextual() ? n.get("deviceId").asText() : null;
                q.updated = instant(n.path("updated"));
                q.version = n.path("version").asLong(0);
            }
            queue = q;
        }
        return queue;
    }

    void saveQueue() {
        ObjectNode n = Json.mapper().createObjectNode();
        n.set("items", strings(queue.items));
        n.put("index", queue.index).put("positionSeconds", queue.positionSeconds).put("shuffle", queue.shuffle)
                .put("repeat", queue.repeat);
        n.set("source", queue.source);
        n.put("deviceId", queue.deviceId).put("updated", queue.updated.toString()).put("version", queue.version);
        write(dir.resolve("queue.json"), n);
    }

    // ---- history ----

    History history() {
        if (history == null) {
            History h = new History();
            JsonNode n = readIfExists(dir.resolve("history.json"));
            if (n != null) {
                for (JsonNode e : n.path("recent")) {
                    h.recent.addLast(Map.entry(e.path("p").asText(), e.path("t").asLong()));
                }
                n.path("counts").fields().forEachRemaining(
                        e -> h.counts.put(e.getKey(), new long[] { e.getValue().path("n").asLong(),
                                e.getValue().path("t").asLong() }));
            }
            history = h;
        }
        return history;
    }

    boolean historyDirty() {
        return history != null && history.dirty;
    }

    void flushHistory(Instant now) {
        if (history == null || !history.dirty) {
            return;
        }
        ObjectNode n = Json.mapper().createObjectNode();
        ArrayNode recent = n.putArray("recent");
        history.recent.forEach(e -> recent.addObject().put("p", e.getKey()).put("t", e.getValue()));
        ObjectNode counts = n.putObject("counts");
        history.counts.forEach((k, v) -> counts.putObject(k).put("n", v[0]).put("t", v[1]));
        write(dir.resolve("history.json"), n);
        history.dirty = false;
        history.lastFlush = now;
    }

    // ---- file helpers ----

    private static ArrayNode strings(List<String> l) {
        ArrayNode a = Json.mapper().createArrayNode();
        l.forEach(a::add);
        return a;
    }

    private static List<String> readStrings(JsonNode a) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : a) {
            out.add(e.asText());
        }
        return out;
    }

    private static Instant instant(JsonNode n) {
        try {
            return n.isTextual() ? Instant.parse(n.asText()) : Instant.EPOCH;
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    private static JsonNode readIfExists(Path f) {
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return read(f);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Ignoring unreadable " + f, e);
            return null;
        }
    }

    private static JsonNode read(Path f) {
        try {
            return Json.mapper().readTree(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + f, e);
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
