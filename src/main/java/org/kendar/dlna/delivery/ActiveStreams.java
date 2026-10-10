package org.kendar.dlna.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Registry of the streams being sent right now (for the admin view). Thread-safe, in memory only. */
public final class ActiveStreams {

    /** One open stream. {@code variant} is {@code original} or the transcode path segment (e.g. {@code mp3}). */
    public record Info(long id, String client, String containerId, String itemId, String variant,
            Instant startedAt) {
    }

    /** Closing it ends the stream's entry. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private final Clock clock;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Info> open = new ConcurrentHashMap<>();

    public ActiveStreams() {
        this(Clock.systemUTC());
    }

    public ActiveStreams(Clock clock) {
        this.clock = clock;
    }

    public Handle open(String client, String containerId, String itemId, String variant) {
        long id = ids.incrementAndGet();
        open.put(id, new Info(id, client, containerId, itemId, variant == null ? "original" : variant,
                clock.instant()));
        return () -> open.remove(id);
    }

    /** Open streams, oldest first. */
    public List<Info> list() {
        return open.values().stream().sorted(Comparator.comparingLong(Info::id)).toList();
    }

    public int count() {
        return open.size();
    }

    /**
     * Parses the path of a stream request ({@code /<containerId>/<itemId>[/<variant>]}) and opens an entry;
     * a path that does not fit opens nothing.
     */
    public Handle openForPath(String client, String pathInContext) {
        String[] parts = pathInContext == null ? new String[0] : pathInContext.replaceFirst("^/+", "").split("/");
        if (parts.length < 2 || parts.length > 3) {
            return () -> {
            };
        }
        return open(client, parts[0], parts[1], parts.length == 3 ? parts[2] : null);
    }
}
