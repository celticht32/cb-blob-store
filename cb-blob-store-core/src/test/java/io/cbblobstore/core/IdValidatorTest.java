/*
 * IdValidatorTest.java — unit tests for the blob-id security validator
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class IdValidatorTest {

    @Test
    void uuidsAlwaysPass() {
        for (int i = 0; i < 50; i++) {
            assertThatCode(() -> IdValidator.requireValid(UUID.randomUUID().toString()))
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "simple",
            "a",
            "with-dash",
            "with_underscore",
            "with.dot.in.middle",
            "abc123",
            "8f1e2d4a-7c3b-4f0a-9e7d-1c2a3b4c5d6e"
    })
    void acceptsCleanIds(String id) {
        assertThatCode(() -> IdValidator.requireValid(id)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../etc/passwd",
            "..",
            "foo/bar",
            "back\\slash",
            "white space",
            "tab\there",
            "newline\nhere",
            "name with spaces",
            "unicode-emoji-\uD83D\uDE00",
            "leading.",
            ".leading-dot",
            "trailing.",
            "with..dots",
            ""
    })
    void rejectsUnsafeIds(String id) {
        assertThatThrownBy(() -> IdValidator.requireValid(id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> IdValidator.requireValid(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void rejectsIdsExceedingMaxLength() {
        String tooLong = "a".repeat(IdValidator.MAX_LENGTH + 1);
        assertThatThrownBy(() -> IdValidator.requireValid(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    @Test
    void acceptsIdsAtMaxLength() {
        String atMax = "a".repeat(IdValidator.MAX_LENGTH);
        assertThatCode(() -> IdValidator.requireValid(atMax)).doesNotThrowAnyException();
    }

    @Test
    void checkedReturnsTheInputWhenValid() {
        assertThat(IdValidator.checked("valid-id")).isEqualTo("valid-id");
    }
}
