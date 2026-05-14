/*
 * BlobStoreMetricsTest.java — verify Micrometer instrumentation surface
 *
 * Uses a SimpleMeterRegistry so we can inspect counters/timers without a
 * Prometheus/JMX backend. Also verifies the no-op behavior when no registry
 * was supplied to the builder.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BlobStoreMetricsTest {

    @Test
    void nullRegistryProducesAQuietNoOp() {
        BlobStoreMetrics m = new BlobStoreMetrics(null);
        // None of these calls should throw.
        m.recordPutSuccess(123L, Duration.ofMillis(5));
        m.recordPutFailure(Duration.ofMillis(5));
        m.recordGetSuccess(Duration.ofMillis(5));
        m.recordGetNotFound(Duration.ofMillis(5));
        m.recordGetFailure(Duration.ofMillis(5));
        m.recordDeleteSuccess();
        m.recordDeleteNotFound();
        m.recordDeleteFailure();
        m.recordUpdateSuccess();
        m.recordUpdateNotFound();
        m.recordUpdateFailure();
        assertThat(m.enabled()).isFalse();
    }

    @Test
    void putSuccessRecordsCounterTimerAndBytes() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        BlobStoreMetrics m = new BlobStoreMetrics(reg);

        m.recordPutSuccess(1024L, Duration.ofMillis(10));

        assertThat(reg.counter("cbbs.put.count", "outcome", "success").count())
                .isEqualTo(1.0);
        assertThat(reg.timer("cbbs.put.duration", "outcome", "success").count())
                .isEqualTo(1L);
        assertThat(reg.summary("cbbs.put.bytes").totalAmount())
                .isEqualTo(1024.0);
    }

    @Test
    void putFailureIncrementsFailureCounterOnly() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        BlobStoreMetrics m = new BlobStoreMetrics(reg);

        m.recordPutFailure(Duration.ofMillis(3));

        assertThat(reg.counter("cbbs.put.count", "outcome", "failure").count())
                .isEqualTo(1.0);
        // success counter untouched
        assertThat(reg.counter("cbbs.put.count", "outcome", "success").count())
                .isEqualTo(0.0);
        // bytes summary untouched on failure
        assertThat(reg.summary("cbbs.put.bytes").count()).isZero();
    }

    @Test
    void getOutcomesAreSplitIntoSuccessNotFoundFailure() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        BlobStoreMetrics m = new BlobStoreMetrics(reg);

        m.recordGetSuccess(Duration.ofMillis(1));
        m.recordGetSuccess(Duration.ofMillis(1));
        m.recordGetNotFound(Duration.ofMillis(1));
        m.recordGetFailure(Duration.ofMillis(1));

        assertThat(reg.counter("cbbs.get.count", "outcome", "success").count()).isEqualTo(2.0);
        assertThat(reg.counter("cbbs.get.count", "outcome", "not_found").count()).isEqualTo(1.0);
        assertThat(reg.counter("cbbs.get.count", "outcome", "failure").count()).isEqualTo(1.0);
    }

    @Test
    void deleteAndUpdateCountersTrackIndependently() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        BlobStoreMetrics m = new BlobStoreMetrics(reg);

        m.recordDeleteSuccess();
        m.recordDeleteNotFound();
        m.recordUpdateSuccess();
        m.recordUpdateFailure();
        m.recordUpdateNotFound();

        assertThat(reg.counter("cbbs.delete.count", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(reg.counter("cbbs.delete.count", "outcome", "not_found").count()).isEqualTo(1.0);
        assertThat(reg.counter("cbbs.metadata.update.count", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(reg.counter("cbbs.metadata.update.count", "outcome", "failure").count()).isEqualTo(1.0);
        assertThat(reg.counter("cbbs.metadata.update.count", "outcome", "not_found").count()).isEqualTo(1.0);
    }
}
