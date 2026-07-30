package com.example.user.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RefreshToken 도메인 엔티티")
class RefreshTokenTest {
    @Test
    @DisplayName("리프레시 토큰 생성")
    void shouldCreate() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        RefreshToken token = RefreshToken.of(1L, "token-value", expiresAt);
        assertEquals(1L, token.getUserId());
        assertEquals("token-value", token.getToken());
        assertEquals(expiresAt, token.getExpiresAt());
    }

    @Test
    @DisplayName("만료되지 않은 토큰 — isExpired false")
    void shouldNotBeExpired() {
        RefreshToken token = RefreshToken.of(1L, "token", Instant.now().plus(1, ChronoUnit.HOURS));
        assertFalse(token.isExpired());
    }

    @Test
    @DisplayName("만료된 토큰 — isExpired true")
    void shouldBeExpired() {
        RefreshToken token = RefreshToken.of(1L, "token", Instant.now().minus(1, ChronoUnit.HOURS));
        assertTrue(token.isExpired());
    }
}
