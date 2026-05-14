/*
 * BlobOptionsTest.java — unit tests for BlobOptions builder
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobOptionsTest {

    @Test
    void defaultsFillInContentType() {
        BlobOptions o = BlobOptions.defaults();
        assertThat(o.contentType()).isEqualTo("application/octet-stream");
        assertThat(o.name()).isNull();
        assertThat(o.explicitId()).isNull();
        assertThat(o.tags()).isEmpty();
    }

    @Test
    void builderCarriesAllFields() {
        BlobOptions o = BlobOptions.builder()
                .name("report.pdf")
                .contentType("application/pdf")
                .id("custom-id")
                .tag("env", "prod")
                .tag("team", "finance")
                .build();
        assertThat(o.name()).isEqualTo("report.pdf");
        assertThat(o.contentType()).isEqualTo("application/pdf");
        assertThat(o.explicitId()).isEqualTo("custom-id");
        assertThat(o.tags()).containsOnly(
                org.assertj.core.api.Assertions.entry("env", "prod"),
                org.assertj.core.api.Assertions.entry("team", "finance"));
    }

    @Test
    void tagsAreImmutableOnTheBuiltOptions() {
        BlobOptions o = BlobOptions.builder().tag("k", "v").build();
        assertThatThrownBy(() -> o.tags().put("a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullKeyOrValueRejected() {
        BlobOptions.Builder b = BlobOptions.builder();
        assertThatThrownBy(() -> b.tag(null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.tag("k", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void carriesTypedCustomFields() {
        java.time.Instant retain = java.time.Instant.parse("2027-01-01T00:00:00Z");
        BlobOptions o = BlobOptions.builder()
                .owner("svc-ingest")
                .project("zero")
                .retentionUntil(retain)
                .build();
        assertThat(o.owner()).isEqualTo("svc-ingest");
        assertThat(o.project()).isEqualTo("zero");
        assertThat(o.retentionUntil()).isEqualTo(retain);
    }

    @Test
    void attributesMapAcceptsMixedJsonCompatibleValues() {
        BlobOptions o = BlobOptions.builder()
                .attribute("string", "s")
                .attribute("number", 42)
                .attribute("bool", true)
                .attribute("nested", java.util.Map.of("k", "v"))
                .attribute("list", java.util.List.of(1, 2, 3))
                .attribute("null-ok", null)
                .build();
        assertThat(o.attributes())
                .containsEntry("string", "s")
                .containsEntry("number", 42)
                .containsEntry("bool", true)
                .containsKey("nested")
                .containsKey("list")
                .containsKey("null-ok");
    }

    @Test
    void attributesBulkSetMergesNotReplaces() {
        BlobOptions o = BlobOptions.builder()
                .attribute("a", 1)
                .attributes(java.util.Map.of("b", 2, "c", 3))
                .build();
        assertThat(o.attributes())
                .containsEntry("a", 1)
                .containsEntry("b", 2)
                .containsEntry("c", 3);
    }

    @Test
    void attributesMapIsImmutableOnBuiltOptions() {
        BlobOptions o = BlobOptions.builder().attribute("k", "v").build();
        assertThatThrownBy(() -> o.attributes().put("a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
