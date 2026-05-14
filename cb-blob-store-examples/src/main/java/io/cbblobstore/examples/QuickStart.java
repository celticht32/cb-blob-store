/*
 * QuickStart.java — End-to-end put+get demonstration
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.examples;

import io.cbblobstore.core.BlobAnalytics;
import io.cbblobstore.core.BlobMetadata;
import io.cbblobstore.core.BlobOptions;
import io.cbblobstore.core.BlobStore;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal end-to-end demonstration of the "easy add + retrieve" API.
 *
 * <p>Run with the {@code CBBS_*} env vars set (see RUNBOOK.md), and pass a
 * filename as the only argument.</p>
 */
public final class QuickStart {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: QuickStart <file>");
            System.exit(2);
        }
        Path file = Path.of(args[0]);

        try (BlobStore store = BlobStore.builder()
                .couchbaseConnectionString(env("CBBS_CB_CONNECTION_STRING"))
                .couchbaseCredentials(env("CBBS_CB_USERNAME"), env("CBBS_CB_PASSWORD"))
                .couchbaseBucket(envOr("CBBS_CB_BUCKET", "blob_metadata"))
                .capella(Boolean.parseBoolean(envOr("CBBS_CB_CAPELLA", "false")))
                .s3Bucket(env("CBBS_S3_BUCKET"))
                .s3Region(envOr("CBBS_S3_REGION", "us-east-1"))
                .build()) {

            // ---- ADD (with custom metadata) ----
            BlobMetadata meta = store.put(file, BlobOptions.builder()
                    .name(file.getFileName().toString())
                    .contentType(probeContentType(file))
                    .owner("alice@example.com")
                    .project("research-q2")
                    .retentionUntil(java.time.Instant.now()
                            .plus(java.time.Duration.ofDays(365)))
                    .tag("source", "quickstart")
                    .tag("env", "prod")
                    .attribute("schemaVersion", 2)
                    .attribute("source-detail",
                            java.util.Map.of("kind", "ingest", "node", "ingest-01"))
                    .build());

            System.out.println("uploaded id=" + meta.id()
                    + " size=" + meta.size()
                    + " sha256=" + meta.sha256()
                    + " owner=" + meta.owner()
                    + " project=" + meta.project());

            // ---- RETRIEVE ----
            try (InputStream in = store.get(meta.id())) {
                Path out = Path.of(file.getFileName().toString() + ".roundtrip");
                long copied = Files.copy(in, out);
                System.out.println("downloaded " + copied + " bytes to " + out);
            }

            // ---- ANALYTICS (optional) ----
            // Skipped if the Analytics service / dataset isn't set up — the SDK
            // surfaces a CouchbaseException with a clear message in that case.
            try {
                BlobAnalytics analytics = store.analytics();
                BlobAnalytics.Summary s = analytics.summary();
                System.out.println("analytics: count=" + s.count()
                        + " totalBytes=" + s.totalBytes()
                        + " avgBytes=" + s.avgBytes());
            } catch (RuntimeException e) {
                System.out.println("analytics skipped: " + e.getMessage());
            }
        }
    }

    private static String probeContentType(Path file) throws Exception {
        String t = Files.probeContentType(file);
        return t == null ? "application/octet-stream" : t;
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env var: " + key);
        }
        return v;
    }

    private static String envOr(String key, String dflt) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? dflt : v;
    }
}
