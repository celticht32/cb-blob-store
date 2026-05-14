/*
 * BlobStoreBuilder.java — fluent builder for BlobStore instances
 *
 * Handles Capella vs self-hosted Couchbase, AWS S3 vs S3-compatible endpoints,
 * SSE configuration, optional Micrometer registry, and connection-lifecycle
 * cleanup on partial-build failure.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.env.ClusterEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Builds {@link BlobStore} instances.
 *
 * <p>Works against:</p>
 * <ul>
 *   <li>Couchbase Capella ({@code couchbases://} + {@link #capella(boolean)}=true).</li>
 *   <li>Self-hosted Couchbase Server 7.6 and 8.x.</li>
 *   <li>AWS S3 or any S3-compatible endpoint (MinIO, R2, Wasabi, Ceph).</li>
 * </ul>
 *
 * <p>SSE-S3 (AES-256) is the default. Override with {@link #s3ServerSideEncryption(SseMode)}.</p>
 *
 * <p>Pass a {@link MeterRegistry} via {@link #meterRegistry(MeterRegistry)} to
 * publish put/get/delete counters, upload/download timers, and total-bytes /
 * blob-count gauges. The metrics dependency is optional on the core library; if
 * you call this method, ensure {@code io.micrometer:micrometer-core} is on the
 * classpath.</p>
 */
public final class BlobStoreBuilder {

    private static final Logger log = LoggerFactory.getLogger(BlobStoreBuilder.class);

    /** S3 multipart minimum part size (protocol limit). */
    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;

    /** S3 multipart maximum part size (protocol limit). */
    public static final long MAX_PART_SIZE = 5L * 1024 * 1024 * 1024;

    // Couchbase
    private String couchbaseConnectionString;
    private String couchbaseUsername;
    private String couchbasePassword;
    private String couchbaseBucket = "blob_metadata";
    private String couchbaseScope = "_default";
    private String couchbaseCollection = "_default";
    private boolean capella = false;

    // S3
    private String s3Bucket;
    private String s3Region = "us-east-1";
    private String s3Endpoint;
    private boolean s3PathStyleAccess = false;
    private String s3AccessKey;
    private String s3SecretKey;
    private String s3KeyPrefix = "blobs/";
    private SseMode sseMode = SseMode.AES256;
    private String s3KmsKeyId;

    // Upload
    private long uploadPartSizeBytes = 8L * 1024 * 1024;

    // Analytics
    private String analyticsDataset = "blobs";

    // Metrics (optional)
    private MeterRegistry meterRegistry;

    BlobStoreBuilder() {}

    // ---------- Couchbase ----------

    public BlobStoreBuilder couchbaseConnectionString(String cs) { this.couchbaseConnectionString = cs; return this; }

    public BlobStoreBuilder couchbaseCredentials(String username, String password) {
        this.couchbaseUsername = username;
        this.couchbasePassword = password;
        return this;
    }

    public BlobStoreBuilder couchbaseBucket(String bucket)         { this.couchbaseBucket = bucket; return this; }
    public BlobStoreBuilder couchbaseScope(String scope)           { this.couchbaseScope = scope; return this; }
    public BlobStoreBuilder couchbaseCollection(String collection) { this.couchbaseCollection = collection; return this; }
    public BlobStoreBuilder capella(boolean capella)               { this.capella = capella; return this; }

    // ---------- S3 ----------

    public BlobStoreBuilder s3Bucket(String bucket)        { this.s3Bucket = bucket; return this; }
    public BlobStoreBuilder s3Region(String region)        { this.s3Region = region; return this; }
    public BlobStoreBuilder s3Endpoint(String endpoint)    { this.s3Endpoint = endpoint; return this; }
    public BlobStoreBuilder s3PathStyleAccess(boolean p)   { this.s3PathStyleAccess = p; return this; }

    /**
     * Optional static credentials.
     *
     * <p>Both must be non-null, or both must be null. Mixed input is rejected
     * because it almost always indicates a configuration mistake.</p>
     */
    public BlobStoreBuilder s3Credentials(String accessKey, String secretKey) {
        this.s3AccessKey = accessKey;
        this.s3SecretKey = secretKey;
        return this;
    }

    public BlobStoreBuilder s3KeyPrefix(String prefix) { this.s3KeyPrefix = prefix == null ? "" : prefix; return this; }

    /** Set SSE mode. Default is {@link SseMode#AES256}. */
    public BlobStoreBuilder s3ServerSideEncryption(SseMode mode) {
        this.sseMode = Objects.requireNonNull(mode, "sseMode");
        return this;
    }

    /** Required when {@code SseMode.KMS} is selected. */
    public BlobStoreBuilder s3KmsKeyId(String kmsKeyId) { this.s3KmsKeyId = kmsKeyId; return this; }

    /**
     * Multipart part size. Must be in [5 MiB, 5 GiB] per the S3 protocol.
     */
    public BlobStoreBuilder uploadPartSizeBytes(long bytes) {
        if (bytes < MIN_PART_SIZE || bytes > MAX_PART_SIZE) {
            throw new IllegalArgumentException(
                    "part size must be in [5 MiB, 5 GiB]; got " + bytes);
        }
        this.uploadPartSizeBytes = bytes;
        return this;
    }

    /**
     * Name (or fully-qualified reference) of the Couchbase Analytics dataset
     * holding blob metadata. Default is {@code "blobs"}. The dataset must be
     * created out-of-band — see the runbook §9. Pass a back-tick-quoted, dotted
     * reference (e.g. {@code "`blob_metadata`.scope1.collection1"}) if the
     * dataset lives outside the default dataverse.
     */
    public BlobStoreBuilder analyticsDataset(String dataset) {
        this.analyticsDataset = Objects.requireNonNull(dataset, "dataset");
        return this;
    }

    /**
     * Wire a Micrometer registry. When set, the BlobStore publishes:
     * <ul>
     *   <li>Counters: {@code cbbs.put.count}, {@code cbbs.get.count},
     *       {@code cbbs.delete.count}, all tagged {@code outcome={success|failure}}</li>
     *   <li>Timers: {@code cbbs.put.duration}, {@code cbbs.get.duration}</li>
     *   <li>Distribution summary: {@code cbbs.put.bytes} (bytes uploaded)</li>
     * </ul>
     * Pass {@code null} to disable (default).
     */
    public BlobStoreBuilder meterRegistry(MeterRegistry registry) {
        this.meterRegistry = registry;
        return this;
    }

    // ---------- Build ----------

    public BlobStore build() {
        Objects.requireNonNull(couchbaseConnectionString, "couchbaseConnectionString");
        Objects.requireNonNull(couchbaseUsername,         "couchbaseUsername");
        Objects.requireNonNull(couchbasePassword,         "couchbasePassword");
        Objects.requireNonNull(s3Bucket,                  "s3Bucket");

        // Credential coherence: both-or-neither.
        boolean accessKeySet = s3AccessKey != null && !s3AccessKey.isBlank();
        boolean secretKeySet = s3SecretKey != null && !s3SecretKey.isBlank();
        if (accessKeySet != secretKeySet) {
            throw new IllegalStateException(
                    "s3Credentials: both access and secret keys must be set, or both null");
        }
        if (sseMode == SseMode.KMS && (s3KmsKeyId == null || s3KmsKeyId.isBlank())) {
            throw new IllegalStateException("SSE-KMS requires s3KmsKeyId");
        }

        log.info("connecting to Couchbase cs={} bucket={} scope={} collection={} capella={}",
                couchbaseConnectionString, couchbaseBucket, couchbaseScope, couchbaseCollection, capella);

        ClusterEnvironment env = null;
        Cluster cluster = null;
        S3Client s3 = null;
        try {
            ClusterEnvironment.Builder envBuilder = ClusterEnvironment.builder();
            if (capella) {
                envBuilder.applyProfile("wan-development");
            }
            env = envBuilder.build();

            cluster = Cluster.connect(couchbaseConnectionString,
                    ClusterOptions.clusterOptions(couchbaseUsername, couchbasePassword)
                            .environment(env));
            cluster.waitUntilReady(Duration.ofSeconds(15));
            log.info("Couchbase cluster ready");

            s3 = buildS3Client(accessKeySet);
            log.info("S3 client ready region={} endpoint={} sse={}",
                    s3Region, s3Endpoint == null ? "default" : s3Endpoint, sseMode);

            return new CouchbaseS3BlobStore(
                    cluster, env,
                    couchbaseBucket, couchbaseScope, couchbaseCollection,
                    s3, s3Bucket, s3KeyPrefix, uploadPartSizeBytes,
                    sseMode, s3KmsKeyId, analyticsDataset, meterRegistry);

        } catch (RuntimeException e) {
            log.warn("BlobStore build failed; cleaning up partial state", e);
            // Clean up anything we partially constructed.
            if (s3 != null)       try { s3.close(); }       catch (RuntimeException ignored) {}
            if (cluster != null)  try { cluster.disconnect(); } catch (RuntimeException ignored) {}
            if (env != null)      try { env.shutdown(); }   catch (RuntimeException ignored) {}
            throw e;
        }
    }

    private S3Client buildS3Client(boolean staticCreds) {
        var s3Builder = S3Client.builder().region(Region.of(s3Region));

        if (s3Endpoint != null && !s3Endpoint.isBlank()) {
            s3Builder.endpointOverride(URI.create(s3Endpoint));
        }
        if (s3PathStyleAccess) {
            s3Builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
        }

        AwsCredentialsProvider creds = staticCreds
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(s3AccessKey, s3SecretKey))
                : DefaultCredentialsProvider.create();
        s3Builder.credentialsProvider(creds);

        return s3Builder.build();
    }
}
