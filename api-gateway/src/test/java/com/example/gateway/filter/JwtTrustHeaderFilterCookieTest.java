package com.example.gateway.filter;

import com.example.gateway.config.JwtVerifier;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTrustHeaderFilterCookieTest {
    static final String SECRET = "default-dev-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256";

    static String jwt(long uid, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().subject(String.valueOf(uid)).claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).signWith(key).compact();
    }

    @Test
    void readsTokenFromCookie_injectsTrustHeaders() throws Exception {
        JwtTrustHeaderFilter filter = new JwtTrustHeaderFilter(new JwtVerifier(SECRET));
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/v1/auth/me");
        raw.setCookies(new jakarta.servlet.http.Cookie("access_token", jwt(42L, "USER")));
        ServerRequest req = ServerRequest.create(raw, java.util.List.of());

        final String[] seenUserId = new String[1];
        ServerResponse resp = filter.filter(req, r -> {
            seenUserId[0] = r.headers().firstHeader(JwtTrustHeaderFilter.H_USER_ID);
            return ServerResponse.ok().build();
        });

        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(seenUserId[0]).isEqualTo("42");
    }
}
