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
import static org.springframework.web.servlet.function.RequestPredicates.DELETE;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RequestPredicates.PUT;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * 게이트웨이 라우트 (GATE-01, D-P2-5). client-facing 서비스만 노출한다:
 * <ul>
 *   <li>payment 취소(인증): /v1/payments/** → payment downstream, JwtTrustHeaderFilter 부착.
 *       POST /v1/payments/{key}/cancel-requests(취소 승인요청 생성, cancel-approval P1)는 이 predicate가
 *       이미 커버하므로 별도 라우트 불필요</li>
 *   <li>취소 승인요청 조회/승인/반려(인증): /v1/cancel-requests/** → payment downstream,
 *       JwtTrustHeaderFilter 부착(cancel-approval P1, Task 7)</li>
 *   <li>user-service 공개(토큰 불요): /v1/auth/{signup,login,refresh} → user downstream, strip만</li>
 *   <li>user-service 인증: /v1/auth/{logout,me}, /v1/admin/** → user downstream, JwtTrustHeaderFilter 부착
 *       (admin/**의 role 인가는 user-service 자체 JwtAuthenticationFilter가 재검증)</li>
 *   <li>order 생성(인증): POST /v1/orders(정확 경로) → order downstream, JwtTrustHeaderFilter 부착.
 *       /v1/orders/items:verify(payment 전용 내부 검증)는 이 경로에 걸리지 않아 노출되지 않는다
 *       (D-CONTEXT-5, order-link Phase 1 GW-01)</li>
 *   <li>cart(인증): /v1/cart/** → order downstream(cart는 order-service에 위치), JwtTrustHeaderFilter 부착</li>
 *   <li>product 공개 브라우징(토큰 불요): GET /v1/products/**, /v1/categories/** → product downstream,
 *       strip만(Task 9)</li>
 *   <li>product 관리자 write(인증): POST /v1/products(시드), POST .../images/presign, POST .../images,
 *       DELETE .../images/{id}, PUT .../images/order → product downstream, JwtTrustHeaderFilter 부착.
 *       POST /v1/products(시드)는 ADMIN 인증 라우트로 노출된다 — downstream(product-service)이
 *       X-User-Role=ADMIN을 재검증(Task 8b)</li>
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
                .route(path("/v1/payments/**").or(path("/v1/payment-attempts/**")), http())
                .before(uri(paymentUri))
                .filter(jwt)
                .build();
    }

    /**
     * 취소 승인요청(cancel-requests) — 인증 라우트, payment downstream. 조회/승인/반려 등
     * {@code /v1/cancel-requests/**} 전체를 payment-service로 보낸다. 생성 경로
     * {@code POST /v1/payments/{key}/cancel-requests}는 이미 위 paymentRoute가 커버하므로 여기서는
     * 별도 predicate가 필요 없다(cancel-approval P1, Task 7).
     */
    @Bean
    RouterFunction<ServerResponse> cancelRequestsRoute(
            JwtTrustHeaderFilter jwt,
            @Value("${gateway.downstream.payment-uri}") String paymentUri) {
        return route("cancel-requests")
                .route(path("/v1/cancel-requests/**"), http())
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

    /**
     * user-service 인증 라우트(logout/me + admin/**) — 토큰 필요. strip→verify→inject는
     * JwtTrustHeaderFilter가 담당. /v1/admin/**(예: PATCH /v1/admin/users/{id}/role)는
     * user-service 자체 JwtAuthenticationFilter가 hasRole("ADMIN")을 재검증하므로, 게이트웨이는
     * /v1/auth/me와 동일하게 토큰 유효성만 보고 신뢰헤더를 전달한다 — 새 인증 메커니즘 아님.
     */
    @Bean
    RouterFunction<ServerResponse> userAuthSecuredRoute(
            JwtTrustHeaderFilter jwt,
            @Value("${gateway.downstream.user-uri}") String userUri) {
        RequestPredicate secured = path("/v1/auth/logout")
                .or(path("/v1/auth/me"))
                .or(path("/v1/admin/**"));
        return route("user-auth-secured")
                .route(secured, http())
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

    /** 장바구니(cart) — 인증 라우트. order-service downstream(cart는 order-service에 위치). */
    @Bean
    RouterFunction<ServerResponse> cartRoute(
            JwtTrustHeaderFilter jwt,
            @Value("${gateway.downstream.order-uri}") String orderUri) {
        return route("cart")
                .route(path("/v1/cart/**"), http())
                .before(uri(orderUri))
                .filter(jwt)
                .build();
    }

    /**
     * product-service 공개 브라우징(GET만) — 토큰 불요. userAuthPublicRoute와 동일하게 신뢰 헤더는
     * 무조건 strip(공개 GET에도 클라 위조 X-User-* 차단 — T-02-01/D-P2-3 동일 원칙).
     * POST /v1/products(시드)는 GET이 아니므로 이 predicate에는 걸리지 않지만, 별도 인증 라우트
     * (productAdminWriteRoute)로 노출된다(Task 8b).
     */
    @Bean
    RouterFunction<ServerResponse> productBrowseRoute(
            @Value("${gateway.downstream.product-uri}") String productUri) {
        RequestPredicate browse = GET("/v1/products/**").or(GET("/v1/categories/**"));
        return route("product-browse")
                .route(browse, http())
                .before(uri(productUri))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_USER_ID))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_USER_ROLE))
                .before(removeRequestHeader(JwtTrustHeaderFilter.H_MERCHANT_ID))
                .build();
    }

    /**
     * product-service 관리자 write(카테고리/상품 생성 + 이미지 presign/confirm/delete/reorder) — 인증 라우트.
     * POST /v1/categories와 POST /v1/products(각각 정확 경로)는 ADMIN 전용 생성 — downstream(product-service)이
     * X-User-Role=ADMIN을 재검증한다. 이미지 실제 엔드포인트는 "/images:presign"(콜론 리터럴)이
     * 아니라 "/images/presign"(세그먼트) — Task 6에서 Spring MVC가 ':presign' 콜론 형태를 라우팅하지
     * 못한다고 확인돼 세그먼트 형태로 구현됐다.
     * strip→verify→inject는 JwtTrustHeaderFilter가 담당.
     */
    @Bean
    RouterFunction<ServerResponse> productAdminWriteRoute(
            JwtTrustHeaderFilter jwt,
            @Value("${gateway.downstream.product-uri}") String productUri) {
        RequestPredicate write = POST("/v1/categories")
                .or(POST("/v1/products/*/images/presign"))
                .or(POST("/v1/products/*/images"))
                .or(DELETE("/v1/products/*/images/*"))
                .or(PUT("/v1/products/*/images/order"))
                .or(POST("/v1/products"));
        return route("product-admin-write")
                .route(write, http())
                .before(uri(productUri))
                .filter(jwt)
                .build();
    }
}
