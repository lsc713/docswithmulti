package com.example.gateway.config;

import java.util.List;

/**
 * 게이트웨이 경로 상수 단일 출처(single source of truth).
 * 공개 인증 경로는 라우팅(RouteConfig)과 CSRF 면제(CsrfFilter)가 반드시 동일해야 하므로
 * 여기 한 곳에서만 정의한다 — 두 목록이 어긋나는 드리프트를 원천 차단.
 */
public final class GatewayPaths {
    private GatewayPaths() {}

    /** 토큰 불요 공개 인증 경로. 여기 추가/삭제하면 라우팅과 CSRF 면제가 함께 반영된다. */
    public static final List<String> PUBLIC_AUTH = List.of(
            "/v1/auth/signup", "/v1/auth/login", "/v1/auth/refresh");
}
