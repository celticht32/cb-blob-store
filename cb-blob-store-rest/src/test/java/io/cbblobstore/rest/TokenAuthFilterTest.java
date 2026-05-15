/*
 * TokenAuthFilterTest.java — unit tests for the bearer-token auth filter
 *
 * Mocks Javalin's Context. Verifies:
 *   - missing/malformed Authorization header is rejected with 401
 *   - valid token passes through and stashes the principal
 *   - invalid token is rejected and the principal is not stashed
 *   - the healthz allow-list bypasses auth
 *   - constructing the filter with no tokens and auth enabled fails fast
 *   - constantTimeEquals returns the right result and stays constant-time on length mismatch
 *
 * Part of cb-blob-store.
 * Copyright (c) 2026 Chris Ahrendt
 * Licensed under the MIT License — see LICENSE for details.
 */
package io.cbblobstore.rest;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenAuthFilterTest {

    @Mock Context ctx;
    private final Map<String, Object> attrs = new HashMap<>();

    @BeforeEach
    void wireContextAttributeStub() {
        // Capture ctx.attribute(key, val) into the local map and
        // make ctx.attribute(key) return whatever's stored.
        lenient().doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).attribute(anyString(), any());
        lenient().when(ctx.attribute(anyString())).thenAnswer(inv -> attrs.get(inv.getArgument(0)));

        // Default ctx.status(...) returns ctx for chaining; same for header(...).
        lenient().when(ctx.status(any(HttpStatus.class))).thenReturn(ctx);
        lenient().when(ctx.header(anyString(), anyString())).thenReturn(ctx);
    }

    private TokenAuthFilter filter() {
        return new TokenAuthFilter(Set.of("good-token", "another-good-token"), false);
    }

    @Test
    void healthzBypassesAuth() {
        when(ctx.path()).thenReturn("/healthz");
        filter().handle(ctx);

        verify(ctx, never()).status(any(HttpStatus.class));
        verify(ctx, never()).skipRemainingHandlers();
    }

    @Test
    void missingAuthorizationHeaderIsRejected() {
        when(ctx.path()).thenReturn("/blobs");
        when(ctx.header("Authorization")).thenReturn(null);

        filter().handle(ctx);

        verify(ctx).status(HttpStatus.UNAUTHORIZED);
        verify(ctx).header("WWW-Authenticate", "Bearer");
        verify(ctx).skipRemainingHandlers();
    }

    @Test
    void wrongSchemeIsRejected() {
        when(ctx.path()).thenReturn("/blobs");
        when(ctx.header("Authorization")).thenReturn("Basic Zm9vOmJhcg==");

        filter().handle(ctx);

        verify(ctx).status(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownTokenIsRejected() {
        when(ctx.path()).thenReturn("/blobs");
        when(ctx.header("Authorization")).thenReturn("Bearer not-a-real-token");

        filter().handle(ctx);

        verify(ctx).status(HttpStatus.UNAUTHORIZED);
        assertThat(attrs).doesNotContainKey("auth.principal");
    }

    @Test
    void validTokenIsAcceptedAndPrincipalIsStashed() {
        when(ctx.path()).thenReturn("/blobs");
        when(ctx.header("Authorization")).thenReturn("Bearer good-token");

        filter().handle(ctx);

        verify(ctx, never()).status(any(HttpStatus.class));
        verify(ctx, never()).skipRemainingHandlers();
        assertThat(attrs.get("auth.principal")).asString().startsWith("token-");
        assertThat(attrs.get("auth.principal").toString()).doesNotContain("good-token");
    }

    @Test
    void principalOfReturnsAnonymousWhenUnset() {
        when(ctx.attribute("auth.principal")).thenReturn(null);
        assertThat(TokenAuthFilter.principalOf(ctx)).isEqualTo("anonymous");
    }

    @Test
    void disabledModePassesEverything() {
        TokenAuthFilter open = new TokenAuthFilter(Set.of(), true);

        open.handle(ctx);

        verify(ctx, never()).status(any(HttpStatus.class));
        verify(ctx, never()).skipRemainingHandlers();
        // never even consults the Authorization header
        verify(ctx, times(0)).header("Authorization");
        // never even consults the path
        verify(ctx, times(0)).path();
    }

    @Test
    void constructorFailsClosedWhenNoTokensAndNotDisabled() {
        assertThatThrownBy(() -> new TokenAuthFilter(Set.of(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CBBS_AUTH_TOKENS");
    }

    @Test
    void constantTimeEqualsReturnsTrueOnlyOnExactMatch() {
        byte[] a = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] b = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] c = "abd".getBytes(StandardCharsets.UTF_8);
        byte[] longer = "abcd".getBytes(StandardCharsets.UTF_8);

        assertThat(TokenAuthFilter.constantTimeEquals(a, b)).isTrue();
        assertThat(TokenAuthFilter.constantTimeEquals(a, c)).isFalse();
        assertThat(TokenAuthFilter.constantTimeEquals(a, longer)).isFalse();
    }
}
