/*
 * BlobStoreMetrics.java — Micrometer instrumentation surface for the library
 *
 * Encapsulates counters, timers, and the distribution summary published when a
 * MeterRegistry is wired in. A no-op shape is used when none is configured, so
 * the rest of the code can call into this class unconditionally.
 *
 * Counter / timer naming follows Micrometer conventions:
 *   cbbs.put.count          — counter, tag outcome={success,failure}
 *   cbbs.put.duration       — timer  (records on success and failure)
 *   cbbs.put.bytes          — distribution summary, bytes uploaded
 *   cbbs.get.count          — counter, tag outcome={success,failure,not_found}
 *   cbbs.get.duration       — timer
 *   cbbs.delete.count       — counter, tag outcome={success,failure,not_found}
 *   cbbs.metadata.update.count — counter, tag outcome={success,failure,not_found}
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Single-purpose Micrometer instrumentation helper. Methods are safe to call
 * when the registry is null (they degrade to no-ops).
 */
final class BlobStoreMetrics {

    static final String PUT_COUNT     = "cbbs.put.count";
    static final String PUT_DURATION  = "cbbs.put.duration";
    static final String PUT_BYTES     = "cbbs.put.bytes";
    static final String GET_COUNT     = "cbbs.get.count";
    static final String GET_DURATION  = "cbbs.get.duration";
    static final String DELETE_COUNT  = "cbbs.delete.count";
    static final String UPDATE_COUNT  = "cbbs.metadata.update.count";

    private final MeterRegistry registry;
    private final Timer putSuccess;
    private final Timer putFailure;
    private final Timer getSuccess;
    private final Timer getFailure;
    private final Timer getNotFound;
    private final Counter putSuccessCount;
    private final Counter putFailureCount;
    private final Counter getSuccessCount;
    private final Counter getFailureCount;
    private final Counter getNotFoundCount;
    private final Counter deleteSuccess;
    private final Counter deleteFailure;
    private final Counter deleteNotFound;
    private final Counter updateSuccess;
    private final Counter updateFailure;
    private final Counter updateNotFound;
    private final DistributionSummary uploadBytes;

    BlobStoreMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry == null) {
            // All field references stay non-null via no-op stand-ins built from a
            // null registry call sequence; cheaper to just keep them null and
            // gate at the call sites below.
            this.putSuccess = null;
            this.putFailure = null;
            this.getSuccess = null;
            this.getFailure = null;
            this.getNotFound = null;
            this.putSuccessCount = null;
            this.putFailureCount = null;
            this.getSuccessCount = null;
            this.getFailureCount = null;
            this.getNotFoundCount = null;
            this.deleteSuccess = null;
            this.deleteFailure = null;
            this.deleteNotFound = null;
            this.updateSuccess = null;
            this.updateFailure = null;
            this.updateNotFound = null;
            this.uploadBytes = null;
            return;
        }
        this.putSuccess = Timer.builder(PUT_DURATION).tag("outcome", "success").register(registry);
        this.putFailure = Timer.builder(PUT_DURATION).tag("outcome", "failure").register(registry);
        this.getSuccess = Timer.builder(GET_DURATION).tag("outcome", "success").register(registry);
        this.getFailure = Timer.builder(GET_DURATION).tag("outcome", "failure").register(registry);
        this.getNotFound = Timer.builder(GET_DURATION).tag("outcome", "not_found").register(registry);
        this.putSuccessCount  = Counter.builder(PUT_COUNT).tag("outcome", "success").register(registry);
        this.putFailureCount  = Counter.builder(PUT_COUNT).tag("outcome", "failure").register(registry);
        this.getSuccessCount  = Counter.builder(GET_COUNT).tag("outcome", "success").register(registry);
        this.getFailureCount  = Counter.builder(GET_COUNT).tag("outcome", "failure").register(registry);
        this.getNotFoundCount = Counter.builder(GET_COUNT).tag("outcome", "not_found").register(registry);
        this.deleteSuccess    = Counter.builder(DELETE_COUNT).tag("outcome", "success").register(registry);
        this.deleteFailure    = Counter.builder(DELETE_COUNT).tag("outcome", "failure").register(registry);
        this.deleteNotFound   = Counter.builder(DELETE_COUNT).tag("outcome", "not_found").register(registry);
        this.updateSuccess    = Counter.builder(UPDATE_COUNT).tag("outcome", "success").register(registry);
        this.updateFailure    = Counter.builder(UPDATE_COUNT).tag("outcome", "failure").register(registry);
        this.updateNotFound   = Counter.builder(UPDATE_COUNT).tag("outcome", "not_found").register(registry);
        this.uploadBytes = DistributionSummary.builder(PUT_BYTES)
                .baseUnit("bytes")
                .register(registry);
    }

    boolean enabled() { return registry != null; }

    void recordPutSuccess(long bytes, Duration elapsed) {
        if (!enabled()) return;
        putSuccess.record(elapsed);
        putSuccessCount.increment();
        uploadBytes.record(bytes);
    }

    void recordPutFailure(Duration elapsed) {
        if (!enabled()) return;
        putFailure.record(elapsed);
        putFailureCount.increment();
    }

    void recordGetSuccess(Duration elapsed) {
        if (!enabled()) return;
        getSuccess.record(elapsed);
        getSuccessCount.increment();
    }

    void recordGetNotFound(Duration elapsed) {
        if (!enabled()) return;
        getNotFound.record(elapsed);
        getNotFoundCount.increment();
    }

    void recordGetFailure(Duration elapsed) {
        if (!enabled()) return;
        getFailure.record(elapsed);
        getFailureCount.increment();
    }

    void recordDeleteSuccess() { if (enabled()) deleteSuccess.increment(); }
    void recordDeleteNotFound() { if (enabled()) deleteNotFound.increment(); }
    void recordDeleteFailure() { if (enabled()) deleteFailure.increment(); }

    void recordUpdateSuccess() { if (enabled()) updateSuccess.increment(); }
    void recordUpdateNotFound() { if (enabled()) updateNotFound.increment(); }
    void recordUpdateFailure() { if (enabled()) updateFailure.increment(); }
}
