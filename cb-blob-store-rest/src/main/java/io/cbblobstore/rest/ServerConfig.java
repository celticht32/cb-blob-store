/*
 * ServerConfig.java — env-var-driven configuration for the REST sidecar
 *
 * Required vars are validated at startup; the server fails fast if anything
 * essential is missing. Auth must be explicitly disabled (CBBS_AUTH_DISABLED=true)
 * if no tokens are supplied, so a misconfigured deployment cannot serve
 * unauthenticated traffic by accident.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.rest;

import io.cbblobstore.core.SseMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Typed config bag. Sourced from environment variables — see RUNBOOK.md §5. */
final class ServerConfig {

    // ---- Couchbase ----
    final String couchbaseConnectionString;
    final String couchbaseUsername;
    final String couchbasePassword;
    final String couchbaseBucket;
    final String couchbaseScope;
    final String couchbaseCollection;
    final boolean capella;

    // ---- S3 ----
    final String s3Bucket;
    final String s3Region;
    final String s3Endpoint;
    final boolean s3PathStyle;
    final String s3AccessKey;
    final String s3SecretKey;
    final String s3KeyPrefix;
    final SseMode s3Sse;
    final String s3KmsKeyId;

    // ---- Analytics ----
    final String analyticsDataset;

    // ---- HTTP ----
    final int port;

    // ---- Security ----
    final Set<String> authTokens;
    final boolean authDisabled;
    final String tlsKeystorePath;
    final String tlsKeystorePassword;
    final int tlsPort;
    final long maxUploadBytes;
    final int rateLimitPerMinute;
    final List<String> corsAllowedOrigins;

    private ServerConfig() {
        // Couchbase
        this.couchbaseConnectionString = required("CBBS_CB_CONNECTION_STRING");
        this.couchbaseUsername         = required("CBBS_CB_USERNAME");
        this.couchbasePassword         = required("CBBS_CB_PASSWORD");
        this.couchbaseBucket           = env("CBBS_CB_BUCKET",     "blob_metadata");
        this.couchbaseScope            = env("CBBS_CB_SCOPE",      "_default");
        this.couchbaseCollection       = env("CBBS_CB_COLLECTION", "_default");
        this.capella                   = bool("CBBS_CB_CAPELLA", false);

        // S3
        this.s3Bucket    = required("CBBS_S3_BUCKET");
        this.s3Region    = env("CBBS_S3_REGION",    "us-east-1");
        this.s3Endpoint  = nullable("CBBS_S3_ENDPOINT");
        this.s3PathStyle = bool("CBBS_S3_PATH_STYLE", false);
        this.s3AccessKey = nullable("CBBS_S3_ACCESS_KEY");
        this.s3SecretKey = nullable("CBBS_S3_SECRET_KEY");
        this.s3KeyPrefix = env("CBBS_S3_KEY_PREFIX", "blobs/");
        this.s3Sse       = enumOf("CBBS_S3_SSE", SseMode.AES256, SseMode::valueOf);
        this.s3KmsKeyId  = nullable("CBBS_S3_KMS_KEY_ID");

        // Analytics
        this.analyticsDataset = env("CBBS_CB_ANALYTICS_DATASET", "blobs");

        // HTTP
        this.port = intEnv("CBBS_PORT", 8484);

        // Security
        this.authDisabled = bool("CBBS_AUTH_DISABLED", false);
        this.authTokens = parseTokens(nullable("CBBS_AUTH_TOKENS"));
        if (!authDisabled && authTokens.isEmpty()) {
            throw new IllegalStateException(
                    "CBBS_AUTH_TOKENS is required unless CBBS_AUTH_DISABLED=true (dev only)");
        }

        this.tlsKeystorePath     = nullable("CBBS_TLS_KEYSTORE_PATH");
        this.tlsKeystorePassword = nullable("CBBS_TLS_KEYSTORE_PASSWORD");
        this.tlsPort             = intEnv("CBBS_TLS_PORT", 8443);
        if (tlsKeystorePath != null && tlsKeystorePassword == null) {
            throw new IllegalStateException(
                    "CBBS_TLS_KEYSTORE_PATH set but CBBS_TLS_KEYSTORE_PASSWORD missing");
        }

        this.maxUploadBytes     = longEnv("CBBS_MAX_UPLOAD_BYTES", 5L * 1024 * 1024 * 1024); // 5 GiB
        this.rateLimitPerMinute = intEnv("CBBS_RATE_LIMIT_PER_MINUTE", 120);

        String origins = nullable("CBBS_CORS_ALLOWED_ORIGINS");
        this.corsAllowedOrigins = origins == null || origins.isBlank()
                ? Collections.emptyList()
                : Arrays.stream(origins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    static ServerConfig fromEnv() { return new ServerConfig(); }

    boolean tlsEnabled() { return tlsKeystorePath != null; }

    // ---- env-var helpers ----

    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? dflt : v;
    }

    private static String nullable(String key) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? null : v;
    }

    private static String required(String key) {
        String v = System.getenv(key);
        Objects.requireNonNull(v, "Required env var: " + key);
        if (v.isBlank()) throw new IllegalStateException("Required env var is blank: " + key);
        return v;
    }

    private static boolean bool(String key, boolean dflt) {
        String v = nullable(key);
        return v == null ? dflt : Boolean.parseBoolean(v);
    }

    private static int intEnv(String key, int dflt) {
        String v = nullable(key);
        if (v == null) return dflt;
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + key + " is not an integer: " + v, e);
        }
    }

    private static long longEnv(String key, long dflt) {
        String v = nullable(key);
        if (v == null) return dflt;
        try { return Long.parseLong(v); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("env var " + key + " is not a long: " + v, e);
        }
    }

    private static <E extends Enum<E>> E enumOf(String key, E dflt, Function<String, E> parser) {
        String v = nullable(key);
        if (v == null) return dflt;
        try { return parser.apply(v.toUpperCase()); }
        catch (IllegalArgumentException e) {
            throw new IllegalStateException("env var " + key + " has invalid value: " + v, e);
        }
    }

    private static Set<String> parseTokens(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String t : csv.split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }
}
