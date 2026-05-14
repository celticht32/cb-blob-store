/*
 * IdValidator.java — validates caller-supplied blob ids
 *
 * Defends against path traversal, control characters, and unbounded length
 * in S3 object keys built from caller input.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.core;

import java.util.regex.Pattern;

/**
 * Validates caller-supplied blob ids before they are interpolated into S3 object
 * keys or used as Couchbase document keys.
 *
 * <p>Allowed characters: ASCII letters, digits, dash, underscore, dot. Length 1–250.
 * Path traversal sequences ({@code ..}), leading/trailing dots, and whitespace are
 * rejected. Generated UUIDs always pass this check.</p>
 */
public final class IdValidator {

    /** Maximum id length. Couchbase document keys are capped at 250 bytes. */
    public static final int MAX_LENGTH = 250;

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]+");

    private IdValidator() {}

    /**
     * @throws IllegalArgumentException if {@code id} is null, empty, too long,
     *     contains disallowed characters, contains {@code ..}, or starts/ends with a dot.
     */
    public static void requireValid(String id) {
        if (id == null) {
            throw new IllegalArgumentException("blob id must not be null");
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException("blob id must not be empty");
        }
        if (id.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "blob id length " + id.length() + " exceeds maximum " + MAX_LENGTH);
        }
        if (!ALLOWED.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "blob id contains disallowed characters; allowed: [A-Za-z0-9._-]");
        }
        if (id.contains("..")) {
            throw new IllegalArgumentException("blob id must not contain '..'");
        }
        if (id.charAt(0) == '.' || id.charAt(id.length() - 1) == '.') {
            throw new IllegalArgumentException("blob id must not start or end with '.'");
        }
    }

    /** Convenience: returns the id unchanged if valid, otherwise throws. */
    public static String checked(String id) {
        requireValid(id);
        return id;
    }
}
