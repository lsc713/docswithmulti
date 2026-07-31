package com.example.user.presentation.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthCookieFactory")
class AuthCookieFactoryTest {

    // secure=true, sameSite 기본, domain 없음, refresh 만료 14일(ms)
    private final AuthCookieFactory factory =
            new AuthCookieFactory(true, null, 14L * 24 * 3600 * 1000);

    @Test
    @DisplayName("access 쿠키 — HttpOnly/Secure/SameSite=Lax/세션")
    void accessCookie() {
        ResponseCookie c = factory.access("jwt-token");
        assertThat(c.getName()).isEqualTo("access_token");
        assertThat(c.getValue()).isEqualTo("jwt-token");
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.isSecure()).isTrue();
        assertThat(c.getSameSite()).isEqualTo("Lax");
        assertThat(c.getPath()).isEqualTo("/");
        assertThat(c.getMaxAge().getSeconds()).isEqualTo(-1); // 세션 쿠키
    }

    @Test
    @DisplayName("refresh 쿠키 — HttpOnly/SameSite=Strict/path 제한/Max-Age")
    void refreshCookie() {
        ResponseCookie c = factory.refresh("rt-uuid");
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSameSite()).isEqualTo("Strict");
        assertThat(c.getPath()).isEqualTo("/v1/auth/refresh");
        assertThat(c.getMaxAge().getSeconds()).isEqualTo(14L * 24 * 3600);
    }

    @Test
    @DisplayName("csrf 쿠키 — non-HttpOnly(JS 읽기 가능)")
    void csrfCookie() {
        ResponseCookie c = factory.csrf("csrf-val");
        assertThat(c.getName()).isEqualTo("csrf_token");
        assertThat(c.isHttpOnly()).isFalse();
        assertThat(c.isSecure()).isTrue();
    }

    @Test
    @DisplayName("expireAll — 3종 Max-Age=0, path 일치")
    void expireAll() {
        List<ResponseCookie> cookies = factory.expireAll();
        assertThat(cookies).hasSize(3);
        assertThat(cookies).allSatisfy(c -> assertThat(c.getMaxAge().getSeconds()).isEqualTo(0));
        assertThat(cookies).anySatisfy(c -> {
            assertThat(c.getName()).isEqualTo("refresh_token");
            assertThat(c.getPath()).isEqualTo("/v1/auth/refresh");
        });
    }

    @Test
    @DisplayName("newCsrfValue — 매번 다른 난수")
    void csrfValueRandom() {
        assertThat(factory.newCsrfValue()).isNotEqualTo(factory.newCsrfValue());
    }
}
