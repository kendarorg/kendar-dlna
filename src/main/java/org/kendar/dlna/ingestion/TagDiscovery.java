package org.kendar.dlna.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.kendar.dlna.shared.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The names of the non-standard tags found in the audio files while they are read, with the number of files that
 * carry each. Written to {@code tags.json} next to {@code config.json} so the admin can pick the ones worth
 * indexing ({@code extraTags}); the file is disposable, a "Re-read all tags" rescan rebuilds the counts. Only files
 * whose tags are actually read count: unchanged files are not re-read, so after an incremental scan the counts of
 * known tags keep their previous value and newly seen tags start from the files read now.
 */
public final class TagDiscovery {

    private static final Logger LOG = Logger.getLogger(TagDiscovery.class.getName());

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    /** One file carries these tags (each name counts once per file). */
    public void seen(Collection<String> namesOfOneFile) {
        for (String n : namesOfOneFile) {
            String key = n.strip().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    /** Forgets everything (a full re-read starts the counts from zero). */
    public void reset() {
        counts.clear();
    }

    /** Takes over the names and counts of a previous {@code tags.json} for tags not seen yet. */
    public void mergeKnown(Map<String, Integer> known) {
        known.forEach((name, count) -> counts.putIfAbsent(name, new AtomicInteger(count)));
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> out = new TreeMap<>();
        counts.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    /** Reads {@code tags.json}; an empty map when it is missing or unreadable. */
    public static Map<String, Integer> load(Path file) {
        Map<String, Integer> out = new TreeMap<>();
        try {
            if (file != null && Files.isRegularFile(file)) {
                JsonNode root = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
                for (JsonNode t : root.path("tags")) {
                    out.put(t.path("name").asText().toLowerCase(Locale.ROOT), t.path("count").asInt());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.fine("Ignoring unreadable " + file + ": " + e);
        }
        return out;
    }

    /** Writes {@code tags.json} atomically; a failure is logged, never thrown (the file is only a convenience). */
    public void write(Path file) {
        if (file == null) {
            return;
        }
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("scannedAt", Instant.now().toString());
        ArrayNode tags = root.putArray("tags");
        counts().forEach((name, count) -> {
            ObjectNode t = tags.addObject();
            t.put("name", name);
            t.put("count", count);
        });
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warning("Cannot write " + file + ": " + e);
        }
    }
}
