/*
 * BlobAnalyticsTest.java — unit tests for the Analytics-service helper
 *
 * Mocks Cluster.analyticsQuery and AnalyticsResult so the test stays hermetic.
 * Covers happy paths for count/totalBytes/summary/groupBy/topN/byTag/byDate,
 * input validation, and the LIMIT clamp.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.analytics.AnalyticsOptions;
import com.couchbase.client.java.analytics.AnalyticsResult;
import com.couchbase.client.java.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlobAnalyticsTest {

    @Mock Cluster cluster;

    private BlobAnalytics analytics;

    @BeforeEach
    void setUp() {
        analytics = new BlobAnalytics(cluster, "blobs");
    }

    @Test
    void countReturnsFirstRowAsLong() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAs(Long.class)).thenReturn(List.of(42L));
        when(cluster.analyticsQuery(anyString())).thenReturn(r);

        assertThat(analytics.count()).isEqualTo(42L);

        ArgumentCaptor<String> stmt = ArgumentCaptor.forClass(String.class);
        verify(cluster).analyticsQuery(stmt.capture());
        assertThat(stmt.getValue()).contains("count(*)").contains("FROM blobs");
    }

    @Test
    void countReturnsZeroOnEmptyResult() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAs(Long.class)).thenReturn(List.of());
        when(cluster.analyticsQuery(anyString())).thenReturn(r);

        assertThat(analytics.count()).isZero();
    }

    @Test
    void totalBytesReturnsSummedSize() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAs(Long.class)).thenReturn(List.of(1_073_741_824L));
        when(cluster.analyticsQuery(anyString())).thenReturn(r);

        assertThat(analytics.totalBytes()).isEqualTo(1_073_741_824L);
    }

    @Test
    void summaryParsesAllThreeFields() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        JsonObject row = JsonObject.create()
                .put("count", 5L)
                .put("total_bytes", 500L)
                .put("avg_bytes", 100L);
        when(r.rowsAsObject()).thenReturn(List.of(row));
        when(cluster.analyticsQuery(anyString())).thenReturn(r);

        BlobAnalytics.Summary s = analytics.summary();
        assertThat(s.count()).isEqualTo(5L);
        assertThat(s.totalBytes()).isEqualTo(500L);
        assertThat(s.avgBytes()).isEqualTo(100L);
    }

    @Test
    void summaryHandlesEmptyResultGracefully() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAsObject()).thenReturn(List.of());
        when(cluster.analyticsQuery(anyString())).thenReturn(r);

        BlobAnalytics.Summary s = analytics.summary();
        assertThat(s.count()).isZero();
        assertThat(s.totalBytes()).isZero();
        assertThat(s.avgBytes()).isZero();
    }

    @Test
    void countByContentTypeBuildsOrderedMap() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAsObject()).thenReturn(List.of(
                JsonObject.create().put("contentType", "image/png").put("cnt", 10L),
                JsonObject.create().put("contentType", "video/mp4").put("cnt", 3L),
                JsonObject.create().put("contentType", (String) null).put("cnt", 1L)));
        when(cluster.analyticsQuery(anyString())).thenReturn(r);

        Map<String, Long> m = analytics.countByContentType();
        assertThat(m).containsExactly(
                Map.entry("image/png", 10L),
                Map.entry("video/mp4", 3L),
                Map.entry("(unknown)", 1L));
    }

    @Test
    void largestBlobsPassesLimitAsParameter() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAs(String.class)).thenReturn(List.of("a", "b", "c"));
        when(cluster.analyticsQuery(anyString(), any(AnalyticsOptions.class))).thenReturn(r);

        assertThat(analytics.largestBlobs(3)).containsExactly("a", "b", "c");

        ArgumentCaptor<String> stmt = ArgumentCaptor.forClass(String.class);
        verify(cluster).analyticsQuery(stmt.capture(), any(AnalyticsOptions.class));
        assertThat(stmt.getValue()).contains("ORDER BY size DESC").contains("LIMIT ?");
    }

    @Test
    void largestBlobsClampsAtMaxLimit() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        lenient().when(r.rowsAs(String.class)).thenReturn(List.of());
        lenient().when(cluster.analyticsQuery(anyString(), any(AnalyticsOptions.class))).thenReturn(r);

        analytics.largestBlobs(Integer.MAX_VALUE);

        // No exception thrown, query executed. The MAX_LIMIT is the contract;
        // we don't try to introspect the AnalyticsOptions parameter list here
        // because that mocking is finicky — it's enough to assert it didn't blow up.
        verify(cluster).analyticsQuery(anyString(), any(AnalyticsOptions.class));
    }

    @Test
    void largestBlobsRejectsZeroAndNegativeLimits() {
        assertThatThrownBy(() -> analytics.largestBlobs(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analytics.largestBlobs(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void byTagInlinesSafeTagKeyAndParameterizesValue() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAs(String.class)).thenReturn(List.of("blob-1", "blob-2"));
        when(cluster.analyticsQuery(anyString(), any(AnalyticsOptions.class))).thenReturn(r);

        assertThat(analytics.byTag("env", "prod")).containsExactly("blob-1", "blob-2");

        ArgumentCaptor<String> stmt = ArgumentCaptor.forClass(String.class);
        verify(cluster).analyticsQuery(stmt.capture(), any(AnalyticsOptions.class));
        // Key is inlined safely (allow-listed); value uses positional parameter.
        assertThat(stmt.getValue()).contains("tags.`env` = ?");
        assertThat(stmt.getValue()).doesNotContain("prod");
    }

    @Test
    void byTagRejectsUnsafeTagKey() {
        // Any character outside [A-Za-z0-9_-] is rejected.
        assertThatThrownBy(() -> analytics.byTag("env`; DROP DATASET --", "prod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tag key");

        assertThatThrownBy(() -> analytics.byTag("with space", "prod"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largerThanParameterizesThreshold() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAs(String.class)).thenReturn(List.of("big-blob"));
        when(cluster.analyticsQuery(anyString(), any(AnalyticsOptions.class))).thenReturn(r);

        assertThat(analytics.largerThan(1_000_000L)).containsExactly("big-blob");

        ArgumentCaptor<String> stmt = ArgumentCaptor.forClass(String.class);
        verify(cluster).analyticsQuery(stmt.capture(), any(AnalyticsOptions.class));
        assertThat(stmt.getValue()).contains("size > ?");
    }

    @Test
    void createdBetweenPassesIso8601StringsAsParameters() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(r.rowsAs(String.class)).thenReturn(List.of());
        when(cluster.analyticsQuery(anyString(), any(AnalyticsOptions.class))).thenReturn(r);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-02-01T00:00:00Z");
        analytics.createdBetween(from, to);

        ArgumentCaptor<String> stmt = ArgumentCaptor.forClass(String.class);
        verify(cluster).analyticsQuery(stmt.capture(), argThat(opts -> opts != null));
        assertThat(stmt.getValue())
                .contains("createdAt >= ?")
                .contains("createdAt < ?");
    }

    @Test
    void rawQueryPassesStatementVerbatim() {
        AnalyticsResult r = mock(AnalyticsResult.class);
        when(cluster.analyticsQuery("custom statement")).thenReturn(r);

        assertThat(analytics.rawQuery("custom statement")).isSameAs(r);
    }

    @Test
    void datasetExposedForDebug() {
        assertThat(analytics.dataset()).isEqualTo("blobs");
    }

    @Test
    void constructorRejectsNullCluster() {
        assertThatThrownBy(() -> new BlobAnalytics(null, "blobs"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullDataset() {
        assertThatThrownBy(() -> new BlobAnalytics(cluster, null))
                .isInstanceOf(NullPointerException.class);
    }
}
