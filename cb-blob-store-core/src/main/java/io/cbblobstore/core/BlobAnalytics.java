/*
 * BlobAnalytics.java — Couchbase Analytics service helpers for blob metadata
 *
 * Aggregate-style queries (count, total bytes, group-by, top-N, tag lookup)
 * over the metadata stored in Couchbase. Backed by `cluster.analyticsQuery(...)`
 * — the Analytics (formerly CBAS) service must be enabled on the cluster and
 * a dataset must exist for the metadata documents. The runbook walks through
 * dataset creation.
 *
 * All caller-supplied values (tag values, content types, paging limits) are
 * passed as positional parameters, never concatenated into the statement, to
 * keep arbitrary-input SQL++ injection off the table.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.analytics.AnalyticsOptions;
import com.couchbase.client.java.analytics.AnalyticsResult;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Analytics-service helpers for blob metadata.
 *
 * <p>Caller-supplied values flow through positional parameters. Tag <em>keys</em>
 * are validated against a strict allow-list and inlined; tag <em>values</em> are
 * always parameterized.</p>
 *
 * <p>Construct via {@link BlobStore#analytics()}; not directly.</p>
 */
public final class BlobAnalytics {

    /** Hard cap on result rows in any LIMIT-style query, regardless of caller input. */
    public static final int MAX_LIMIT = 10_000;

    private static final Pattern SAFE_TAG_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private final Cluster cluster;
    private final String dataset;

    BlobAnalytics(Cluster cluster, String dataset) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.dataset = Objects.requireNonNull(dataset, "dataset");
    }

    // ---------- aggregate counts ----------

    /** @return number of blob documents in the dataset */
    public long count() {
        AnalyticsResult r = cluster.analyticsQuery(
                "SELECT RAW count(*) FROM " + dataset + " WHERE type = 'blob'");
        List<Long> rows = r.rowsAs(Long.class);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /** @return summed {@code size} across all blob documents */
    public long totalBytes() {
        AnalyticsResult r = cluster.analyticsQuery(
                "SELECT RAW coalesce(sum(size), 0) FROM " + dataset + " WHERE type = 'blob'");
        List<Long> rows = r.rowsAs(Long.class);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /**
     * One-shot summary — count, total bytes, average bytes — in a single round trip.
     */
    public Summary summary() {
        AnalyticsResult r = cluster.analyticsQuery(
                "SELECT count(*) AS count, "
                + "       coalesce(sum(size), 0) AS total_bytes, "
                + "       coalesce(avg(size), 0) AS avg_bytes "
                + "FROM " + dataset + " WHERE type = 'blob'");
        List<JsonObject> rows = r.rowsAsObject();
        if (rows.isEmpty()) return new Summary(0L, 0L, 0L);
        JsonObject row = rows.get(0);
        return new Summary(
                row.getLong("count") == null ? 0L : row.getLong("count"),
                row.getLong("total_bytes") == null ? 0L : row.getLong("total_bytes"),
                row.getNumber("avg_bytes") == null ? 0L : row.getNumber("avg_bytes").longValue());
    }

    /** Aggregate summary across all blobs. */
    public record Summary(long count, long totalBytes, long avgBytes) {}

    // ---------- group-by ----------

    /**
     * @return blob count grouped by {@code contentType}; null content types
     *         appear under the key {@code "(unknown)"}.
     */
    public Map<String, Long> countByContentType() {
        AnalyticsResult r = cluster.analyticsQuery(
                "SELECT contentType, count(*) AS cnt "
                + "FROM " + dataset + " "
                + "WHERE type = 'blob' "
                + "GROUP BY contentType "
                + "ORDER BY cnt DESC");
        Map<String, Long> out = new LinkedHashMap<>();
        for (JsonObject row : r.rowsAsObject()) {
            String ct = row.getString("contentType");
            Long cnt = row.getLong("cnt");
            out.put(ct == null ? "(unknown)" : ct, cnt == null ? 0L : cnt);
        }
        return out;
    }

    // ---------- top-N ----------

    /**
     * @param limit max rows; clamped to {@value #MAX_LIMIT}
     * @return blob ids ordered by size descending
     */
    public List<String> largestBlobs(int limit) {
        int effectiveLimit = clampLimit(limit);
        AnalyticsResult r = cluster.analyticsQuery(
                "SELECT RAW id FROM " + dataset + " "
                        + "WHERE type = 'blob' "
                        + "ORDER BY size DESC LIMIT ?",
                AnalyticsOptions.analyticsOptions()
                        .parameters(JsonArray.from(effectiveLimit))
                        .readonly(true));
        return r.rowsAs(String.class);
    }

    // ---------- filters ----------

    /**
     * Find blob ids carrying a given tag.
     *
     * @param tagKey   tag name; must match {@code [A-Za-z0-9_-]+}
     * @param tagValue tag value; passed as a parameter, may contain any string
     */
    public List<String> byTag(String tagKey, String tagValue) {
        if (!SAFE_TAG_KEY.matcher(tagKey).matches()) {
            throw new IllegalArgumentException(
                    "tag key must match [A-Za-z0-9_-]+; got: " + tagKey);
        }
        AnalyticsResult r = cluster.analyticsQuery(
                "SELECT RAW id FROM " + dataset + " "
                        + "WHERE type = 'blob' AND tags.`" + tagKey + "` = ?",
                AnalyticsOptions.analyticsOptions()
                        .parameters(JsonArray.from(tagValue))
                        .readonly(true));
        return r.rowsAs(String.class);
    }

    /** Find blobs whose size, in bytes, exceeds {@code threshold}. */
    public List<String> largerThan(long thresholdBytes) {
        AnalyticsResult r = cluster.analyticsQuery(
                "SELECT RAW id FROM " + dataset + " "
                        + "WHERE type = 'blob' AND size > ? "
                        + "ORDER BY size DESC",
                AnalyticsOptions.analyticsOptions()
                        .parameters(JsonArray.from(thresholdBytes))
                        .readonly(true));
        return r.rowsAs(String.class);
    }

    /**
     * Find blobs created in {@code [from, to)} (inclusive start, exclusive end).
     * Times are compared as ISO-8601 strings.
     */
    public List<String> createdBetween(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        AnalyticsResult r = cluster.analyticsQuery(
                "SELECT RAW id FROM " + dataset + " "
                        + "WHERE type = 'blob' AND createdAt >= ? AND createdAt < ? "
                        + "ORDER BY createdAt ASC",
                AnalyticsOptions.analyticsOptions()
                        .parameters(JsonArray.from(from.toString(), to.toString()))
                        .readonly(true));
        return r.rowsAs(String.class);
    }

    // ---------- escape hatch ----------

    /**
     * Run an arbitrary Analytics query. The caller is responsible for the
     * statement; this method does not interpret or rewrite it.
     */
    public AnalyticsResult rawQuery(String statement) {
        return cluster.analyticsQuery(statement);
    }

    /** As {@link #rawQuery(String)} but with caller-supplied options. */
    public AnalyticsResult rawQuery(String statement, AnalyticsOptions options) {
        return cluster.analyticsQuery(statement, options);
    }

    /** Dataset name this handle is bound to. */
    public String dataset() {
        return dataset;
    }

    private static int clampLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        return Math.min(limit, MAX_LIMIT);
    }
}
