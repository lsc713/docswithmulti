package com.example.gateway.config;

import com.example.gateway.filter.JwtTrustHeaderFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
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
 *   <li>order 생성(인증): POST /v1/orders(정확 경로) → order downstream, JwtTrustHeaderFilter 부착.
 *       /v1/orders/items:verify(payment 전용 내부 검증)는 이 경로에 걸리지 않아 노출되지 않는다
 *       (D-CONTEXT-5, order-link Phase 1 GW-01)</li>
 * </ul>
 * merchant-limit/risk는 payment가 HTTP/Kafka로 부르는 <b>내부</b> 서비스 → 게이트웨이 미노출(D-P2-5).
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
        // 공개 인증 경로는 GatewayPaths 단일 출처에서 predicate로 조합 (CsrfFilter 면제 목록과 공유).
        RequestPredicate publicAuth = GatewayPaths.PUBLIC_AUTH.stream()
                .map(RequestPredicates::path)
                .reduce(RequestPredicate::or)
                .orElseThrow();
        return route("user-auth-public")
                .route(publicAuth, http())
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

    /**
     * order 생성 — 인증 라우트. predicate는 정확히 "/v1/orders"(NOT "/v1/orders/**") — 내부 전용
     * "/v1/orders/items:verify"가 이 경로에 걸려 노출되지 않도록 하는 것이 핵심(D-CONTEXT-5, GW-01).
     * GET /v1/orders/{id}는 아직 read 핸들러가 없어 라우팅하지 않는다(추가 시 별도 경로로 노출).
     */
    @Bean
    RouterFunction<ServerResponse> orderRoute(
            JwtTrustHeaderFilter jwt,
            @Value("${gateway.downstream.order-uri}") String orderUri) {
        return route("order")
                .route(path("/v1/orders"), http())
                .before(uri(orderUri))
                .filter(jwt)
                .build();
    }
}
