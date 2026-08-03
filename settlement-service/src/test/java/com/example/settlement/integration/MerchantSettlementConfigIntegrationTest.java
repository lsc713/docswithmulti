package com.example.settlement.integration;

import com.example.settlement.application.interfaces.MerchantSettlementConfigRepository;
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
 * 요율 설정/조회 통합테스트 (FEE-01): PUT upsert(+overwrite), findRate present/empty/inactive, feeRate 검증 400.
 * 요율은 쓰기 경로(PUT/repo.upsert)로만 시드 — Flyway prod-data 시드 없음. 엔티티는 V1 DDL 대상 boot validate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Merchant settlement config (PUT upsert + findRate Optional + 400 검증)")
class MerchantSettlementConfigIntegrationTest {

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
    @Autowired MerchantSettlementConfigRepository repo;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM merchant_settlement_config");
    }

    @Test
    @DisplayName("PUT 요율 → 200, findRate가 설정값 반환(DB 왕복)")
    void putThenFindRoundTrip() throws Exception {
        HttpResponse<String> resp = put(100, "{\"feeRate\":0.0330}");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("0.0330");
        assertThat(repo.findRate(100)).hasValueSatisfying(r ->
                assertThat(r).isEqualByComparingTo("0.0330"));
    }

    @Test
    @DisplayName("두 번째 PUT은 요율을 덮어씀(멱등 set)")
    void putOverwrites() throws Exception {
        assertThat(put(100, "{\"feeRate\":0.0330}").statusCode()).isEqualTo(200);
        assertThat(put(100, "{\"feeRate\":0.0500}").statusCode()).isEqualTo(200);
        assertThat(repo.findRate(100)).hasValueSatisfying(r ->
                assertThat(r).isEqualByComparingTo("0.0500"));
    }

    @Test
    @DisplayName("미설정 가맹점 → findRate empty (계산기 미호출 = finalize 유예)")
    void unsetMerchantEmpty() {
        assertThat(repo.findRate(999)).isEmpty();
    }

    @Test
    @DisplayName("inactive(active=false) 설정 → findRate empty")
    void inactiveConfigEmpty() {
        jdbc.update("""
                INSERT INTO merchant_settlement_config (merchant_id, fee_rate, active, created_at, updated_at)
                VALUES (777, 0.0500, FALSE, NOW(3), NOW(3))""");
        assertThat(repo.findRate(777)).isEmpty();
    }

    @Test
    @DisplayName("feeRate < 0 / >= 1 / scale > 4 → 400")
    void invalidFeeRateRejected() throws Exception {
        assertThat(put(100, "{\"feeRate\":-0.01}").statusCode()).isEqualTo(400);
        assertThat(put(100, "{\"feeRate\":1.0}").statusCode()).isEqualTo(400);
        assertThat(put(100, "{\"feeRate\":0.03301}").statusCode()).isEqualTo(400);
        assertThat(repo.findRate(100)).isEmpty(); // 거부된 요청은 아무 것도 쓰지 않음
    }

    private HttpResponse<String> put(long merchantId, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/settlements/config/" + merchantId))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
