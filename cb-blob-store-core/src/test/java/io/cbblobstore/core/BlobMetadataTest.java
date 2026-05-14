/*
 * BlobMetadataTest.java — unit tests for BlobMetadata JSON round-trip
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobMetadataTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void roundTripsThroughJacksonAndCarriesTypeField() throws Exception {
        Instant now = Instant.parse("2026-05-13T12:00:00Z");
        BlobMetadata meta = new BlobMetadata(
                "abc-123", "video.mp4", "video/mp4",
                12345L, "deadbeef", "my-bucket", "blobs/abc-123",
                now, now, Map.of("env", "prod"));

        String json = mapper.writeValueAsString(meta);
        assertThat(json).contains("\"type\":\"blob\"");
        assertThat(json).contains("\"id\":\"abc-123\"");
        assertThat(json).contains("\"createdAt\":\"2026-05-13T12:00:00Z\"");

        BlobMetadata back = mapper.readValue(json, BlobMetadata.class);
        assertThat(back.id()).isEqualTo("abc-123");
        assertThat(back.size()).isEqualTo(12345L);
        assertThat(back.sha256()).isEqualTo("deadbeef");
        assertThat(back.tags()).containsEntry("env", "prod");
        assertThat(back.type()).isEqualTo("blob");
    }

    @Test
    void nullTagsBecomesEmptyMap() {
        BlobMetadata m = new BlobMetadata("id", null, null, 0L, "h", "b", "k",
                Instant.EPOCH, Instant.EPOCH, null);
        assertThat(m.tags()).isEmpty();
    }

    @Test
    void rejectsNullId() {
        assertThatThrownBy(() -> new BlobMetadata(null, "n", "c", 0L, "h", "b", "k",
                Instant.EPOCH, Instant.EPOCH, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tagsMapIsImmutable() {
        BlobMetadata m = new BlobMetadata("id", null, null, 0L, "h", "b", "k",
                Instant.EPOCH, Instant.EPOCH, Map.of("k", "v"));
        assertThatThrownBy(() -> m.tags().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void roundTripsTypedFieldsAndAttributesMap() throws Exception {
        Instant now = Instant.parse("2026-05-13T12:00:00Z");
        Instant retain = Instant.parse("2027-01-01T00:00:00Z");
        java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("region", "us-east-1");
        attrs.put("priority", 3);
        attrs.put("nested", Map.of("kind", "video"));

        BlobMetadata meta = new BlobMetadata(
                "abc", "x.mp4", "video/mp4",
                10L, "h", "b", "k",
                now, now, Map.of("env", "prod"),
                "alice", "research", retain, attrs);

        String json = mapper.writeValueAsString(meta);
        assertThat(json).contains("\"owner\":\"alice\"");
        assertThat(json).contains("\"project\":\"research\"");
        assertThat(json).contains("\"retentionUntil\":\"2027-01-01T00:00:00Z\"");
        assertThat(json).contains("\"region\":\"us-east-1\"");
        assertThat(json).contains("\"priority\":3");

        BlobMetadata back = mapper.readValue(json, BlobMetadata.class);
        assertThat(back.owner()).isEqualTo("alice");
        assertThat(back.project()).isEqualTo("research");
        assertThat(back.retentionUntil()).isEqualTo(retain);
        assertThat(back.attributes())
                .containsEntry("region", "us-east-1")
                .containsEntry("priority", 3);
    }

    @Test
    void legacy10ArgConstructorStillWorks() {
        // Pre-0.2.0 callers used the 10-arg constructor; it must remain available.
        BlobMetadata m = new BlobMetadata("id", "n", "c", 1L, "h", "b", "k",
                Instant.EPOCH, Instant.EPOCH, Map.of("k", "v"));
        assertThat(m.owner()).isNull();
        assertThat(m.project()).isNull();
        assertThat(m.retentionUntil()).isNull();
        assertThat(m.attributes()).isEmpty();
    }

    @Test
    void nullCustomFieldsAreOmittedFromJson() throws Exception {
        // JsonInclude.NON_NULL keeps the document compact when nothing is set.
        BlobMetadata m = new BlobMetadata("id", null, null, 0L, "h", "b", "k",
                Instant.EPOCH, Instant.EPOCH, null, null, null, null, null);
        String json = mapper.writeValueAsString(m);
        assertThat(json)
                .doesNotContain("\"owner\"")
                .doesNotContain("\"project\"")
                .doesNotContain("\"retentionUntil\"");
    }

    @Test
    void withCustomMetadataCopiesOnlyChangedFields() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-06-01T00:00:00Z");
        BlobMetadata m = new BlobMetadata("id", "orig", "ct", 1L, "h", "b", "k",
                t0, t0, Map.of(), "alice", "p1", null, Map.of("k", "v0"));

        BlobMetadata m2 = m.withCustomMetadata(
                null, "bob", null, null,
                Map.of("env", "prod"),
                Map.of("k", "v1"),
                t1);

        assertThat(m2.id()).isEqualTo("id");
        assertThat(m2.name()).isEqualTo("orig");   // unchanged
        assertThat(m2.owner()).isEqualTo("bob");
        assertThat(m2.project()).isEqualTo("p1");  // unchanged
        assertThat(m2.tags()).containsEntry("env", "prod");
        assertThat(m2.attributes()).containsEntry("k", "v1");
        assertThat(m2.createdAt()).isEqualTo(t0);  // unchanged
        assertThat(m2.updatedAt()).isEqualTo(t1);  // bumped
    }
}
