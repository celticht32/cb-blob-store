/*
 * BlobMetadata.java — Immutable metadata record persisted in Couchbase
 *
 * Carries three groups of fields:
 *   1. System-set: id, size, sha256, s3Bucket, s3Key, createdAt, updatedAt
 *   2. Caller-set typed: name, contentType, owner, project, retentionUntil
 *   3. Caller-set freeform: tags (string→string) and attributes (string→Object)
 *
 * `attributes` is for arbitrary JSON the caller wants to round-trip; values can be
 * strings, numbers, booleans, nested maps, or lists. `tags` is kept for the
 * existing simple-label use case and stays string→string for cheap indexing.
 *
 * Backward-compatible: existing documents without the new fields deserialize
 * cleanly — Jackson defaults the missing scalars to null and the missing maps
 * to empty via the @JsonCreator parameters.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable view of a stored blob's metadata.
 *
 * <p>Persisted as a JSON document in Couchbase under the document id {@link #id()}.
 * The payload itself is stored in S3 at {@code s3://{s3Bucket}/{s3Key}}.</p>
 *
 * <h2>Custom metadata</h2>
 * Callers can attach custom information in three ways:
 * <ul>
 *   <li>{@link #owner()}, {@link #project()}, {@link #retentionUntil()} — typed
 *       top-level fields. Cheap to index (one secondary index covers all three);
 *       use these when you want the same field for every blob.</li>
 *   <li>{@link #tags()} — {@code Map<String,String>} for simple labels. Cheap to
 *       filter via Analytics or N1QL.</li>
 *   <li>{@link #attributes()} — {@code Map<String,Object>} for arbitrary JSON.
 *       Use this for nested structures or non-string values. Costs more to query.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BlobMetadata {

    /** Constant {@code type} field used to discriminate blob docs in Couchbase. */
    public static final String DOCUMENT_TYPE = "blob";

    // System-set
    private final String id;
    private final long size;
    private final String sha256;
    private final String s3Bucket;
    private final String s3Key;
    private final Instant createdAt;
    private final Instant updatedAt;

    // Caller-set typed
    private final String name;
    private final String contentType;
    private final String owner;          // user/account/service identifier (free-form)
    private final String project;        // logical grouping (free-form)
    private final Instant retentionUntil;// optional retention deadline

    // Caller-set freeform
    private final Map<String, String> tags;
    private final Map<String, Object> attributes;

    @JsonCreator
    public BlobMetadata(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("contentType") String contentType,
            @JsonProperty("size") long size,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("s3Bucket") String s3Bucket,
            @JsonProperty("s3Key") String s3Key,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("tags") Map<String, String> tags,
            @JsonProperty("owner") String owner,
            @JsonProperty("project") String project,
            @JsonProperty("retentionUntil") Instant retentionUntil,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.contentType = contentType;
        this.size = size;
        this.sha256 = sha256;
        this.s3Bucket = Objects.requireNonNull(s3Bucket, "s3Bucket");
        this.s3Key = Objects.requireNonNull(s3Key, "s3Key");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.tags = tags == null ? Collections.emptyMap() : Map.copyOf(tags);
        this.owner = owner;
        this.project = project;
        this.retentionUntil = retentionUntil;
        // attributes can carry null values (legitimate JSON null); Map.copyOf
        // would NPE on those, so we use a defensive copy + unmodifiableMap.
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * Legacy 10-arg constructor kept so callers compiled against earlier versions
     * keep working. Equivalent to the canonical constructor with owner/project/
     * retentionUntil/attributes all unset.
     *
     * @deprecated use the 14-arg canonical constructor
     */
    @Deprecated(since = "0.2.0")
    public BlobMetadata(String id, String name, String contentType, long size, String sha256,
                        String s3Bucket, String s3Key, Instant createdAt, Instant updatedAt,
                        Map<String, String> tags) {
        this(id, name, contentType, size, sha256, s3Bucket, s3Key,
                createdAt, updatedAt, tags, null, null, null, null);
    }

    /** Discriminator stamped into the JSON for views / N1QL filtering. */
    @JsonProperty("type")
    public String type() {
        return DOCUMENT_TYPE;
    }

    @JsonProperty("id")             public String id() { return id; }
    @JsonProperty("name")           public String name() { return name; }
    @JsonProperty("contentType")    public String contentType() { return contentType; }
    @JsonProperty("size")           public long size() { return size; }
    @JsonProperty("sha256")         public String sha256() { return sha256; }
    @JsonProperty("s3Bucket")       public String s3Bucket() { return s3Bucket; }
    @JsonProperty("s3Key")          public String s3Key() { return s3Key; }
    @JsonProperty("createdAt")      public Instant createdAt() { return createdAt; }
    @JsonProperty("updatedAt")      public Instant updatedAt() { return updatedAt; }
    @JsonProperty("tags")           public Map<String, String> tags() { return tags; }
    @JsonProperty("owner")          public String owner() { return owner; }
    @JsonProperty("project")        public String project() { return project; }
    @JsonProperty("retentionUntil") public Instant retentionUntil() { return retentionUntil; }
    @JsonProperty("attributes")     public Map<String, Object> attributes() { return attributes; }

    /**
     * Return a copy with the supplied custom-metadata mutations applied. Useful
     * for in-place metadata updates that don't touch the underlying S3 object.
     * Pass {@code null} for any argument you don't want to change.
     */
    public BlobMetadata withCustomMetadata(String newName,
                                           String newOwner,
                                           String newProject,
                                           Instant newRetentionUntil,
                                           Map<String, String> newTags,
                                           Map<String, Object> newAttributes,
                                           Instant newUpdatedAt) {
        return new BlobMetadata(
                id,
                newName != null ? newName : name,
                contentType,
                size,
                sha256,
                s3Bucket,
                s3Key,
                createdAt,
                newUpdatedAt != null ? newUpdatedAt : updatedAt,
                newTags != null ? newTags : tags,
                newOwner != null ? newOwner : owner,
                newProject != null ? newProject : project,
                newRetentionUntil != null ? newRetentionUntil : retentionUntil,
                newAttributes != null ? newAttributes : attributes);
    }

    @Override
    public String toString() {
        return "BlobMetadata{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", contentType='" + contentType + '\'' +
                ", size=" + size +
                ", sha256='" + sha256 + '\'' +
                ", s3='" + s3Bucket + "/" + s3Key + '\'' +
                (owner != null ? ", owner='" + owner + '\'' : "") +
                (project != null ? ", project='" + project + '\'' : "") +
                (retentionUntil != null ? ", retentionUntil=" + retentionUntil : "") +
                '}';
    }
}
