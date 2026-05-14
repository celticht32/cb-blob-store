/*
 * MetadataRepositoryTest.java — unit tests for the metadata persistence layer
 *
 * Mocks the Couchbase Collection, focusing on:
 *   - JSON insert via RawJsonTranscoder
 *   - DocumentNotFound -> Optional.empty
 *   - the type-field guard rejecting non-blob documents
 *   - idempotent delete
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core.internal;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.MutationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cbblobstore.core.BlobMetadata;
import io.cbblobstore.core.BlobStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataRepositoryTest {

    @Mock Collection collection;

    private MetadataRepository repo;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        repo = new MetadataRepository(collection);
    }

    @Test
    void insertSerializesAsJsonWithRawJsonTranscoder() {
        BlobMetadata meta = sampleMeta("abc");
        when(collection.insert(anyString(), any(byte[].class), any(InsertOptions.class)))
                .thenReturn(mock(MutationResult.class));

        repo.insert(meta);

        verify(collection).insert(eq("abc"), any(byte[].class), any(InsertOptions.class));
    }

    @Test
    void findByIdReturnsBlobMetadataOnHit() throws Exception {
        BlobMetadata meta = sampleMeta("abc");
        byte[] json = MAPPER.writeValueAsBytes(meta);

        GetResult result = mock(GetResult.class);
        when(result.contentAs(byte[].class)).thenReturn(json);
        when(collection.get(eq("abc"), any(GetOptions.class))).thenReturn(result);

        Optional<BlobMetadata> got = repo.findById("abc");
        assertThat(got).isPresent();
        assertThat(got.get().id()).isEqualTo("abc");
        assertThat(got.get().sha256()).isEqualTo("hash");
    }

    @Test
    void findByIdReturnsEmptyOnDocumentNotFound() {
        when(collection.get(eq("missing"), any(GetOptions.class)))
                .thenThrow(new DocumentNotFoundException(null));

        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void findByIdRefusesDocumentsWithoutBlobType() throws Exception {
        // A doc with type != "blob" at the same id is treated as not-our-doc.
        String foreignJson = "{\"id\":\"abc\",\"type\":\"user\",\"size\":0,\"sha256\":\"x\"," +
                "\"s3Bucket\":\"b\",\"s3Key\":\"k\",\"createdAt\":\"2026-01-01T00:00:00Z\"," +
                "\"updatedAt\":\"2026-01-01T00:00:00Z\",\"tags\":{}}";

        GetResult result = mock(GetResult.class);
        when(result.contentAs(byte[].class)).thenReturn(foreignJson.getBytes());
        when(collection.get(eq("abc"), any(GetOptions.class))).thenReturn(result);

        assertThat(repo.findById("abc")).isEmpty();
    }

    @Test
    void findByIdWrapsUnexpectedErrors() {
        when(collection.get(eq("abc"), any(GetOptions.class)))
                .thenThrow(new RuntimeException("network exploded"));

        assertThatThrownBy(() -> repo.findById("abc"))
                .isInstanceOf(BlobStoreException.class)
                .hasMessageContaining("abc");
    }

    @Test
    void deleteIsIdempotentOnMissingDocument() {
        // Couchbase throws DocumentNotFound on remove of a non-existent doc;
        // the repo must swallow it.
        when(collection.remove("missing"))
                .thenThrow(new DocumentNotFoundException(null));

        repo.delete("missing");   // must not throw
    }

    @Test
    void deleteHappyPath() {
        when(collection.remove("abc")).thenReturn(mock(MutationResult.class));

        repo.delete("abc");

        verify(collection).remove("abc");
    }

    @Test
    void deleteWrapsUnexpectedErrors() {
        when(collection.remove("abc"))
                .thenThrow(new RuntimeException("network exploded"));

        assertThatThrownBy(() -> repo.delete("abc"))
                .isInstanceOf(BlobStoreException.class);
        verify(collection).remove("abc");
        // ensure we tried exactly once
        verify(collection, never()).remove(eq("other"));
    }

    @Test
    void replacePersistsTheNewJsonViaRawJsonTranscoder() {
        BlobMetadata m = sampleMeta("abc");
        when(collection.replace(eq("abc"), any(byte[].class),
                any(com.couchbase.client.java.kv.ReplaceOptions.class)))
                .thenReturn(mock(MutationResult.class));

        repo.replace(m);

        verify(collection).replace(eq("abc"), any(byte[].class),
                any(com.couchbase.client.java.kv.ReplaceOptions.class));
    }

    @Test
    void replaceMapsDocumentNotFoundToBlobStoreNotFound() {
        BlobMetadata m = sampleMeta("missing");
        when(collection.replace(eq("missing"), any(byte[].class),
                any(com.couchbase.client.java.kv.ReplaceOptions.class)))
                .thenThrow(new DocumentNotFoundException(null));

        assertThatThrownBy(() -> repo.replace(m))
                .isInstanceOf(BlobStoreException.NotFound.class);
    }

    @Test
    void replaceWrapsUnexpectedErrors() {
        BlobMetadata m = sampleMeta("abc");
        when(collection.replace(eq("abc"), any(byte[].class),
                any(com.couchbase.client.java.kv.ReplaceOptions.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> repo.replace(m))
                .isInstanceOf(BlobStoreException.class)
                .hasMessageContaining("abc");
    }

    private static BlobMetadata sampleMeta(String id) {
        return new BlobMetadata(
                id, "name.bin", "application/octet-stream",
                42L, "hash", "bucket", "blobs/" + id,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of());
    }
}
