/*
 * BlobStoreExceptionTest.java — unit tests for the exception hierarchy
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlobStoreExceptionTest {

    @Test
    void carriesMessageAndCause() {
        Throwable cause = new RuntimeException("boom");
        BlobStoreException e = new BlobStoreException("wrap", cause);
        assertThat(e.getMessage()).isEqualTo("wrap");
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void notFoundIsBlobStoreException() {
        BlobStoreException.NotFound nf = new BlobStoreException.NotFound("abc");
        assertThat(nf).isInstanceOf(BlobStoreException.class);
        assertThat(nf.getMessage()).contains("abc");
    }
}
