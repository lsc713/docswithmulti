package com.example.gateway.integration;

import com.example.gateway.filter.JwtTrustHeaderFilter;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracer end-to-end 증명 (GATE-01 라우팅 + GATE-02 신뢰헤더 전달 happy path).
 * payment/user 두 downstream을 각각 별도 WireMock 스텁으로 두어 per-route 라우팅을 진짜로 증명한다.
 * Boot 4 테스트 제약(TestRestTemplate 미제공)에 대비해 JDK HttpClient로 게이트웨이 실포트를 호출.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIT {

    // user-service local 프로파일 기본 secret과 동일 문자열 (HS256 대칭키 공유 — Pitfall 4)
    private static final String SECRET =
            "default-dev-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256";
    // 게이트웨이가 모르는 다른 secret — 서명 불일치(TOKEN_INVALID) 케이스용 (>=256bit)
    private static final String WRONG_SECRET =
            "totally-different-attacker-secret-key-also-at-least-256-bits-long-xx";

    static final WireMockServer paymentDownstream = new WireMockServer(options().dynamicPort());
    static final WireMockServer userDownstream = new WireMockServer(options().dynamicPort());
    static final WireMockServer orderDownstream = new WireMockServer(options().dynamicPort());
    static final WireMockServer productDownstream = new WireMockServer(options().dynamicPort());

    static {
        // @DynamicPropertySource 서플라이어가 포트를 읽기 전에 기동돼 있어야 함
        paymentDownstream.start();
        userDownstream.start();
        orderDownstream.start();
        productDownstream.start();
    }

    @AfterAll
    static void stopDownstreams() {
        paymentDownstream.stop();
        userDownstream.stop();
        orderDownstream.stop();
        productDownstream.stop();
    }

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        registry.add("gateway.downstream.payment-uri", () -> "http://localhost:" + paymentDownstream.port());
        registry.add("gateway.downstream.user-uri", () -> "http://localhost:" + userDownstream.port());
        registry.add("gateway.downstream.order-uri", () -> "http://localhost:" + orderDownstream.port());
        registry.add("gateway.downstream.product-uri", () -> "http://localhost:" + productDownstream.port());
    }

    @LocalServerPort
    int gatewayPort;

    private final HttpClient http = HttpClient.newHttpClient();

    // CSRF double-submit(Task 8, Finding 2로 Bearer 클라는 예외) — payments/logout는 CSRF
    // 예외(로그인/회원가입/refresh)에 없는 상태변경 라우트지만, Authorization: Bearer 헤더가 있고
    // access_token 쿠키가 없으면 CSRF는 스킵된다(비-브라우저 클라는 CSRF 취약점이 없음). 아래
    // Authorization 헤더가 아예 없는 두 케이스(진짜 "토큰 없음")만 CSRF 관문을 넘겨야 JWT 로직의
    // TOKEN_MISSING 분기가 노출되므로 고정 토큰을 계속 동봉한다.
    private static final String CSRF_TOKEN = "it-fixed-csrf-token";

    private HttpRequest.Builder withCsrf(HttpRequest.Builder b) {
        return b.header("Cookie", "csrf_token=" + CSRF_TOKEN).header("X-CSRF-Token", CSRF_TOKEN);
    }

    @BeforeEach
    void resetStubs() {
        paymentDownstream.resetAll();
        userDownstream.resetAll();
        orderDownstream.resetAll();
        productDownstream.resetAll();
    }

    @Test
    void validJwt_routesToPaymentDownstream_withTrustHeaders_strippingSpoofed() throws Exception {
        paymentDownstream.stubFor(post(urlPathMatching("/v1/payments/.*/cancel"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"CANCELLED\"}")));

        String token = accessToken(42L, "USER", 7L);
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/payments/PK123/cancel")))
                        .header("Authorization", "Bearer " + token)
                        .header(JwtTrustHeaderFilter.H_USER_ID, "999") // 위조 시도 → strip 되어야 함
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);

        // GATE-01(payment 경로 라우팅) + GATE-02(신뢰헤더 주입) + strip(위조 999 → 게이트웨이 42) 동시 증명
        paymentDownstream.verify(postRequestedFor(urlPathMatching("/v1/payments/.*/cancel"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ID, equalTo("42"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ROLE, equalTo("USER")));
        // per-route 증명: payment 경로는 user downstream으로 새지 않는다
        userDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void publicLoginPath_routesToUserDownstream() throws Exception {
        userDownstream.stubFor(get(urlPathEqualTo("/v1/auth/login"))
                .willReturn(aResponse().withStatus(200).withBody("{\"accessToken\":\"...\"}")));

        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/auth/login")))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        userDownstream.verify(getRequestedFor(urlPathEqualTo("/v1/auth/login")));
        // per-route 증명: user 경로는 payment downstream으로 새지 않는다
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    // Task 8: CorsConfig의 @Bean CorsFilter를 FilterConfig가 FilterRegistrationBean으로 감싸도
    // Boot의 자동등록과 중복돼 Access-Control-Allow-Origin이 두 번 나가면 브라우저가 CORS를 깨진
    // 것으로 판단한다 — 정확히 1회만 응답에 실리는지 실제 요청으로 증명.
    @Test
    void corsHeaderAppliedExactlyOnce_notDoubleRegistered() throws Exception {
        userDownstream.stubFor(get(urlPathEqualTo("/v1/auth/login"))
                .willReturn(aResponse().withStatus(200).withBody("{\"accessToken\":\"...\"}")));

        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/auth/login")))
                        .header("Origin", "http://localhost:5173")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().allValues("Access-Control-Allow-Origin"))
                .containsExactly("http://localhost:5173");
    }

    // Finding 2 follow-up: CSRF는 브라우저 쿠키 인증에만 적용된다는 걸 실제로 증명 — access_token
    // 쿠키(유효 JWT)로 인증하는 상태변경 요청이 csrf_token/X-CSRF-Token 쌍 없이 오면, FilterConfig
    // 체인의 CsrfFilter가 JWT 체크보다 먼저 403으로 막고 downstream은 호출되지 않아야 한다.
    @Test
    void cookieAuthenticated_noCsrfPair_rejected403_downstreamNotCalled() throws Exception {
        String token = accessToken(42L, "USER", 7L);
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/payments/PK1/cancel")))
                        .header("Cookie", "access_token=" + token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("CSRF_TOKEN_INVALID");
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    // === GATE-03: 누락/무효/만료 토큰 → downstream 도달 전 401, downstream 무호출 ===

    @Test
    void missingToken_returns401_downstreamNotCalled() throws Exception {
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/payments/PK1/cancel")))
                        .POST(HttpRequest.BodyPublishers.noBody()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_MISSING");
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void invalidSignature_returns401_downstreamNotCalled() throws Exception {
        String forged = signedToken(WRONG_SECRET, 42L, "USER", -1_000L, 3_600_000L);
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/payments/PK1/cancel")))
                        .header("Authorization", "Bearer " + forged)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_INVALID");
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void expiredToken_returns401_downstreamNotCalled() throws Exception {
        // issuedAt/expiration 모두 과거로 설정 → jjwt parseSignedClaims가 만료 자동 거부
        String expired = signedToken(SECRET, 42L, "USER", -7_200_000L, -3_600_000L);
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/payments/PK1/cancel")))
                        .header("Authorization", "Bearer " + expired)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_EXPIRED");
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    // === D-P2-3: 유효 JWT + 클라 위조 X-User-* 동시 전송 → 게이트웨이 검증값만 downstream 도달 ===

    @Test
    void validJwt_spoofedTrustHeaders_downstreamReceivesGatewayIdentityOnly() throws Exception {
        paymentDownstream.stubFor(post(urlPathMatching("/v1/payments/.*/cancel"))
                .willReturn(aResponse().withStatus(200)));

        String token = accessToken(42L, "USER", 7L);
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/payments/PK9/cancel")))
                        .header("Authorization", "Bearer " + token)
                        .header(JwtTrustHeaderFilter.H_USER_ID, "9999")     // 위조
                        .header(JwtTrustHeaderFilter.H_USER_ROLE, "ADMIN")  // 인가 우회 시도
                        .header(JwtTrustHeaderFilter.H_MERCHANT_ID, "1")    // 위조
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        // 위조 9999/ADMIN/1 이 아닌 게이트웨이 검증값 42/USER/7 만 도달해야 함
        paymentDownstream.verify(postRequestedFor(urlPathMatching("/v1/payments/.*/cancel"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ID, equalTo("42"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ROLE, equalTo("USER"))
                .withHeader(JwtTrustHeaderFilter.H_MERCHANT_ID, equalTo("7")));
    }

    // === D-P2-5: 공개 경로 토큰없이 통과(+strip) / 인증 경로 토큰없으면 401 ===

    @Test
    void publicSignupPath_noToken_passesThrough_stripsSpoofedHeaders() throws Exception {
        userDownstream.stubFor(post(urlPathEqualTo("/v1/auth/signup"))
                .willReturn(aResponse().withStatus(200)));

        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/auth/signup")))
                        .header(JwtTrustHeaderFilter.H_USER_ROLE, "ADMIN") // 공개 경로에도 strip 되어야 함
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        userDownstream.verify(postRequestedFor(urlPathEqualTo("/v1/auth/signup"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ROLE, absent()));
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void securedLogoutPath_noToken_returns401_downstreamNotCalled() throws Exception {
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/auth/logout")))
                        .POST(HttpRequest.BodyPublishers.noBody()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_MISSING");
        userDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    // Task 9: GET /v1/auth/me도 secured 라우트(JwtTrustHeaderFilter 부착)로 편입.
    // GET은 SAFE 메서드라 CsrfFilter(Task 8)가 스킵하므로 CSRF 페어 없이도 JWT 검증까지 도달해
    // TOKEN_MISSING 401이 나와야 한다(403 아님).
    @Test
    void securedMePath_noToken_returns401_downstreamNotCalled() throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/auth/me")))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_MISSING");
        userDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    // === order-link Phase 1 GW-01: POST /v1/orders secured route, items:verify NOT exposed ===

    @Test
    void createOrder_noToken_returns401_downstreamNotCalled() throws Exception {
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/orders")))
                        .POST(HttpRequest.BodyPublishers.noBody()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_MISSING");
        orderDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void createOrder_validJwt_routesToOrderDownstream_withTrustHeaderInjected() throws Exception {
        orderDownstream.stubFor(post(urlPathEqualTo("/v1/orders"))
                .willReturn(aResponse().withStatus(201).withBody("{\"orderId\":1}")));

        String token = accessToken(42L, "USER", null);
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/orders")))
                        .header("Authorization", "Bearer " + token)
                        .header(JwtTrustHeaderFilter.H_USER_ID, "9999") // 위조 시도 → strip 되어야 함
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(201);
        orderDownstream.verify(postRequestedFor(urlPathEqualTo("/v1/orders"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ID, equalTo("42")));
        // per-route 증명: order 경로는 payment/user downstream으로 새지 않는다
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
        userDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void verifyPath_notRoutedToOrderDownstream() throws Exception {
        String token = accessToken(42L, "USER", null);
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/orders/items:verify")))
                        .header("Authorization", "Bearer " + token))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"orderItemIds\":[1]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // 어떤 라우트 predicate에도 매칭되지 않음 — 200이 아니고 order downstream은 절대 호출되지 않는다
        assertThat(res.statusCode()).isNotEqualTo(200);
        orderDownstream.verify(0, anyRequestedFor(anyUrl()));
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
        userDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    // === Task 9: product-service 라우트 — 공개 GET 브라우징(strip) + 인증 이미지 write ===

    @Test
    void publicGetProducts_routesWithoutToken_stripsSpoofedTrustHeaders() throws Exception {
        productDownstream.stubFor(get(urlPathEqualTo("/v1/categories/5/products"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));

        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/categories/5/products")))
                        .header(JwtTrustHeaderFilter.H_USER_ID, "9999") // 위조 시도 → strip 되어야 함
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        productDownstream.verify(getRequestedFor(urlPathEqualTo("/v1/categories/5/products"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ID, absent()));
        // per-route 증명: product 경로는 다른 downstream으로 새지 않는다
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
        userDownstream.verify(0, anyRequestedFor(anyUrl()));
        orderDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    // 실제 경로는 :presign 콜론 리터럴이 아니라 세그먼트 /images/presign (Task 6에서 Spring MVC가
    // ':presign'을 라우팅하지 못한다고 확인됨) — 게이트웨이 predicate/테스트 모두 이 형태를 따른다.
    @Test
    void imagePresign_noToken_returns401_downstreamNotCalled() throws Exception {
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/products/1/images/presign")))
                        .POST(HttpRequest.BodyPublishers.noBody()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_MISSING");
        productDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void imagePresign_validJwt_routesToProductDownstream_withTrustHeaderInjected() throws Exception {
        productDownstream.stubFor(post(urlPathEqualTo("/v1/products/1/images/presign"))
                .willReturn(aResponse().withStatus(200).withBody("{\"key\":\"k\",\"uploadUrl\":\"u\"}")));

        String token = accessToken(42L, "ADMIN", null);
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/products/1/images/presign")))
                        .header("Authorization", "Bearer " + token)
                        // 다른 인증 write 라우트 테스트와 동일하게 noBody() — 실제 body 스트리밍은
                        // WireMock(Jetty)+JDK HttpClient chunked forwarding 조합에서 별개 이슈(EOF)를
                        // 유발해 라우팅/인증 검증이라는 이 테스트의 목적과 무관한 노이즈가 된다.
                        .POST(HttpRequest.BodyPublishers.noBody()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        productDownstream.verify(postRequestedFor(urlPathEqualTo("/v1/products/1/images/presign"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ROLE, equalTo("ADMIN")));
    }

    // === ADMIN role feature: PATCH /v1/admin/** — user-service 인증 라우트(/v1/auth/me와 동일 방식) ===

    @Test
    void adminRoute_noToken_returns401_downstreamNotCalled() throws Exception {
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/admin/users/1/role")))
                        .method("PATCH", HttpRequest.BodyPublishers.noBody()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_MISSING");
        userDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void adminRoute_validJwt_routesToUserDownstream_withTrustHeaderInjected() throws Exception {
        userDownstream.stubFor(patch(urlPathEqualTo("/v1/admin/users/1/role"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":1,\"role\":\"ADMIN\"}")));

        String token = accessToken(42L, "ADMIN", null);
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/admin/users/1/role")))
                        .header("Authorization", "Bearer " + token)
                        .method("PATCH", HttpRequest.BodyPublishers.noBody()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        userDownstream.verify(patchRequestedFor(urlPathEqualTo("/v1/admin/users/1/role"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ROLE, equalTo("ADMIN")));
        // per-route 증명: admin 경로는 다른 downstream으로 새지 않는다
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
        orderDownstream.verify(0, anyRequestedFor(anyUrl()));
        productDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void postProductsSeed_noToken_returns401_downstreamNotCalled() throws Exception {
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/products")))
                        .POST(HttpRequest.BodyPublishers.noBody()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_MISSING");
        productDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void postProductsSeed_validJwt_routesToProductDownstream_withTrustHeaderInjected() throws Exception {
        productDownstream.stubFor(post(urlPathEqualTo("/v1/products"))
                .willReturn(aResponse().withStatus(200).withBody("{\"productId\":1,\"skus\":[]}")));

        String token = accessToken(42L, "ADMIN", null);
        HttpResponse<String> res = http.send(
                withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/products")))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody()))   // noBody: 다른 write 라우트 테스트와 동일(스트리밍 노이즈 회피)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        productDownstream.verify(postRequestedFor(urlPathEqualTo("/v1/products"))
                .withHeader(JwtTrustHeaderFilter.H_USER_ROLE, equalTo("ADMIN")));
        // per-route: 다른 downstream으로 새지 않음
        paymentDownstream.verify(0, anyRequestedFor(anyUrl()));
        orderDownstream.verify(0, anyRequestedFor(anyUrl()));
        userDownstream.verify(0, anyRequestedFor(anyUrl()));
    }

    private String gateway(String path) {
        return "http://localhost:" + gatewayPort + path;
    }

    private static String accessToken(long userId, String role, Long merchantId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + 3_600_000));
        if (merchantId != null) {
            builder.claim("merchantId", merchantId);
        }
        return builder.signWith(key).compact();
    }

    /** iat/exp 를 now 기준 오프셋(ms)으로 설정해 만료·무효 서명 토큰을 생성. */
    private static String signedToken(String secret, long userId, String role,
                                      long iatOffsetMs, long expOffsetMs) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date(now + iatOffsetMs))
                .expiration(new Date(now + expOffsetMs))
                .signWith(key)
                .compact();
    }
}
