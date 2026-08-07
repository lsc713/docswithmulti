package com.example.settlement.integration;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.application.interfaces.BankTransferPort.TransferAck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * HOLD-04: 유보 상태 조회. 유보 정책 있는 가맹점 approve → GET /{id}/reserve 200(HELD·amount·RSV- ref),
 * 유보 없는 정산 → 404 RESERVE_NOT_FOUND {code,message}.
 *
 * <p>harness = ReserveHoldIntegrationTest 복제(MySQL only + RANDOM_PORT + kafka listener off,
 * java.net.http.HttpClient + @LocalServerPort, @MockitoBean BankTransferPort).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Reserve query (HOLD-04): GET /{id}/reserve 200(HELD) / 404")
class ReserveQueryIntegrationTest {

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

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 27);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 2);

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @MockitoBean BankTransferPort bankTransferPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM reserve");
        jdbc.update("DELETE FROM merchant_reserve_config");
        jdbc.update("DELETE FROM payout");
        jdbc.update("DELETE FROM merchant_payout_account");
        jdbc.update("DELETE FROM settlement");
        when(bankTransferPort.submit(any(), any(), any())).thenReturn(new TransferAck(true));
    }

    @Test
    @DisplayName("정책 O approve 후 GET /{id}/reserve → 200 + HELD·amount·transfer_ref RSV-{id}·hold_until")
    void getReserveAfterApprove_200Held() throws Exception {
        long merchantId = 811L;
        BigDecimal net = new BigDecimal("10000.00");
        seedReserveConfig(merchantId, "0.1000", "1000000.00", 7);
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seed(merchantId, net, "FINALIZED");
        assertThat(approve(settlementId).statusCode()).isEqualTo(200);

        HttpResponse<String> res = getReserve(settlementId);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .contains("\"status\":\"HELD\"")
                .contains("1000.00")
                .contains("\"transferRef\":\"RSV-" + settlementId + "\"")
                .contains(LocalDate.now(KST).plusDays(7).toString());
    }

    @Test
    @DisplayName("유보 없는 정산 GET /{id}/reserve → 404 RESERVE_NOT_FOUND {code,message}")
    void getReserveMissing_404() throws Exception {
        HttpResponse<String> res = getReserve(999999L);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("RESERVE_NOT_FOUND");
    }

    // --- helpers ---

    private void seedReserveConfig(long merchantId, String rate, String cap, int holdDays) {
        jdbc.update("""
            INSERT INTO merchant_reserve_config
                (merchant_id, reserve_rate, reserve_cap, hold_days, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """, merchantId, new BigDecimal(rate), new BigDecimal(cap), holdDays);
    }

    private HttpResponse<String> putAccount(long merchantId) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/settlements/payout-account/" + merchantId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"bankCode\":\"004\",\"accountNumber\":\"123-456\",\"holderName\":\"홍길동\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> approve(long settlementId) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/settlements/" + settlementId + "/payout"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getReserve(long settlementId) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/settlements/" + settlementId + "/reserve"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private long seed(long merchantId, BigDecimal net, String status) {
        jdbc.update("""
            INSERT INTO settlement
                (merchant_id, period_start, period_end, gross_amount, cancel_amount,
                 fee_amount, vat_amount, net_amount, status, finalized_at, created_at, updated_at)
            VALUES (?, ?, ?, 0, 0, 0, 0, ?, ?,
                    CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """, merchantId, java.sql.Date.valueOf(PERIOD_START), java.sql.Date.valueOf(PERIOD_END), net, status);
        return jdbc.queryForObject(
                "SELECT id FROM settlement WHERE merchant_id = ? AND period_start = ?",
                Long.class, merchantId, java.sql.Date.valueOf(PERIOD_START));
    }
}
