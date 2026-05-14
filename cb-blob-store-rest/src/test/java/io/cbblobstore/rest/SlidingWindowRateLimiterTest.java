/*
 * SlidingWindowRateLimiterTest.java — deterministic tests for the rate limiter
 *
 * Uses the package-private tryAcquireAt(key, nowMillis) hook to inject a clock
 * so tests don't depend on wall time.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.rest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlidingWindowRateLimiterTest {

    @Test
    void permitsUpToTheConfiguredCount() {
        SlidingWindowRateLimiter rl = new SlidingWindowRateLimiter(3);
        long t0 = 1_000_000L;

        assertThat(rl.tryAcquireAt("a", t0)).isTrue();
        assertThat(rl.tryAcquireAt("a", t0 + 1)).isTrue();
        assertThat(rl.tryAcquireAt("a", t0 + 2)).isTrue();
        // 4th hit in the same window is denied.
        assertThat(rl.tryAcquireAt("a", t0 + 3)).isFalse();
    }

    @Test
    void differentKeysAreTrackedSeparately() {
        SlidingWindowRateLimiter rl = new SlidingWindowRateLimiter(1);
        long t0 = 1_000_000L;

        assertThat(rl.tryAcquireAt("a", t0)).isTrue();
        assertThat(rl.tryAcquireAt("b", t0)).isTrue();   // different key, fresh quota
        assertThat(rl.tryAcquireAt("a", t0 + 1)).isFalse();
    }

    @Test
    void slidesWindowAndReleasesPermits() {
        SlidingWindowRateLimiter rl = new SlidingWindowRateLimiter(2);
        long t0 = 1_000_000L;

        assertThat(rl.tryAcquireAt("a", t0)).isTrue();
        assertThat(rl.tryAcquireAt("a", t0 + 100)).isTrue();
        assertThat(rl.tryAcquireAt("a", t0 + 200)).isFalse();   // still inside window

        // Advance past the first hit's window boundary.
        long afterFirstExpires = t0 + SlidingWindowRateLimiter.WINDOW_MILLIS + 1;
        assertThat(rl.tryAcquireAt("a", afterFirstExpires)).isTrue();
    }

    @Test
    void rejectsZeroOrNegativeConfigAtConstruction() {
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
