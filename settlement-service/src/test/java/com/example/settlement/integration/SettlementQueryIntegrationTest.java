package com.example.settlement.integration;

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
 * 정산 조회 API 통합테스트 (QUERY-01): list(+status 필터, 잘못된 status→400), detail(header+lines, 없는 id→404).
 * Kafka 리스너는 auto-startup=false 로 끄고 조회 표면만 검증(MySQL 시드 직접 삽입).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Settlement query API (list + status 필터 + detail + 400/404)")
class SettlementQueryIntegrationTest {

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
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private long openId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM settlement_line");
        jdbc.update("DELETE FROM settlement");
        // merchant 555: OPEN 주 1건(라인 포함) + FINALIZED 주 1건
        jdbc.update("""
                INSERT INTO settlement (merchant_id, period_start, period_end, cancel_amount, status, created_at, updated_at)
                VALUES (555, '2026-08-03', '2026-08-09', 25000, 'OPEN', NOW(3), NOW(3))""");
        openId = jdbc.queryForObject(
                "SELECT id FROM settlement WHERE merchant_id = 555 AND status = 'OPEN'", Long.class);
        jdbc.update("""
                INSERT INTO settlement_line (settlement_id, type, payment_key, amount, event_id, occurred_at, created_at)
                VALUES (?, 'CANCEL', 'PAY-Q-1', 25000, 'cancel:5001', NOW(3), NOW(3))""", openId);
        jdbc.update("""
                INSERT INTO settlement (merchant_id, period_start, period_end, status, created_at, updated_at)
                VALUES (555, '2026-07-27', '2026-08-02', 'FINALIZED', NOW(3), NOW(3))""");
    }

    @Test
    @DisplayName("GET /v1/settlements?merchantId=555 → 헤더 2건")
    void listAll() throws Exception {
        HttpResponse<String> resp = get("/v1/settlements?merchantId=555");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(countOccurrences(resp.body(), "\"merchantId\":555")).isEqualTo(2);
    }

    @Test
    @DisplayName("status=OPEN / FINALIZED 필터 → 각 1건")
    void listWithStatusFilter() throws Exception {
        assertThat(get("/v1/settlements?merchantId=555&status=OPEN").body())
                .contains("\"status\":\"OPEN\"").doesNotContain("\"status\":\"FINALIZED\"");
        assertThat(get("/v1/settlements?merchantId=555&status=FINALIZED").body())
                .contains("\"status\":\"FINALIZED\"").doesNotContain("\"status\":\"OPEN\"");
    }

    @Test
    @DisplayName("알 수 없는 status → 400")
    void listWithInvalidStatusRejected() throws Exception {
        HttpResponse<String> resp = get("/v1/settlements?merchantId=555&status=BOGUS");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("INVALID_STATUS");
    }

    @Test
    @DisplayName("GET /v1/settlements/{id} → 헤더 + 라인")
    void detailReturnsHeaderAndLines() throws Exception {
        HttpResponse<String> resp = get("/v1/settlements/" + openId);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"status\":\"OPEN\"");
        assertThat(resp.body()).contains("\"eventId\":\"cancel:5001\"");
        assertThat(resp.body()).contains("PAY-Q-1");
    }

    @Test
    @DisplayName("없는 id → 404")
    void detailMissingIdReturns404() throws Exception {
        HttpResponse<String> resp = get("/v1/settlements/99999999");
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(resp.body()).contains("SETTLEMENT_NOT_FOUND");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }
}
