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
