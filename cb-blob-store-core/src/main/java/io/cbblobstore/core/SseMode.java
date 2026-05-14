/*
 * SseMode.java — S3 server-side encryption modes
 *
 * Selects which SSE header is set on uploads. Default is AES256.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

/**
 * Server-side encryption mode applied by the library when uploading to S3.
 *
 * <p>{@link #AES256} is the secure default; it adds no operational complexity and
 * is supported by AWS S3, MinIO, and most S3-compatible stores. Use {@link #KMS}
 * when you need a customer-managed KMS key. Use {@link #NONE} only when you have
 * deliberately set a bucket default policy and want to avoid sending the header.</p>
 */
public enum SseMode {

    /** No SSE header sent; bucket default policy applies. */
    NONE,

    /** SSE-S3 (AES-256, S3-managed keys). Default. */
    AES256,

    /** SSE-KMS. Requires a KMS key id via {@code BlobStoreBuilder.s3KmsKeyId}. */
    KMS
}
