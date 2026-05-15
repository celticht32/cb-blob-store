/*
 * SlidingWindowRateLimiter.java — in-memory per-IP rate limiter
 *
 * Tracks request timestamps in a 60-second sliding window per remote address.
 * Lightweight and dependency-free. Suitable for single-instance deployments;
 * for multi-instance deployments fronted by a load balancer, configure rate
 * limiting at the LB layer instead.
 *
 * Eviction: a periodic sweep is triggered piggyback on tryAcquire calls (no
 * background thread) so the map cannot grow unboundedly with unique source
 * IPs. Entries are dropped once their deque is empty AND their last access
 * was more than 2 windows ago.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.rest;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Token-less sliding-window rate limiter. */
final class SlidingWindowRateLimiter {

    static final long WINDOW_MILLIS = 60_000L;

    /** An entry is evictable when idle this long with an empty deque. */
    static final long EVICTION_IDLE_MILLIS = 2 * WINDOW_MILLIS;

    /** Sweep evictable entries roughly every N tryAcquire calls. */
    private static final long SWEEP_EVERY = 1024L;

    private final int permitsPerWindow;
    private final ConcurrentMap<String, Entry> hits = new ConcurrentHashMap<>();
    private final AtomicLong callsSinceSweep = new AtomicLong();

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
        Entry e = hits.computeIfAbsent(key, k -> new Entry());
        boolean acquired;
        synchronized (e) {
            while (!e.queue.isEmpty() && nowMillis - e.queue.peekFirst() > WINDOW_MILLIS) {
                e.queue.removeFirst();
            }
            if (e.queue.size() >= permitsPerWindow) {
                acquired = false;
            } else {
                e.queue.addLast(nowMillis);
                acquired = true;
            }
            e.lastAccessMillis = nowMillis;
        }
        maybeSweep(nowMillis);
        return acquired;
    }

    /** Package-private hook for tests to force a sweep. */
    void sweepEvictable(long nowMillis) {
        Iterator<Map.Entry<String, Entry>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> kv = it.next();
            Entry e = kv.getValue();
            // Lock the entry briefly so a concurrent tryAcquire either sees the
            // entry present (and updates lastAccess, which we then re-read) or
            // creates a fresh one after our removal.
            boolean removable;
            synchronized (e) {
                removable = e.queue.isEmpty()
                        && (nowMillis - e.lastAccessMillis) > EVICTION_IDLE_MILLIS;
            }
            if (removable) {
                // Re-check under the map's atomic remove to avoid removing a
                // freshly-mutated entry.
                hits.compute(kv.getKey(), (k, v) -> {
                    if (v == null) return null;
                    synchronized (v) {
                        if (v.queue.isEmpty()
                                && (nowMillis - v.lastAccessMillis) > EVICTION_IDLE_MILLIS) {
                            return null; // remove
                        }
                        return v;
                    }
                });
            }
        }
    }

    /** Package-private: current tracked-key count, for tests. */
    int trackedKeys() {
        return hits.size();
    }

    private void maybeSweep(long nowMillis) {
        if (callsSinceSweep.incrementAndGet() >= SWEEP_EVERY) {
            callsSinceSweep.set(0L);
            sweepEvictable(nowMillis);
        }
    }

    /** Per-key state: the timestamp deque plus a last-access stamp for eviction. */
    private static final class Entry {
        final Deque<Long> queue = new ArrayDeque<>();
        long lastAccessMillis;
    }
}
