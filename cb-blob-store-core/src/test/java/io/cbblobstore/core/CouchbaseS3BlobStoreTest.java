/*
 * CouchbaseS3BlobStoreTest.java — unit tests for the main BlobStore implementation
 *
 * Mocks the S3Client and Couchbase Collection so the test is fully hermetic.
 * Covers:
 *   - happy path: multipart upload + metadata insert
 *   - empty stream: non-multipart path
 *   - SSE applied to S3 requests
 *   - get: streams from S3
 *   - get: NotFound when metadata missing
 *   - delete: removes S3 then metadata
 *   - id validation rejected before any backend call
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.MutationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouchbaseS3BlobStoreTest {

    @Mock Cluster cluster;
    @Mock ClusterEnvironment env;
    @Mock Collection collection;
    @Mock S3Client s3;

    private CouchbaseS3BlobStore newStore(SseMode sse, String kmsId) {
        return new CouchbaseS3BlobStore(
                cluster, env, collection,
                s3, "test-bucket", "blobs/",
                5L * 1024 * 1024,   // 5 MiB part size (min allowed)
                sse, kmsId, "blobs",
                null /* no MeterRegistry — metrics tested separately */);
    }

    @BeforeEach
    void wireDefaultS3Stubs() {
        // S3 stubs marked lenient — not every test exercises every method.
        lenient().when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upl-1").build());

        AtomicInteger partNum = new AtomicInteger();
        lenient().when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenAnswer(inv -> UploadPartResponse.builder()
                        .eTag("etag-" + partNum.incrementAndGet()).build());

        lenient().when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());

        lenient().when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        lenient().when(collection.insert(anyString(), any(byte[].class), any(InsertOptions.class)))
                .thenReturn(mock(MutationResult.class));
    }

    @Test
    void putStreamHappyPathRunsMultipartAndInsertsMetadata() throws Exception {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);

        byte[] payload = "Hello, blob world!".getBytes();
        BlobMetadata meta = store.put(new ByteArrayInputStream(payload),
                BlobOptions.builder().name("hello.txt").contentType("text/plain").build());

        assertThat(meta.size()).isEqualTo(payload.length);
        assertThat(meta.sha256()).hasSize(64);
        assertThat(meta.s3Bucket()).isEqualTo("test-bucket");
        assertThat(meta.s3Key()).startsWith("blobs/");

        verify(s3).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        verify(s3, times(1)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
        verify(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(collection).insert(eq(meta.id()), any(byte[].class), any(InsertOptions.class));
    }

    @Test
    void putAppliesSseAes256OnMultipartCreate() throws Exception {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);
        store.put(new ByteArrayInputStream("x".getBytes()),
                BlobOptions.builder().contentType("text/plain").build());

        ArgumentCaptor<CreateMultipartUploadRequest> cap =
                ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(s3).createMultipartUpload(cap.capture());
        assertThat(cap.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
    }

    @Test
    void putWithSseKmsSetsKeyIdOnRequest() throws Exception {
        CouchbaseS3BlobStore store = newStore(SseMode.KMS, "arn:aws:kms:::key/abc");
        store.put(new ByteArrayInputStream("x".getBytes()),
                BlobOptions.builder().contentType("text/plain").build());

        ArgumentCaptor<CreateMultipartUploadRequest> cap =
                ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(s3).createMultipartUpload(cap.capture());
        assertThat(cap.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(cap.getValue().ssekmsKeyId()).isEqualTo("arn:aws:kms:::key/abc");
    }

    @Test
    void emptyStreamTakesNonMultipartPath() throws Exception {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);

        BlobMetadata meta = store.put(new ByteArrayInputStream(new byte[0]),
                BlobOptions.builder().build());

        assertThat(meta.size()).isZero();
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        verify(s3, never()).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void putWithExplicitIdValidatesIt() {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);

        assertThatThrownBy(() -> store.put(new ByteArrayInputStream("x".getBytes()),
                BlobOptions.builder().id("../escape").build()))
                .isInstanceOf(IllegalArgumentException.class);

        // No S3 call should have happened.
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void getThrowsNotFoundWhenMetadataMissing() {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);
        when(collection.get(eq("abc"), any(GetOptions.class)))
                .thenThrow(new DocumentNotFoundException(null));

        assertThatThrownBy(() -> store.get("abc"))
                .isInstanceOf(BlobStoreException.NotFound.class);
    }

    @Test
    void deleteRemovesS3ThenMetadata() {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);

        // Stub a metadata doc so the lookup succeeds.
        BlobMetadata meta = new BlobMetadata("abc", "n", "ct", 1L, "h",
                "test-bucket", "blobs/abc",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.util.Map.of());
        byte[] json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsBytes(meta);
        } catch (Exception e) { throw new RuntimeException(e); }

        GetResult getResult = mock(GetResult.class);
        when(getResult.contentAs(byte[].class)).thenReturn(json);
        when(collection.get(eq("abc"), any(GetOptions.class))).thenReturn(getResult);
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
        when(collection.remove(eq("abc"))).thenReturn(mock(MutationResult.class));

        store.delete("abc");

        ArgumentCaptor<DeleteObjectRequest> cap = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(cap.capture());
        assertThat(cap.getValue().key()).isEqualTo("blobs/abc");
        verify(collection).remove("abc");
    }

    @Test
    void deleteOfMissingBlobIsNoop() {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);
        when(collection.get(eq("missing"), any(GetOptions.class)))
                .thenThrow(new DocumentNotFoundException(null));

        store.delete("missing");   // must not throw

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // ---------- Custom metadata + updateMetadata ----------

    @Test
    void putPropagatesTypedFieldsAndAttributesIntoMetadata() throws Exception {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);

        java.time.Instant retain = java.time.Instant.parse("2027-01-01T00:00:00Z");
        java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("region", "us-east-1");
        attrs.put("priority", 3);
        attrs.put("nested", java.util.Map.of("k", "v"));

        BlobMetadata meta = store.put(new java.io.ByteArrayInputStream("x".getBytes()),
                BlobOptions.builder()
                        .name("y.bin")
                        .contentType("application/octet-stream")
                        .owner("svc-ingest")
                        .project("project-zero")
                        .retentionUntil(retain)
                        .tag("env", "prod")
                        .attributes(attrs)
                        .build());

        // Returned BlobMetadata reflects the typed fields.
        assertThat(meta.owner()).isEqualTo("svc-ingest");
        assertThat(meta.project()).isEqualTo("project-zero");
        assertThat(meta.retentionUntil()).isEqualTo(retain);
        assertThat(meta.tags()).containsEntry("env", "prod");
        assertThat(meta.attributes())
                .containsEntry("region", "us-east-1")
                .containsEntry("priority", 3)
                .containsKey("nested");

        // The bytes written to Couchbase carry the same fields.
        ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);
        verify(collection).insert(eq(meta.id()), json.capture(), any(InsertOptions.class));
        String s = new String(json.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(s)
                .contains("\"owner\":\"svc-ingest\"")
                .contains("\"project\":\"project-zero\"")
                .contains("\"retentionUntil\":\"2027-01-01T00:00:00Z\"")
                .contains("\"region\":\"us-east-1\"")
                .contains("\"priority\":3");
    }

    @Test
    void updateMetadataReplacesCallerFieldsWithoutTouchingS3() throws Exception {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);

        // Stub an existing metadata doc.
        BlobMetadata existing = new BlobMetadata(
                "abc", "old.bin", "application/octet-stream",
                100L, "sha", "test-bucket", "blobs/abc",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                java.util.Map.of("env", "stage"),
                "old-owner", null, null, java.util.Map.of("k", "old"));
        byte[] json = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsBytes(existing);

        GetResult getResult = mock(GetResult.class);
        when(getResult.contentAs(byte[].class)).thenReturn(json);
        when(collection.get(eq("abc"), any(GetOptions.class))).thenReturn(getResult);
        when(collection.replace(eq("abc"), any(byte[].class), any(com.couchbase.client.java.kv.ReplaceOptions.class)))
                .thenReturn(mock(MutationResult.class));

        BlobMetadata updated = store.updateMetadata("abc",
                BlobOptions.builder()
                        .owner("new-owner")
                        .project("new-project")
                        .tag("env", "prod")  // replaces existing
                        .attribute("k", "new")
                        .build());

        assertThat(updated.id()).isEqualTo("abc");
        assertThat(updated.owner()).isEqualTo("new-owner");
        assertThat(updated.project()).isEqualTo("new-project");
        assertThat(updated.tags()).containsExactly(org.assertj.core.api.Assertions.entry("env", "prod"));
        assertThat(updated.attributes()).containsEntry("k", "new");
        // System fields untouched
        assertThat(updated.size()).isEqualTo(100L);
        assertThat(updated.sha256()).isEqualTo("sha");

        // S3 was never touched.
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    void updateMetadataThrowsNotFoundWhenMissing() {
        CouchbaseS3BlobStore store = newStore(SseMode.AES256, null);
        when(collection.get(eq("missing"), any(GetOptions.class)))
                .thenThrow(new DocumentNotFoundException(null));

        assertThatThrownBy(() -> store.updateMetadata("missing",
                BlobOptions.builder().owner("x").build()))
                .isInstanceOf(BlobStoreException.NotFound.class);
    }
}
