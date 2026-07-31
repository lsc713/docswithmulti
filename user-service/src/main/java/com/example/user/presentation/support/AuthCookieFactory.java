package com.example.user.presentation.support;

import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 인증 쿠키 3종 생성/만료 일원화. 속성은 설정 주입(secure/sameSite domain). */
public class AuthCookieFactory {

    public static final String ACCESS = "access_token";
    public static final String REFRESH = "refresh_token";
    public static final String CSRF = "csrf_token";
    private static final String REFRESH_PATH = "/v1/auth/refresh";

    private final boolean secure;
    private final String domain;         // null이면 미설정(호스트 전용 쿠키)
    private final long refreshMaxAgeSec;

    public AuthCookieFactory(boolean secure, String domain, long refreshExpiryMs) {
        this.secure = secure;
        this.domain = domain;
        this.refreshMaxAgeSec = refreshExpiryMs / 1000;
    }

    public ResponseCookie access(String jwt) {
        return base(ACCESS, jwt, "/").httpOnly(true).sameSite("Lax").build(); // 세션 쿠키(maxAge 기본 -1)
    }

    public ResponseCookie refresh(String token) {
        return base(REFRESH, token, REFRESH_PATH).httpOnly(true).sameSite("Strict")
                .maxAge(Duration.ofSeconds(refreshMaxAgeSec)).build();
    }

    public ResponseCookie csrf(String value) {
        return base(CSRF, value, "/").httpOnly(false).sameSite("Lax").build();
    }

    public String newCsrfValue() {
        return UUID.randomUUID().toString();
    }

    public List<ResponseCookie> expireAll() {
        return List.of(
                base(ACCESS, "", "/").httpOnly(true).maxAge(0).build(),
                base(REFRESH, "", REFRESH_PATH).httpOnly(true).maxAge(0).build(),
                base(CSRF, "", "/").httpOnly(false).maxAge(0).build());
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value).path(path).secure(secure);
        if (domain != null && !domain.isBlank()) b.domain(domain);
        return b;
    }
}
