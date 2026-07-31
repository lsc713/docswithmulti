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
 * 게이트웨이 라우트 (GATE-01, D-P2-5). client-facing 서비스만 노출한다:
 * <ul>
 *   <li>payment 취소(인증): /v1/payments/** → payment downstream, JwtTrustHeaderFilter 부착</li>
 *   <li>user-service 공개(토큰 불요): /v1/auth/{signup,login,refresh} → user downstream, strip만</li>
 *   <li>user-service 인증: /v1/auth/{logout,me} → user downstream, JwtTrustHeaderFilter 부착</li>
 * </ul>
 * order/merchant-limit/risk는 payment가 HTTP/Kafka로 부르는 <b>내부</b> 서비스 → 게이트웨이 미노출(D-P2-5).
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
     * user-service 공개 3경로(signup/login/refresh) — 토큰 불요. JWT 필터는 없지만 클라 위조
     * 신뢰 헤더는 여기서도 무조건 strip(공개 경로도 downstream이 실수로 읽을 여지 차단 — T-02-01/D-P2-3).
     */
    @Bean
    RouterFunction<ServerResponse> userAuthPublicRoute(
            @Value("${gateway.downstream.user-uri}") String userUri) {
        return route("user-auth-public")
                .route(path("/v1/auth/signup")
                                .or(path("/v1/auth/login"))
                                .or(path("/v1/auth/refresh")),
                        http())
                .before(uri(userUri))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_USER_ID))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_USER_ROLE))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_MERCHANT_ID))
                .build();
    }

    /** user-service 인증 라우트(logout/me) — 토큰 필요. strip→verify→inject는 JwtTrustHeaderFilter가 담당. */
    @Bean
    RouterFunction<ServerResponse> userAuthSecuredRoute(
            JwtTrustHeaderFilter jwt,
            @Value("${gateway.downstream.user-uri}") String userUri) {
        return route("user-auth-secured")
                .route(path("/v1/auth/logout").or(path("/v1/auth/me")), http())
                .before(uri(userUri))
                .filter(jwt)
                .build();
    }
}
