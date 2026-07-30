package com.example.gateway.config;

import com.example.gateway.filter.JwtTrustHeaderFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * 게이트웨이 라우트 (GATE-01, D-P2-5). 이 tracer는 두 경로만으로 per-route 라우팅을 증명한다:
 * <ul>
 *   <li>payment 취소(인증 필요): /v1/payments/** → payment downstream, JwtTrustHeaderFilter 부착</li>
 *   <li>user 로그인(공개): /v1/auth/login → user downstream, JWT 필터 없음(토큰 없이 통과)</li>
 * </ul>
 * order/merchant/risk 및 나머지 공개/보호 경로는 Plan 02에서 확장.
 */
@Configuration
public class RouteConfig {

    /** payment 취소 — 인증 라우트. strip→verify→inject는 JwtTrustHeaderFilter가 담당. */
    @Bean
    RouterFunction<ServerResponse> paymentRoute(
            JwtTrustHeaderFilter jwt,
            @Value("${gateway.downstream.payment-uri}") String paymentUri) {
        return route("payment")
                .route(path("/v1/payments/**"), http())
                .before(uri(paymentUri))
                .filter(jwt)
                .build();
    }

    /**
     * user 로그인 — 공개 라우트(토큰 불요). JWT 필터는 없지만 클라 위조 신뢰 헤더는 여기서도
     * 무조건 strip(공개 경로도 downstream이 실수로 읽을 여지 차단 — T-02-01).
     */
    @Bean
    RouterFunction<ServerResponse> userAuthPublicRoute(
            @Value("${gateway.downstream.user-uri}") String userUri) {
        return route("user-auth-public")
                .route(path("/v1/auth/login"), http())
                .before(uri(userUri))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_USER_ID))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_USER_ROLE))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_MERCHANT_ID))
                .build();
    }
}
