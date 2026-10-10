package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeyedLimiterTest {

    @Test
    void grantsAtMostMaxPerKeyAndReleasesOnce() {
        KeyedLimiter l = new KeyedLimiter(2);
        var a = l.tryAcquire("u1").orElseThrow();
        var b = l.tryAcquire("u1").orElseThrow();
        assertThat(l.tryAcquire("u1")).isEmpty();
        assertThat(l.tryAcquire("u2")).isPresent();   // other keys are independent
        a.close();
        a.close();   // idempotent: does not free a second place
        assertThat(l.inUse("u1")).isEqualTo(1);
        var c = l.tryAcquire("u1").orElseThrow();
        assertThat(l.tryAcquire("u1")).isEmpty();
        b.close();
        c.close();
        assertThat(l.inUse("u1")).isZero();
    }
}
