/*
 * MetadataRepository.java — Couchbase persistence for BlobMetadata
 *
 * Uses RawJsonTranscoder so Jackson controls the wire format. The findById
 * path verifies the "type" field after deserialization to refuse foreign
 * documents that share the id namespace.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core.internal;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.codec.RawJsonTranscoder;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.ReplaceOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cbblobstore.core.BlobMetadata;
import io.cbblobstore.core.BlobStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public final class MetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(MetadataRepository.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Collection collection;

    public MetadataRepository(Collection collection) {
        this.collection = collection;
    }

    public void insert(BlobMetadata meta) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(meta);
            collection.insert(meta.id(), json,
                    InsertOptions.insertOptions().transcoder(RawJsonTranscoder.INSTANCE));
            log.debug("metadata inserted id={} size={}B json", meta.id(), json.length);
        } catch (Exception e) {
            log.warn("metadata insert failed id={}", meta.id(), e);
            throw new BlobStoreException("Failed to insert metadata for blob " + meta.id(), e);
        }
    }

    /**
     * Replace an existing metadata document with the supplied value. The caller
     * is responsible for verifying the new document refers to the same blob id.
     */
    public void replace(BlobMetadata meta) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(meta);
            collection.replace(meta.id(), json,
                    ReplaceOptions.replaceOptions().transcoder(RawJsonTranscoder.INSTANCE));
            log.debug("metadata replaced id={} size={}B json", meta.id(), json.length);
        } catch (DocumentNotFoundException e) {
            log.debug("metadata replace miss id={}", meta.id());
            throw new BlobStoreException.NotFound(meta.id());
        } catch (Exception e) {
            log.warn("metadata replace failed id={}", meta.id(), e);
            throw new BlobStoreException("Failed to replace metadata for blob " + meta.id(), e);
        }
    }

    public Optional<BlobMetadata> findById(String id) {
        try {
            byte[] bytes = collection.get(id,
                    GetOptions.getOptions().transcoder(RawJsonTranscoder.INSTANCE))
                    .contentAs(byte[].class);
            // Guard against id-namespace collisions with non-blob documents. We
            // read the type from the raw JSON tree (not BlobMetadata.type(), which
            // is a constant) so a foreign document with a different type still
            // surfaces as a miss.
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(bytes);
            com.fasterxml.jackson.databind.JsonNode typeNode = root.get("type");
            if (typeNode == null || !BlobMetadata.DOCUMENT_TYPE.equals(typeNode.asText())) {
                log.debug("metadata findById hit but wrong type id={}", id);
                return Optional.empty();
            }
            BlobMetadata meta = MAPPER.treeToValue(root, BlobMetadata.class);
            log.trace("metadata findById hit id={}", id);
            return Optional.of(meta);
        } catch (DocumentNotFoundException e) {
            log.trace("metadata findById miss id={}", id);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("metadata findById failed id={}", id, e);
            throw new BlobStoreException("Failed to read metadata for blob " + id, e);
        }
    }

    public void delete(String id) {
        try {
            collection.remove(id);
            log.debug("metadata deleted id={}", id);
        } catch (DocumentNotFoundException e) {
            // already gone, idempotent
            log.trace("metadata delete miss id={}", id);
        } catch (Exception e) {
            log.warn("metadata delete failed id={}", id, e);
            throw new BlobStoreException("Failed to delete metadata for blob " + id, e);
        }
    }
}
