package org.kendar.dlna.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.testutil.MutableClock;

/** Password hashing, in-memory login sessions and the failure throttle. */
class SecurityPrimitivesTest {

    final MutableClock clock = new MutableClock();
    final SessionStore sessions = new SessionStore(clock);
    final PasswordHasher hasher = new PasswordHasher(1000, new SecureRandom());

    // ---- hashing ----

    @Test
    void hashesVerifyAndNeverRepeat() {
        String h1 = hasher.hash("correct horse");
        String h2 = hasher.hash("correct horse");
        assertThat(h1).startsWith("pbkdf2-sha256$1000$").isNotEqualTo(h2);   // random salt
        assertThat(hasher.verify("correct horse", h1)).isTrue();
        assertThat(hasher.verify("correct horse", h2)).isTrue();
        assertThat(hasher.verify("wrong", h1)).isFalse();
        assertThat(hasher.verify("", h1)).isFalse();
    }

    @Test
    void verifyUsesTheIterationsOfTheStoredHashAndSurvivesGarbage() {
        String old = new PasswordHasher(10, new SecureRandom()).hash("pw");
        assertThat(hasher.verify("pw", old)).isTrue();
        assertThat(hasher.verify("pw", null)).isFalse();
        assertThat(hasher.verify(null, old)).isFalse();
        for (String junk : new String[] { "", "plain", "pbkdf2-sha256$x$y$z", "pbkdf2-sha256$0$AA==$AA==",
                "pbkdf2-sha256$99999999$AA==$AA==", "md5$1$AA==$AA==", "pbkdf2-sha256$10$***$***" }) {
            assertThat(hasher.verify("pw", junk)).as(junk).isFalse();
        }
        assertThatThrownBy(() -> new PasswordHasher(0, new SecureRandom())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultIterationsAreStrong() {
        assertThat(PasswordHasher.DEFAULT_ITERATIONS).isGreaterThanOrEqualTo(210_000);
    }

    // ---- sessions ----

    @Test
    void sessionsExpireWhenIdleOrOld() {
        var s = sessions.create("u1");
        clock.advance(Duration.ofHours(7));
        assertThat(sessions.find(s.token())).isPresent();   // touched: idle timer restarts
        clock.advance(Duration.ofHours(7));
        assertThat(sessions.find(s.token())).isPresent();
        clock.advance(Duration.ofHours(7));
        assertThat(sessions.find(s.token())).isPresent();
        clock.advance(Duration.ofHours(7));
        assertThat(sessions.find(s.token())).as("older than 24 h in total").isEmpty();

        var t = sessions.create("u1");
        clock.advance(Duration.ofHours(9));
        assertThat(sessions.find(t.token())).as("idle for 9 h").isEmpty();
    }

    @Test
    void sessionTokensAreRandomAndUnknownOnesFail() {
        var a = sessions.create("u1");
        var b = sessions.create("u1");
        assertThat(a.token()).isNotEqualTo(b.token()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(a.csrfToken()).isNotEqualTo(a.token());
        assertThat(a.userId()).isEqualTo("u1");
        assertThat(sessions.find("forged")).isEmpty();
        assertThat(sessions.find(null)).isEmpty();
        assertThat(sessions.find("")).isEmpty();
        sessions.invalidate(a.token());
        assertThat(sessions.find(a.token())).isEmpty();
        sessions.invalidateAll();
        assertThat(sessions.size()).isZero();
    }

    @Test
    void sessionsCanBeEndedPerUser() {
        var a1 = sessions.create("a");
        var a2 = sessions.create("a");
        var b1 = sessions.create("b");
        sessions.invalidateUserExcept("a", a1.token());
        assertThat(sessions.find(a1.token())).isPresent();
        assertThat(sessions.find(a2.token())).isEmpty();
        sessions.invalidateUser("a");
        assertThat(sessions.find(a1.token())).isEmpty();
        assertThat(sessions.find(b1.token())).isPresent();
    }

    // ---- throttling ----

    @Test
    void throttleBlocksAnAddressAfterTooManyFailuresAndReleasesLater() {
        LoginThrottle t = new LoginThrottle(clock, 3, Duration.ofMinutes(10));
        for (int i = 0; i < 2; i++) {
            t.failure("1.1.1.1");
        }
        assertThat(t.blockedFor("1.1.1.1")).isEmpty();
        t.failure("1.1.1.1");
        assertThat(t.blockedFor("1.1.1.1")).hasValueSatisfying(d -> assertThat(d).isBetween(
                Duration.ofMinutes(9), Duration.ofMinutes(10)));
        assertThat(t.blockedFor("2.2.2.2")).as("other addresses are unaffected").isEmpty();

        clock.advance(Duration.ofMinutes(11));
        assertThat(t.blockedFor("1.1.1.1")).isEmpty();
    }

    @Test
    void successClearsTheFailures() {
        LoginThrottle t = new LoginThrottle(clock, 2, Duration.ofMinutes(10));
        t.failure("a");
        t.success("a");
        t.failure("a");
        assertThat(t.blockedFor("a")).isEmpty();
    }
}
