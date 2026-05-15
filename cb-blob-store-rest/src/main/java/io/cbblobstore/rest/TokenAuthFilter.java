/*
 * TokenAuthFilter.java — bearer-token authentication for the REST sidecar
 *
 * Compares the Authorization header value against the configured token set
 * using a constant-time check to avoid timing side channels.
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.rest;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates {@code Authorization: Bearer <token>} headers.
 *
 * <p>Tokens are loaded from configuration at startup. The filter rejects with
 * {@code 401 Unauthorized} when:</p>
 * <ul>
 *   <li>The header is missing or malformed;</li>
 *   <li>The token is not in the configured set.</li>
 * </ul>
 *
 * <p>Comparisons are constant-time per token length to prevent timing oracle attacks.</p>
 */
public final class TokenAuthFilter {

    /** Path prefixes that bypass authentication. */
    private static final Set<String> ALLOW_LIST = Set.of("/healthz");

    private final Set<String> tokens;
    private final boolean disabled;

    /**
     * @param tokens   the accepted bearer tokens
     * @param disabled if {@code true}, all requests pass (development only)
     */
    public TokenAuthFilter(Set<String> tokens, boolean disabled) {
        this.tokens = Collections.unmodifiableSet(new HashSet<>(tokens));
        this.disabled = disabled;
        if (!disabled && tokens.isEmpty()) {
            throw new IllegalStateException(
                    "auth is enabled but no tokens configured (set CBBS_AUTH_TOKENS or CBBS_AUTH_DISABLED=true)");
        }
    }

    /** Javalin "before" handler entry point. */
    public void handle(Context ctx) {
        if (disabled) return;
        if (ALLOW_LIST.contains(ctx.path())) return;

        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(ctx, "missing bearer token");
            return;
        }
        String presented = header.substring("Bearer ".length()).trim();
        if (presented.isEmpty() || !matchesAny(presented)) {
            unauthorized(ctx, "invalid token");
            return;
        }
        // Stash the (hashed prefix of the) token for the audit log to attribute the call.
        ctx.attribute("auth.principal", hashIdentity(presented));
    }

    /** @return the principal stashed by a previous {@link #handle(Context)} call, or {@code "anonymous"}. */
    public static String principalOf(Context ctx) {
        Object p = ctx.attribute("auth.principal");
        return p == null ? "anonymous" : p.toString();
    }

    private boolean matchesAny(String presented) {
        // Run the same comparison work for every configured token, regardless of
        // whether one matched earlier, so total time doesn't reveal a hit or
        // near-miss. MessageDigest.isEqual is documented constant-time since JDK 7.
        boolean hit = false;
        byte[] pBytes = presented.getBytes(StandardCharsets.UTF_8);
        for (String tok : tokens) {
            byte[] tBytes = tok.getBytes(StandardCharsets.UTF_8);
            if (constantTimeEquals(pBytes, tBytes)) {
                hit = true;
                // do not break — keep iterating to maintain timing characteristics
            }
        }
        return hit;
    }

    /**
     * Constant-time byte-array equality. Length differences are masked by padding
     * both inputs to a common max length before comparison, so the loop runs the
     * same number of iterations regardless of inputs. This prevents an attacker
     * from inferring the configured token length from observable response time.
     *
     * <p>Delegates the actual comparison to {@link java.security.MessageDigest#isEqual},
     * which the JDK documents as constant-time.</p>
     */
    static boolean constantTimeEquals(byte[] a, byte[] b) {
        // Pad to a common length so we don't short-circuit on length mismatch.
        // The padded comparison still distinguishes "same length, different bytes"
        // from "different lengths" thanks to mismatching pad regions, which is the
        // correct outcome — but the loop time doesn't reveal which.
        int max = Math.max(a.length, b.length);
        byte[] aPad = a.length == max ? a : java.util.Arrays.copyOf(a, max);
        byte[] bPad = b.length == max ? b : java.util.Arrays.copyOf(b, max);
        // Combine the constant-time comparison with a length check so we still
        // reject correctly when one is a prefix of the other (e.g. configured
        // token "abc" vs presented "abc\0\0").
        return java.security.MessageDigest.isEqual(aPad, bPad) && a.length == b.length;
    }

    /**
     * Stable, non-reversible identifier for the audit log. We never log the raw token.
     * The first 8 hex chars of SHA-256 are enough to disambiguate operators in practice.
     */
    private static String hashIdentity(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("token-");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "token-unknown";
        }
    }

    private static void unauthorized(Context ctx, String reason) {
        ctx.header("WWW-Authenticate", "Bearer");
        ctx.status(HttpStatus.UNAUTHORIZED).result(reason);
        ctx.skipRemainingHandlers();
    }
}
