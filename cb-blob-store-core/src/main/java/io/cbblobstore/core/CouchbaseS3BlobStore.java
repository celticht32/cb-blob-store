/*
 * CouchbaseS3BlobStore.java — default BlobStore implementation
 *
 * Streams uploads via S3 multipart with SSE applied per the builder's SseMode.
 * Computes SHA-256 during upload. Empty streams take a non-multipart path so
 * S3 does not reject "complete with zero parts".
 *
 * Wires the per-call BlobOptions (name, owner, project, retentionUntil, tags,
 * attributes) into BlobMetadata on insert and replace. When a MeterRegistry
 * was supplied to the builder, every public method records counters and
 * timers via the dedicated BlobStoreMetrics helper.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.env.ClusterEnvironment;
import io.cbblobstore.core.internal.MetadataRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class CouchbaseS3BlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(CouchbaseS3BlobStore.class);

    private final Cluster cluster;
    private final ClusterEnvironment env;
    private final MetadataRepository metadata;

    private final S3Client s3;
    private final String s3Bucket;
    private final String s3KeyPrefix;
    private final int partSize;          // already validated int-safe
    private final SseMode sseMode;
    private final String s3KmsKeyId;
    private final String analyticsDataset;
    private final BlobStoreMetrics metrics;

    private final AtomicBoolean closed = new AtomicBoolean();

    /** Production constructor (used by {@link BlobStoreBuilder}). */
    CouchbaseS3BlobStore(Cluster cluster,
                         ClusterEnvironment env,
                         String cbBucket,
                         String cbScope,
                         String cbCollection,
                         S3Client s3,
                         String s3Bucket,
                         String s3KeyPrefix,
                         long partSizeBytes,
                         SseMode sseMode,
                         String s3KmsKeyId,
                         String analyticsDataset,
                         MeterRegistry meterRegistry) {
        this(cluster, env,
                cluster.bucket(cbBucket).scope(cbScope).collection(cbCollection),
                s3, s3Bucket, s3KeyPrefix, partSizeBytes, sseMode, s3KmsKeyId,
                analyticsDataset, meterRegistry);
    }

    /** Test constructor — accepts an already-resolved {@link Collection}. */
    CouchbaseS3BlobStore(Cluster cluster,
                         ClusterEnvironment env,
                         Collection collection,
                         S3Client s3,
                         String s3Bucket,
                         String s3KeyPrefix,
                         long partSizeBytes,
                         SseMode sseMode,
                         String s3KmsKeyId,
                         String analyticsDataset,
                         MeterRegistry meterRegistry) {
        if (partSizeBytes > Integer.MAX_VALUE) {
            // Should have been blocked at the builder, but guard anyway.
            throw new IllegalArgumentException("part size too large: " + partSizeBytes);
        }
        this.cluster = cluster;
        this.env = env;
        this.metadata = new MetadataRepository(collection);
        this.s3 = s3;
        this.s3Bucket = s3Bucket;
        this.s3KeyPrefix = s3KeyPrefix;
        this.partSize = (int) partSizeBytes;
        this.sseMode = sseMode == null ? SseMode.AES256 : sseMode;
        this.s3KmsKeyId = s3KmsKeyId;
        this.analyticsDataset = analyticsDataset == null ? "blobs" : analyticsDataset;
        this.metrics = new BlobStoreMetrics(meterRegistry);
    }

    // ---------- PUT ----------

    @Override
    public BlobMetadata put(InputStream data, BlobOptions options) throws IOException {
        String id = options.explicitId() != null
                ? IdValidator.checked(options.explicitId())
                : UUID.randomUUID().toString();
        String key = s3KeyPrefix + id;
        long startNanos = System.nanoTime();

        try (var mdc = MDC.putCloseable("blobId", id)) {
            log.debug("put start id={} key={} contentType={} owner={} project={}",
                    id, key, options.contentType(), options.owner(), options.project());

            MessageDigest sha = newSha256();

            // Peek for empty stream to avoid the multipart-with-zero-parts pitfall.
            byte[] firstByte = new byte[1];
            int firstRead;
            try {
                firstRead = data.read(firstByte, 0, 1);
            } catch (IOException e) {
                try { data.close(); } catch (IOException ignored) {}
                metrics.recordPutFailure(elapsedSince(startNanos));
                throw e;
            }
            if (firstRead < 0) {
                // Empty payload — write a zero-length S3 object directly.
                try { data.close(); } catch (IOException ignored) {}
                try {
                    BlobMetadata m = putBytes(id, key, new byte[0], options);
                    metrics.recordPutSuccess(0L, elapsedSince(startNanos));
                    return m;
                } catch (RuntimeException re) {
                    metrics.recordPutFailure(elapsedSince(startNanos));
                    throw re;
                }
            }

            // Single multipart upload with SSE applied at create time.
            CreateMultipartUploadRequest.Builder createReq = CreateMultipartUploadRequest.builder()
                    .bucket(s3Bucket)
                    .key(key)
                    .contentType(options.contentType());
            applySse(createReq);
            String uploadId = s3.createMultipartUpload(createReq.build()).uploadId();
            log.debug("multipart created uploadId={}", uploadId);

            long totalBytes = 0;
            List<CompletedPart> completedParts = new ArrayList<>();
            try {
                byte[] buffer = new byte[partSize];

                // Seed the buffer with the byte we already read while peeking.
                buffer[0] = firstByte[0];
                int filled = 1 + readUpTo(data, buffer, 1);
                sha.update(buffer, 0, filled);
                totalBytes += filled;
                int partNumber = 1;
                completedParts.add(uploadOnePart(key, uploadId, partNumber, buffer, filled));

                // Subsequent parts.
                while (filled == buffer.length) {
                    partNumber++;
                    filled = readUpTo(data, buffer, 0);
                    if (filled == 0) break;
                    sha.update(buffer, 0, filled);
                    totalBytes += filled;
                    completedParts.add(uploadOnePart(key, uploadId, partNumber, buffer, filled));
                }

                s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                        .bucket(s3Bucket).key(key).uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                        .build());
                log.debug("multipart complete parts={} bytes={}", completedParts.size(), totalBytes);

            } catch (RuntimeException | IOException e) {
                log.warn("put failed for id={}, aborting multipart upload", id, e);
                safeAbort(key, uploadId);
                metrics.recordPutFailure(elapsedSince(startNanos));
                if (e instanceof IOException ioe) throw ioe;
                throw (RuntimeException) e;
            } finally {
                try { data.close(); } catch (IOException ignored) {}
            }

            Instant now = Instant.now();
            BlobMetadata meta = buildMetadata(id, key, totalBytes,
                    HexFormat.of().formatHex(sha.digest()), options, now);
            try {
                metadata.insert(meta);
            } catch (RuntimeException re) {
                metrics.recordPutFailure(elapsedSince(startNanos));
                throw re;
            }
            log.info("put complete id={} size={} sha256={}", id, totalBytes, meta.sha256());
            metrics.recordPutSuccess(totalBytes, elapsedSince(startNanos));
            return meta;
        }
    }

    @Override
    public BlobMetadata put(Path file, BlobOptions options) throws IOException {
        long size = Files.size(file);
        if (size <= partSize) {
            String id = options.explicitId() != null
                    ? IdValidator.checked(options.explicitId())
                    : UUID.randomUUID().toString();
            String key = s3KeyPrefix + id;
            long startNanos = System.nanoTime();
            try (var mdc = MDC.putCloseable("blobId", id)) {
                byte[] bytes = Files.readAllBytes(file);
                try {
                    BlobMetadata m = putBytes(id, key, bytes, options);
                    metrics.recordPutSuccess(bytes.length, elapsedSince(startNanos));
                    return m;
                } catch (RuntimeException re) {
                    metrics.recordPutFailure(elapsedSince(startNanos));
                    throw re;
                }
            }
        }
        try (InputStream in = Files.newInputStream(file)) {
            return put(in, options);
        }
    }

    private BlobMetadata putBytes(String id, String key, byte[] bytes, BlobOptions options) {
        String sha256 = HexFormat.of().formatHex(newSha256().digest(bytes));

        PutObjectRequest.Builder req = PutObjectRequest.builder()
                .bucket(s3Bucket).key(key)
                .contentType(options.contentType())
                .contentLength((long) bytes.length);
        applySse(req);

        s3.putObject(req.build(), RequestBody.fromBytes(bytes));

        Instant now = Instant.now();
        BlobMetadata meta = buildMetadata(id, key, bytes.length, sha256, options, now);
        metadata.insert(meta);
        log.info("put complete (non-multipart) id={} size={} sha256={}", id, bytes.length, sha256);
        return meta;
    }

    private BlobMetadata buildMetadata(String id, String key, long size, String sha256,
                                       BlobOptions options, Instant now) {
        return new BlobMetadata(
                id, options.name(), options.contentType(), size, sha256,
                s3Bucket, key, now, now,
                options.tags(),
                options.owner(), options.project(), options.retentionUntil(),
                options.attributes());
    }

    private CompletedPart uploadOnePart(String key, String uploadId, int partNumber, byte[] buffer, int len) {
        UploadPartRequest req = UploadPartRequest.builder()
                .bucket(s3Bucket).key(key).uploadId(uploadId)
                .partNumber(partNumber).contentLength((long) len)
                .build();
        byte[] payload = len == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, len);
        String etag = s3.uploadPart(req, RequestBody.fromBytes(payload)).eTag();
        log.trace("uploaded part num={} bytes={} etag={}", partNumber, len, etag);
        return CompletedPart.builder().partNumber(partNumber).eTag(etag).build();
    }

    // ---------- GET / DELETE ----------

    @Override
    public InputStream get(String blobId) {
        IdValidator.requireValid(blobId);
        long startNanos = System.nanoTime();
        try (var mdc = MDC.putCloseable("blobId", blobId)) {
            log.debug("get start");
            BlobMetadata meta = metadata.findById(blobId)
                    .orElseThrow(() -> {
                        metrics.recordGetNotFound(elapsedSince(startNanos));
                        log.debug("get not found");
                        return new BlobStoreException.NotFound(blobId);
                    });
            try {
                InputStream stream = s3.getObject(GetObjectRequest.builder()
                        .bucket(meta.s3Bucket()).key(meta.s3Key()).build());
                metrics.recordGetSuccess(elapsedSince(startNanos));
                log.info("get streaming bucket={} key={} size={}", meta.s3Bucket(), meta.s3Key(), meta.size());
                return stream;
            } catch (NoSuchKeyException e) {
                metrics.recordGetFailure(elapsedSince(startNanos));
                log.warn("get failed — S3 object missing for blob id={} bucket={} key={}",
                        blobId, meta.s3Bucket(), meta.s3Key());
                throw new BlobStoreException(
                        "S3 object missing for blob " + blobId
                                + " (metadata points to " + meta.s3Bucket() + "/" + meta.s3Key() + ")", e);
            } catch (RuntimeException e) {
                metrics.recordGetFailure(elapsedSince(startNanos));
                throw e;
            }
        }
    }

    @Override
    public Optional<BlobMetadata> getMetadata(String blobId) {
        IdValidator.requireValid(blobId);
        try (var mdc = MDC.putCloseable("blobId", blobId)) {
            return metadata.findById(blobId);
        }
    }

    @Override
    public void delete(String blobId) {
        IdValidator.requireValid(blobId);
        try (var mdc = MDC.putCloseable("blobId", blobId)) {
            log.debug("delete start");
            Optional<BlobMetadata> opt = metadata.findById(blobId);
            if (opt.isEmpty()) {
                metrics.recordDeleteNotFound();
                log.debug("delete miss — no metadata");
                return;
            }
            BlobMetadata meta = opt.get();
            try {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(meta.s3Bucket()).key(meta.s3Key()).build());
                metadata.delete(blobId);
                metrics.recordDeleteSuccess();
                log.info("deleted blob id={} bucket={} key={}", blobId, meta.s3Bucket(), meta.s3Key());
            } catch (RuntimeException e) {
                metrics.recordDeleteFailure();
                log.warn("delete failed id={}", blobId, e);
                throw e;
            }
        }
    }

    @Override
    public BlobMetadata updateMetadata(String blobId, BlobOptions options) {
        IdValidator.requireValid(blobId);
        try (var mdc = MDC.putCloseable("blobId", blobId)) {
            log.debug("updateMetadata start owner={} project={} retentionUntil={} tags={} attributes={}",
                    options.owner(), options.project(), options.retentionUntil(),
                    options.tags().size(), options.attributes().size());

            BlobMetadata current = metadata.findById(blobId)
                    .orElseThrow(() -> {
                        metrics.recordUpdateNotFound();
                        return new BlobStoreException.NotFound(blobId);
                    });

            // Compose tags: any non-empty tags map in options REPLACES the existing map,
            // matching the semantics of the typed setters (which also replace).
            BlobMetadata updated = current.withCustomMetadata(
                    options.name(),
                    options.owner(),
                    options.project(),
                    options.retentionUntil(),
                    options.tags().isEmpty() ? null : options.tags(),
                    options.attributes().isEmpty() ? null : options.attributes(),
                    Instant.now());
            try {
                metadata.replace(updated);
                metrics.recordUpdateSuccess();
                log.info("metadata updated id={}", blobId);
                return updated;
            } catch (BlobStoreException.NotFound nf) {
                metrics.recordUpdateNotFound();
                throw nf;
            } catch (RuntimeException e) {
                metrics.recordUpdateFailure();
                throw e;
            }
        }
    }

    @Override
    public BlobAnalytics analytics() {
        if (cluster == null) {
            throw new UnsupportedOperationException("analytics requires a connected Cluster");
        }
        return new BlobAnalytics(cluster, analyticsDataset);
    }

    // ---------- lifecycle ----------

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("closing BlobStore");
            if (cluster != null) try { cluster.disconnect(); } catch (RuntimeException e) { log.warn("cluster disconnect", e); }
            if (env != null)     try { env.shutdown(); }      catch (RuntimeException e) { log.warn("env shutdown", e); }
            if (s3 != null)      try { s3.close(); }          catch (RuntimeException e) { log.warn("s3 close", e); }
        }
    }

    // ---------- helpers ----------

    private void applySse(CreateMultipartUploadRequest.Builder req) {
        switch (sseMode) {
            case NONE: break;
            case AES256: req.serverSideEncryption(ServerSideEncryption.AES256); break;
            case KMS:    req.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(s3KmsKeyId); break;
        }
    }

    private void applySse(PutObjectRequest.Builder req) {
        switch (sseMode) {
            case NONE: break;
            case AES256: req.serverSideEncryption(ServerSideEncryption.AES256); break;
            case KMS:    req.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(s3KmsKeyId); break;
        }
    }

    private void safeAbort(String key, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(s3Bucket).key(key).uploadId(uploadId).build());
            log.debug("aborted multipart upload key={} uploadId={}", key, uploadId);
        } catch (RuntimeException e) {
            log.error("failed to abort multipart upload key={} uploadId={}", key, uploadId, e);
        }
    }

    /** Read until buffer is full or EOF, starting at {@code offset}. Returns bytes added. */
    private static int readUpTo(InputStream in, byte[] buf, int offset) throws IOException {
        int read = 0;
        while (offset + read < buf.length) {
            int n = in.read(buf, offset + read, buf.length - offset - read);
            if (n < 0) break;
            read += n;
        }
        return read;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
