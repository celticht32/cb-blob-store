/*
 * SlidingWindowRateLimiter.java — in-memory per-IP rate limiter
 *
 * Tracks request timestamps in a 60-second sliding window per remote address.
 * Lightweight and dependency-free. Suitable for single-instance deployments;
 * for multi-instance deployments fronted by a load balancer, configure rate
 * limiting at the LB layer instead.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.rest;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Token-less sliding-window rate limiter. */
final class SlidingWindowRateLimiter {

    static final long WINDOW_MILLIS = 60_000L;

    private final int permitsPerWindow;
    private final ConcurrentMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    SlidingWindowRateLimiter(int permitsPerWindow) {
        if (permitsPerWindow < 1) {
            throw new IllegalArgumentException("permitsPerWindow must be >= 1");
        }
        this.permitsPerWindow = permitsPerWindow;
    }

    boolean tryAcquire(String key) {
        return tryAcquireAt(key, System.currentTimeMillis());
    }

    /** Package-private for testing with a fixed clock. */
    boolean tryAcquireAt(String key, long nowMillis) {
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && nowMillis - q.peekFirst() > WINDOW_MILLIS) {
                q.removeFirst();
            }
            if (q.size() >= permitsPerWindow) return false;
            q.addLast(nowMillis);
            return true;
        }
    }
}
