package com.example.gateway;

import com.example.gateway.config.JwtVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtVerifier 순수 단위 (D-P2-4, T-02-03/T-02-05). 스프링 컨텍스트 불요 — 생성자에 secret 직접 주입.
 * 게이트웨이는 verify-only 이므로 동일 secret accept / 위조·만료·변조·alg혼동 reject 만 검증한다.
 */
class JwtVerifierTest {

    private static final String SECRET =
            "default-dev-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256";
    private static final String WRONG_SECRET =
            "totally-different-attacker-secret-key-also-at-least-256-bits-long-xx";

    private final JwtVerifier verifier = new JwtVerifier(SECRET);

    @Test
    void sameSecret_parsesClaims() {
        String token = token(SECRET, 42L, "USER", 3_600_000L);

        Claims claims = verifier.parse(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
    }

    @Test
    void wrongSecret_rejected() {
        String forged = token(WRONG_SECRET, 42L, "USER", 3_600_000L);

        assertThatThrownBy(() -> verifier.parse(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredToken_rejected() {
        String expired = expiredToken(42L, "USER");

        assertThatThrownBy(() -> verifier.parse(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedPayload_rejected() {
        // 유효 토큰의 payload 세그먼트를 조작 → 서명 미갱신 → 검증 실패
        String[] parts = token(SECRET, 42L, "USER", 3_600_000L).split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA" + "." + parts[2];

        assertThatThrownBy(() -> verifier.parse(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void algNoneToken_rejected() {
        // alg=none 위장(서명 없는 unsigned JWT) → 대칭키 파서가 거부해야 함 (alg 혼동 방어)
        String unsigned = Jwts.builder()
                .subject("42")
                .claim("role", "ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .compact();

        assertThatThrownBy(() -> verifier.parse(unsigned))
                .isInstanceOf(JwtException.class);
    }

    private static String token(String secret, long userId, String role, long ttlMs) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMs))
                .signWith(key)
                .compact();
    }

    private static String expiredToken(long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date(now - 7_200_000L))
                .expiration(new Date(now - 3_600_000L))
                .signWith(key)
                .compact();
    }
}
