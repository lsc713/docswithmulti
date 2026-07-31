package com.example.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** CSRF double-submit(쿠키==헤더). spring-security 미사용 — OncePerRequestFilter. */
public class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> PUBLIC = Set.of("/v1/auth/login", "/v1/auth/signup", "/v1/auth/refresh");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (requiresCheck(req)) {
            String cookie = cookie(req, "csrf_token");
            String header = req.getHeader("X-CSRF-Token");
            if (cookie == null || header == null || !cookie.equals(header)) {
                res.setStatus(HttpStatus.FORBIDDEN.value());
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.getWriter().write("{\"code\":\"CSRF_TOKEN_INVALID\",\"message\":\"CSRF 검증 실패\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private boolean requiresCheck(HttpServletRequest req) {
        return !SAFE.contains(req.getMethod()) && !PUBLIC.contains(req.getRequestURI());
    }

    private String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
