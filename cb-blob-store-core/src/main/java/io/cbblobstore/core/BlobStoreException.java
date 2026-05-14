/*
 * BlobStoreException.java — Runtime exception hierarchy for backend failures
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

/** Unchecked exception covering Couchbase + S3 backend failures. */
public class BlobStoreException extends RuntimeException {

    public BlobStoreException(String message) {
        super(message);
    }

    public BlobStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Thrown by {@code get} / {@code getMetadata} / {@code delete} when the id is unknown. */
    public static final class NotFound extends BlobStoreException {
        public NotFound(String blobId) {
            super("Blob not found: " + blobId);
        }
    }
}
