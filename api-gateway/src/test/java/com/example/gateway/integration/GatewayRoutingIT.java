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
}
