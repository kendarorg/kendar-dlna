package org.kendar.dlna.delivery;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** At most {@code max} permits per key at a time (e.g. concurrent transcodes per user). Thread-safe. */
public final class KeyedLimiter {

    /** Closing it returns the permit (idempotent). */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private final int max;
    private final Map<String, Integer> inUse = new ConcurrentHashMap<>();

    public KeyedLimiter(int max) {
        this.max = max;
    }

    public int max() {
        return max;
    }

    /** A permit for the key, or empty when the key already holds {@code max}. */
    public Optional<Permit> tryAcquire(String key) {
        boolean[] granted = new boolean[1];
        inUse.compute(key, (k, n) -> {
            int cur = n == null ? 0 : n;
            if (cur >= max) {
                return n;
            }
            granted[0] = true;
            return cur + 1;
        });
        if (!granted[0]) {
            return Optional.empty();
        }
        AtomicBoolean released = new AtomicBoolean();
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) {
                inUse.compute(key, (k, n) -> n == null || n <= 1 ? null : n - 1);
            }
        });
    }

    public int inUse(String key) {
        return inUse.getOrDefault(key, 0);
    }
}
