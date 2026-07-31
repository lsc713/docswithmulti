package com.example.user.infrastructure.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    @Test
    void authenticatesFromAccessTokenCookie() throws Exception {
        JwtTokenProvider provider = mock(JwtTokenProvider.class);
        when(provider.validateToken("cookie-jwt")).thenReturn(true);
        when(provider.getUserId("cookie-jwt")).thenReturn(42L);
        when(provider.getRole("cookie-jwt")).thenReturn("USER");
        when(provider.getMerchantId("cookie-jwt")).thenReturn(null);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("access_token", "cookie-jwt"));  // Authorization 헤더 없음

        new JwtAuthenticationFilter(provider)
                .doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(42L);
        SecurityContextHolder.clearContext();
    }
}
