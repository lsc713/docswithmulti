package com.example.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {
    @Test
    void allowsWhitelistedOriginWithCredentials() {
        var source = new CorsConfig().corsConfigurationSource(List.of("http://localhost:5173"));
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/v1/auth/login");
        req.addHeader("Origin", "http://localhost:5173");

        CorsConfiguration cfg = source.getCorsConfiguration(req);

        assertThat(cfg.getAllowCredentials()).isTrue();
        assertThat(cfg.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cfg.checkOrigin("http://evil.com")).isNull();
        assertThat(cfg.getAllowedHeaders()).contains("X-CSRF-Token", "Content-Type");
        // CSRF가 보호하는 상태변경 메서드까지 CORS 허용 (M1)
        assertThat(cfg.getAllowedMethods()).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }
}
