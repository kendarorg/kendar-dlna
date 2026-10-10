package org.kendar.dlna.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login throttling per client IP: after {@code maxFailures} failed attempts within {@code window} the address is
 * blocked until the oldest of those failures leaves the window. A success clears the record.
 */
public final class LoginThrottle {

    public static final int DEFAULT_MAX_FAILURES = 5;
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginThrottle(Clock clock) {
        this(clock, DEFAULT_MAX_FAILURES, DEFAULT_WINDOW);
    }

    public LoginThrottle(Clock clock, int maxFailures, Duration window) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** How long the address must wait before another attempt, empty when it may try now. */
    public Optional<Duration> blockedFor(String ip) {
        Deque<Instant> d = failures.get(ip);
        if (d == null) {
            return Optional.empty();
        }
        synchronized (d) {
            prune(d);
            if (d.size() < maxFailures) {
                return Optional.empty();
            }
            Duration wait = Duration.between(clock.instant(), d.peekFirst().plus(window));
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
        }
    }

    public void failure(String ip) {
        if (failures.size() > MAX_TRACKED) {
            failures.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    prune(e.getValue());
                    return e.getValue().isEmpty();
                }
            });
        }
        Deque<Instant> d = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (d) {
            prune(d);
            d.addLast(clock.instant());
        }
    }

    public void success(String ip) {
        failures.remove(ip);
    }

    private void prune(Deque<Instant> d) {
        Instant limit = clock.instant().minus(window);
        while (!d.isEmpty() && d.peekFirst().isBefore(limit)) {
            d.removeFirst();
        }
    }
}
