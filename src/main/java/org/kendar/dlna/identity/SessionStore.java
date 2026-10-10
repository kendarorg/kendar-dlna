package org.kendar.dlna.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory login sessions with idle and absolute timeouts. The CSRF token of a session lives here. */
public final class SessionStore {

    public record Session(String token, String userId, String csrfToken, Instant createdAt, Instant lastSeen) {
    }

    public static final Duration DEFAULT_IDLE = Duration.ofHours(8);
    public static final Duration DEFAULT_MAX_AGE = Duration.ofHours(24);

    private final Clock clock;
    private final Duration idle;
    private final Duration maxAge;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SessionStore(Clock clock) {
        this(clock, DEFAULT_IDLE, DEFAULT_MAX_AGE);
    }

    public SessionStore(Clock clock, Duration idle, Duration maxAge) {
        this.clock = clock;
        this.idle = idle;
        this.maxAge = maxAge;
    }

    public Session create(String userId) {
        sweep();
        Instant now = clock.instant();
        Session s = new Session(randomToken(), userId, randomToken(), now, now);
        sessions.put(s.token(), s);
        return s;
    }

    /** The live session for a token (touching it), empty when unknown or expired. */
    public Optional<Session> find(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        Session s = sessions.get(token);
        if (s == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (expired(s, now)) {
            sessions.remove(token);
            return Optional.empty();
        }
        Session touched = new Session(s.token(), s.userId(), s.csrfToken(), s.createdAt(), now);
        sessions.replace(token, s, touched);
        return Optional.of(touched);
    }

    public void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** Ends every session except the given one (after a password change). */
    public void invalidateAllExcept(String keepToken) {
        sessions.keySet().removeIf(t -> !t.equals(keepToken));
    }

    /** Ends every session of one user (disable, delete, password reset). */
    public void invalidateUser(String userId) {
        sessions.values().removeIf(s -> s.userId().equals(userId));
    }

    /** Ends every session of one user except the given one (password change). */
    public void invalidateUserExcept(String userId, String keepToken) {
        sessions.values().removeIf(s -> s.userId().equals(userId) && !s.token().equals(keepToken));
    }

    public void invalidateAll() {
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }

    public Duration maxAge() {
        return maxAge;
    }

    private boolean expired(Session s, Instant now) {
        return Duration.between(s.lastSeen(), now).compareTo(idle) > 0
                || Duration.between(s.createdAt(), now).compareTo(maxAge) > 0;
    }

    private void sweep() {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> expired(s, now));
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
