package com.example.gateway.filter;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfFilterTest {
    private final CsrfFilter filter = new CsrfFilter();

    @Test
    void rejectsPostWithoutCsrf() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/auth/logout");
        // access_token 쿠키 있음 → 브라우저 쿠키 인증 취급, Bearer-예외 대상 아님(Finding 2)
        req.setCookies(new Cookie("access_token", "jwt"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(403);
        // Finding 1: charset 미지정 → getWriter() 기본 ISO-8859-1로 한글 깨짐 방지
        assertThat(res.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"CSRF_TOKEN_INVALID\",\"message\":\"CSRF 검증 실패\"}");
    }

    @Test
    void bearerOnly_noCookies_noCsrf_passes() throws Exception {
        // Finding 2: CSRF는 쿠키 인증에만 유효한 위협 — 순수 Bearer 클라이언트는 예외
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/auth/logout");
        req.addHeader("Authorization", "Bearer some.jwt.token");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void accessTokenCookiePresent_noCsrf_rejectedEvenWithBearerHeader() throws Exception {
        // access_token 쿠키가 있으면 Bearer 헤더가 같이 와도 CSRF는 강제된다
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/auth/logout");
        req.setCookies(new Cookie("access_token", "jwt"));
        req.addHeader("Authorization", "Bearer some.jwt.token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsPostWithMatchingCsrf() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/auth/logout");
        req.setCookies(new Cookie("csrf_token", "abc"));
        req.addHeader("X-CSRF-Token", "abc");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull(); // 통과(다음 필터 호출됨)
    }

    @Test
    void skipsPublicLogin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/auth/login");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull(); // CSRF 없이 통과
    }

    @Test
    void skipsSafeGet() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/auth/me");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
