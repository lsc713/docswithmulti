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

    static {
        // @DynamicPropertySource 서플라이어가 포트를 읽기 전에 기동돼 있어야 함
        paymentDownstream.start();
        userDownstream.start();
    }

    @AfterAll
    static void stopDownstreams() {
        paymentDownstream.stop();
        userDownstream.stop();
    }

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        registry.add("gateway.downstream.payment-uri", () -> "http://localhost:" + paymentDownstream.port());
        registry.add("gateway.downstream.user-uri", () -> "http://localhost:" + userDownstream.port());
    }

    @LocalServerPort
    int gatewayPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetStubs() {
        paymentDownstream.resetAll();
        userDownstream.resetAll();
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

    // === GATE-03: 누락/무효/만료 토큰 → downstream 도달 전 401, downstream 무호출 ===

    @Test
    void missingToken_returns401_downstreamNotCalled() throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(gateway("/v1/payments/PK1/cancel")))
                        .POST(HttpRequest.BodyPublishers.noBody())
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
                HttpRequest.newBuilder(URI.create(gateway("/v1/auth/logout")))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("TOKEN_MISSING");
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
