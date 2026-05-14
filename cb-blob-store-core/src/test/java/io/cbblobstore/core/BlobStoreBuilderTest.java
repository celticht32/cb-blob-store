/*
 * BlobStoreBuilderTest.java — pre-build validation tests for BlobStoreBuilder
 *
 * Exercises only validation paths that throw before opening any connection,
 * so these tests stay hermetic (no Couchbase or S3 required).
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobStoreBuilderTest {

    @Test
    void rejectsMissingCouchbaseConnectionString() {
        assertThatThrownBy(() -> BlobStore.builder()
                .couchbaseCredentials("u", "p")
                .s3Bucket("b")
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("couchbaseConnectionString");
    }

    @Test
    void rejectsMissingCouchbaseUsername() {
        assertThatThrownBy(() -> BlobStore.builder()
                .couchbaseConnectionString("couchbase://x")
                .s3Bucket("b")
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("couchbaseUsername");
    }

    @Test
    void rejectsMissingS3Bucket() {
        assertThatThrownBy(() -> BlobStore.builder()
                .couchbaseConnectionString("couchbase://x")
                .couchbaseCredentials("u", "p")
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("s3Bucket");
    }

    @Test
    void rejectsMixedS3Credentials() {
        assertThatThrownBy(() -> BlobStore.builder()
                .couchbaseConnectionString("couchbase://x")
                .couchbaseCredentials("u", "p")
                .s3Bucket("b")
                .s3Credentials("only-access-key", null)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both");
    }

    @Test
    void rejectsKmsSseWithoutKeyId() {
        assertThatThrownBy(() -> BlobStore.builder()
                .couchbaseConnectionString("couchbase://x")
                .couchbaseCredentials("u", "p")
                .s3Bucket("b")
                .s3ServerSideEncryption(SseMode.KMS)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS");
    }

    @Test
    void rejectsPartSizeBelow5MiB() {
        assertThatThrownBy(() -> BlobStore.builder().uploadPartSizeBytes(1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPartSizeAbove5GiB() {
        assertThatThrownBy(() -> BlobStore.builder().uploadPartSizeBytes(6L * 1024 * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullSseMode() {
        assertThatThrownBy(() -> BlobStore.builder().s3ServerSideEncryption(null))
                .isInstanceOf(NullPointerException.class);
    }
}
