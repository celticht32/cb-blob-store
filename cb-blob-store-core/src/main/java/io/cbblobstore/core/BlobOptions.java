/*
 * BlobOptions.java — Per-call options for BlobStore.put()
 *
 * Carries the same custom-metadata shape as BlobMetadata: typed top-level fields
 * (owner, project, retentionUntil) plus a string→Object attributes map for
 * arbitrary JSON. tags is kept as string→string for the simple-label case.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Per-call options for {@link BlobStore#put}. Use {@link #builder()}. */
public final class BlobOptions {

    private final String name;
    private final String contentType;
    private final String explicitId;
    private final String owner;
    private final String project;
    private final Instant retentionUntil;
    private final Map<String, String> tags;
    private final Map<String, Object> attributes;

    private BlobOptions(Builder b) {
        this.name = b.name;
        this.contentType = b.contentType == null ? "application/octet-stream" : b.contentType;
        this.explicitId = b.explicitId;
        this.owner = b.owner;
        this.project = b.project;
        this.retentionUntil = b.retentionUntil;
        this.tags = Collections.unmodifiableMap(new HashMap<>(b.tags));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience: defaults only. */
    public static BlobOptions defaults() {
        return builder().build();
    }

    public String name() { return name; }
    public String contentType() { return contentType; }
    public Map<String, String> tags() { return tags; }

    /** May be {@code null}; if set, the library uses this id instead of generating a UUID. */
    public String explicitId() { return explicitId; }

    /** Free-form owner identifier; surfaces as {@code owner} on the metadata doc. */
    public String owner() { return owner; }

    /** Free-form project name; surfaces as {@code project} on the metadata doc. */
    public String project() { return project; }

    /** Optional retention deadline; surfaces as {@code retentionUntil}. */
    public Instant retentionUntil() { return retentionUntil; }

    /** Arbitrary additional JSON-compatible fields; surfaces as {@code attributes}. */
    public Map<String, Object> attributes() { return attributes; }

    public static final class Builder {
        private String name;
        private String contentType;
        private String explicitId;
        private String owner;
        private String project;
        private Instant retentionUntil;
        private final Map<String, String> tags = new HashMap<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        public Builder name(String name) { this.name = name; return this; }

        public Builder contentType(String contentType) { this.contentType = contentType; return this; }

        public Builder tag(String key, String value) {
            this.tags.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Pin the blob id (must be unique). Otherwise a UUID is generated. */
        public Builder id(String explicitId) { this.explicitId = explicitId; return this; }

        public Builder owner(String owner) { this.owner = owner; return this; }

        public Builder project(String project) { this.project = project; return this; }

        public Builder retentionUntil(Instant retentionUntil) {
            this.retentionUntil = retentionUntil;
            return this;
        }

        /**
         * Set a single attribute. The value must be a JSON-compatible type
         * (String, Number, Boolean, Map, List, or null).
         */
        public Builder attribute(String key, Object value) {
            Objects.requireNonNull(key, "key");
            this.attributes.put(key, value);
            return this;
        }

        /** Bulk-set attributes; overwrites by key, does not clear unrelated ones. */
        public Builder attributes(Map<String, ?> attributes) {
            Objects.requireNonNull(attributes, "attributes");
            this.attributes.putAll(attributes);
            return this;
        }

        public BlobOptions build() { return new BlobOptions(this); }
    }
}
