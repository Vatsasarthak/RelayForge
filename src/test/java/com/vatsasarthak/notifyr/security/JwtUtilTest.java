package com.vatsasarthak.notifyr.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough-1234";

    @Test
    void generatedTokenIsValidAndCarriesUsernameAndRole() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000);

        String token = jwtUtil.generateToken("alice", "ADMIN");

        assertThat(jwtUtil.isTokenValid(token)).isTrue();
        assertThat(jwtUtil.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtUtil.extractRole(token)).isEqualTo("ADMIN");
    }

    @Test
    void expiredTokenIsRejected() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, -1_000);

        String token = jwtUtil.generateToken("alice", "ADMIN");

        assertThat(jwtUtil.isTokenValid(token)).isFalse();
    }

    @Test
    void tamperedOrForeignTokensAreRejected() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000);
        JwtUtil otherIssuer = new JwtUtil("another-secret-that-is-also-long-enough-99", 60_000);

        String token = jwtUtil.generateToken("alice", "API_CLIENT");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        assertThat(jwtUtil.isTokenValid(tampered)).isFalse();
        assertThat(jwtUtil.isTokenValid(otherIssuer.generateToken("alice", "ADMIN"))).isFalse();
        assertThat(jwtUtil.isTokenValid("not.a.jwt")).isFalse();
        assertThat(jwtUtil.isTokenValid("")).isFalse();
    }

    @Test
    void missingOrShortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtUtil("", 60_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be set");
        assertThatThrownBy(() -> new JwtUtil(null, 60_000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtUtil("too-short", 60_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
