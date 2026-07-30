package com.example.user.infrastructure.security;

import com.example.user.domain.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessTokenExpiry;
    private final long refreshTokenExpiry;

    public JwtTokenProvider(String secret, long accessTokenExpiry, long refreshTokenExpiry) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public String createAccessToken(long userId, UserRole role, Long merchantId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiry);

        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey);

        if (merchantId != null) {
            builder.claim("merchantId", merchantId);
        }

        return builder.compact();
    }

    public String createRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    public String getRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public Long getMerchantId(String token) {
        Claims claims = getClaims(token);
        Object merchantId = claims.get("merchantId");
        if (merchantId == null) {
            return null;
        }
        if (merchantId instanceof Long l) {
            return l;
        }
        if (merchantId instanceof Integer i) {
            return i.longValue();
        }
        return ((Number) merchantId).longValue();
    }

    public long getRefreshTokenExpiry() {
        return refreshTokenExpiry;
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
