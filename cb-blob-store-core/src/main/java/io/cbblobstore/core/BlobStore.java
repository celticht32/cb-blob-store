/*
 * BlobStore.java — Public BlobStore API (interface)
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Public API for storing and retrieving arbitrarily large blobs.
 *
 * <p>Metadata lives in Couchbase. Payload lives in S3-compatible object storage.
 * Implementations must be thread-safe.</p>
 *
 * <p>Construct via {@link BlobStoreBuilder}:</p>
 * <pre>{@code
 *   BlobStore store = BlobStore.builder()
 *           .couchbaseConnectionString("couchbases://cb.example.com")
 *           .couchbaseCredentials("user", "pass")
 *           .couchbaseBucket("blob_metadata")
 *           .s3Bucket("my-blob-bucket")
 *           .s3Region("us-east-1")
 *           .build();
 * }</pre>
 */
public interface BlobStore extends AutoCloseable {

    /** Entry point for the fluent builder. */
    static BlobStoreBuilder builder() {
        return new BlobStoreBuilder();
    }

    /**
     * Stream a blob from an {@link InputStream} of unknown length.
     * Internally performs S3 multipart upload, so memory use is bounded
     * to one part regardless of total size.
     *
     * @param data    payload; closed by the implementation
     * @param options per-call metadata (name, content type, tags)
     * @return persisted metadata, including generated id and content hash
     * @throws IOException        if the source stream fails mid-upload
     * @throws BlobStoreException on backend (Couchbase or S3) failures
     */
    BlobMetadata put(InputStream data, BlobOptions options) throws IOException;

    /**
     * Convenience overload uploading the contents of a local file.
     * Uses the file size to avoid multipart for small payloads.
     */
    BlobMetadata put(Path file, BlobOptions options) throws IOException;

    /**
     * Open a streaming download. The returned stream MUST be closed by the caller;
     * closing it releases the underlying S3 HTTP connection.
     *
     * @throws BlobStoreException.NotFound if no blob has the given id
     */
    InputStream get(String blobId);

    /** Fetch metadata only (no S3 round trip). */
    Optional<BlobMetadata> getMetadata(String blobId);

    /**
     * Delete the metadata document and the underlying S3 object.
     * Returns silently if the id is unknown.
     */
    void delete(String blobId);

    /**
     * Replace the caller-controlled metadata fields on an existing blob. Only
     * {@code name}, {@code owner}, {@code project}, {@code retentionUntil},
     * {@code tags}, and {@code attributes} are read from {@code options}; the
     * S3 object, content type, size, hash, and createdAt are left intact. The
     * {@code updatedAt} timestamp is refreshed.
     *
     * @return the metadata after the update
     * @throws BlobStoreException.NotFound if no blob has the given id
     */
    BlobMetadata updateMetadata(String blobId, BlobOptions options);

    /**
     * Return a handle to the Couchbase Analytics service for aggregate queries
     * over blob metadata. Requires the Analytics service to be enabled on the
     * cluster and a dataset to have been created — see the Analytics section of
     * the runbook.
     *
     * @throws UnsupportedOperationException if analytics was not configured on this BlobStore
     */
    BlobAnalytics analytics();

    /** Release Couchbase and S3 clients. Idempotent. */
    @Override
    void close();
}
