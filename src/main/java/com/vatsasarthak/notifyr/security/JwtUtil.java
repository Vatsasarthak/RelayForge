package com.vatsasarthak.notifyr.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@Slf4j
public class JwtUtil {

    // HS256 needs a key of at least 256 bits
    static final int MIN_SECRET_BYTES = 32;

    // no default on purpose: a missing JWT_SECRET must stop startup instead of silently using a shared dev key
    @Value("${notification.jwt.secret}")
    private String secret;

    @Value("${notification.jwt.expiration-ms:3600000}")
    private long expirationMs;

    private SecretKey signingKey;

    public JwtUtil() {
    }

    JwtUtil(String secret, long expirationMs) {
        this.secret = secret;
        this.expirationMs = expirationMs;
        init();
    }

    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET (notification.jwt.secret) must be set");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET (notification.jwt.secret) must be at least "
                    + MIN_SECRET_BYTES + " bytes long");
        }
        if (secret.startsWith("change-me")) {
            log.warn("JWT_SECRET still has the placeholder value from .env.example - replace it before any real use");
        }
        signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String username, String role){
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public String extractUsername(String token){
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token){
        return parseClaims(token).get("role", String.class);
    }

    // false for malformed, tampered or expired tokens
    public boolean isTokenValid(String token){
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
