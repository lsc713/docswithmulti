package com.example.settlement.integration;

import com.example.settlement.application.interfaces.MerchantReserveConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유보 정책 설정/조회 통합테스트 (RCFG-01/02): PUT upsert(+overwrite, DB 왕복), 미설정 GET→404,
 * 값 검증 400(rate<0 / rate≥1 / scale>4 / cap<0 / holdDays<0). {code,message} 바디 검증.
 *
 * <p>harness = MerchantSettlementConfigIntegrationTest 복제(MySQL only + RANDOM_PORT + kafka listener off,
 * java.net.http.HttpClient + @LocalServerPort).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Reserve config (PUT upsert + GET 200/404 + 400 검증)")
class ReserveConfigIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("settlement_db")
            .withUsername("settlement")
            .withPassword("settlement");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MerchantReserveConfigRepository repo;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM merchant_reserve_config");
    }

    @Test
    @DisplayName("PUT 정책 → 200 + 값 반환, GET round-trip(DB 왕복)")
    void putThenGetRoundTrip() throws Exception {
        HttpResponse<String> put = put(100, "{\"reserveRate\":0.1000,\"reserveCap\":1000000.00,\"holdDays\":7}");
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(put.body()).contains("0.1000").contains("1000000.00").contains("\"holdDays\":7");

        // DB 왕복
        assertThat(repo.findConfig(100)).hasValueSatisfying(c -> {
            assertThat(c.getReserveRate()).isEqualByComparingTo("0.1000");
            assertThat(c.getReserveCap()).isEqualByComparingTo("1000000.00");
            assertThat(c.getHoldDays()).isEqualTo(7);
        });

        HttpResponse<String> get = get(100);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).contains("0.1000").contains("1000000.00").contains("\"holdDays\":7");
    }

    @Test
    @DisplayName("두 번째 PUT은 정책을 덮어씀(멱등 upsert)")
    void putOverwrites() throws Exception {
        assertThat(put(100, "{\"reserveRate\":0.1000,\"reserveCap\":1000000.00,\"holdDays\":7}").statusCode())
                .isEqualTo(200);
        assertThat(put(100, "{\"reserveRate\":0.2000,\"reserveCap\":500000.00,\"holdDays\":14}").statusCode())
                .isEqualTo(200);
        assertThat(repo.findConfig(100)).hasValueSatisfying(c -> {
            assertThat(c.getReserveRate()).isEqualByComparingTo("0.2000");
            assertThat(c.getReserveCap()).isEqualByComparingTo("500000.00");
            assertThat(c.getHoldDays()).isEqualTo(14);
        });
    }

    @Test
    @DisplayName("미설정 가맹점 GET → 404 RESERVE_CONFIG_NOT_FOUND {code,message}")
    void unsetMerchantGet404() throws Exception {
        HttpResponse<String> get = get(999);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).contains("RESERVE_CONFIG_NOT_FOUND");
    }

    @Test
    @DisplayName("검증 위반(rate<0 / rate≥1 / scale>4 / cap<0 / holdDays<0) → 400 {code}, 아무 것도 쓰지 않음")
    void invalidConfigRejected() throws Exception {
        assertThat(reject("{\"reserveRate\":-0.01,\"reserveCap\":100.00,\"holdDays\":7}")).isEqualTo(400);
        assertThat(reject("{\"reserveRate\":1.0,\"reserveCap\":100.00,\"holdDays\":7}")).isEqualTo(400);
        assertThat(reject("{\"reserveRate\":0.03301,\"reserveCap\":100.00,\"holdDays\":7}")).isEqualTo(400);
        assertThat(reject("{\"reserveRate\":0.1000,\"reserveCap\":-1.00,\"holdDays\":7}")).isEqualTo(400);
        assertThat(reject("{\"reserveRate\":0.1000,\"reserveCap\":100.00,\"holdDays\":-1}")).isEqualTo(400);

        // {code} 바디 확인(대표 1건)
        HttpResponse<String> resp = put(100, "{\"reserveRate\":1.0,\"reserveCap\":100.00,\"holdDays\":7}");
        assertThat(resp.body()).contains("INVALID_RESERVE_CONFIG");
        assertThat(repo.findConfig(100)).isEmpty(); // 거부된 요청은 아무 것도 쓰지 않음
    }

    private int reject(String body) throws Exception {
        return put(100, body).statusCode();
    }

    private HttpResponse<String> put(long merchantId, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/settlements/reserve-config/" + merchantId))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(long merchantId) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/settlements/reserve-config/" + merchantId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
