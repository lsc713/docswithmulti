package com.example.user.infrastructure.security;

import com.example.user.domain.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        String secret = "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256-algo";
        provider = new JwtTokenProvider(secret, 3600000L, 604800000L);
    }

    @Test
    @DisplayName("USER 역할 — Access Token 생성 및 파싱")
    void shouldCreateAndParseUserToken() {
        String token = provider.createAccessToken(1L, UserRole.USER, null);
        assertTrue(provider.validateToken(token));
        assertEquals(1L, provider.getUserId(token));
        assertEquals("USER", provider.getRole(token));
        assertNull(provider.getMerchantId(token));
    }

    @Test
    @DisplayName("MERCHANT 역할 — merchantId 포함")
    void shouldIncludeMerchantId() {
        String token = provider.createAccessToken(2L, UserRole.MERCHANT, 100L);
        assertEquals(2L, provider.getUserId(token));
        assertEquals("MERCHANT", provider.getRole(token));
        assertEquals(100L, provider.getMerchantId(token));
    }

    @Test
    @DisplayName("Refresh Token 생성")
    void shouldCreateRefreshToken() {
        String token = provider.createRefreshToken();
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("만료된 토큰 — validateToken false")
    void shouldRejectExpiredToken() {
        JwtTokenProvider shortLived = new JwtTokenProvider(
            "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256-algo", 0L, 0L);
        String token = shortLived.createAccessToken(1L, UserRole.USER, null);
        assertFalse(shortLived.validateToken(token));
    }

    @Test
    @DisplayName("잘못된 토큰 — validateToken false")
    void shouldRejectInvalidToken() {
        assertFalse(provider.validateToken("invalid.token.value"));
    }

    @Test
    @DisplayName("getRefreshTokenExpiry — 밀리초 반환")
    void shouldReturnRefreshTokenExpiry() {
        assertEquals(604800000L, provider.getRefreshTokenExpiry());
    }
}
