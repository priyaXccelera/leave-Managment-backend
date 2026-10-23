package com.company.leave.common.security;

import com.company.leave.common.api.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and parses HS256 access tokens. Shared by every service via the same secret.
 */
public class JwtService {

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtService(String secret, long accessTtlSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes long");
        }
        if (accessTtlSeconds <= 0) {
            throw new IllegalStateException("ACCESS_TOKEN_TTL_SECONDS must be positive");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = accessTtlSeconds;
    }

    public String generateAccessToken(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("code", user.employeeCode())
                .claim("name", user.name())
                .claim("email", user.email())
                .claim("role", user.role())
                .claim("department", user.department())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public AuthUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new AuthUser(Long.valueOf(claims.getSubject()),
                    claims.get("code", String.class),
                    claims.get("name", String.class),
                    claims.get("email", String.class),
                    claims.get("role", String.class),
                    claims.get("department", String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            throw ApiException.unauthorized("Invalid or expired token");
        }
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }
}
