package com.example.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * verify-only JWT 래퍼 (D-P2-4). user-service JwtTokenProvider 계약 미러 —
 * 동일 HS256 · 동일 secret. 발급(create) 로직·UserRole enum 불필요.
 * 만료/서명오류는 jjwt 예외를 그대로 던져 필터가 401 분기한다.
 */
@Component
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
