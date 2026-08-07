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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HOLD-01/02/03: 승인 시 유보 홀드. 유보 정책 있는 가맹점은 payout=net−reserve + reserve HELD 행,
 * 미설정 가맹점은 payout=net + reserve 행 미생성(하위호환), cap 소진 시 reserve=0.
 *
 * <p>harness = PayoutApproveHardeningIntegrationTest 복제(MySQL only + RANDOM_PORT + kafka listener off,
 * java.net.http.HttpClient + @LocalServerPort, @MockitoBean BankTransferPort).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Reserve HOLD: payout=net−reserve + HELD 행 / 미설정·cap소진 → net·행 미생성")
class ReserveHoldIntegrationTest {

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
    @DisplayName("유보 정책 O: net=10000, rate=0.1 → payout=9000 + reserve HELD 행(1000, RSV- ref, hold_until=today(KST)+7)")
    void reserveConfigured_payoutNetMinusReserve_andHeldRow() throws Exception {
        long merchantId = 801L;
        BigDecimal net = new BigDecimal("10000.00");
        seedReserveConfig(merchantId, "0.1000", "1000000.00", 7);
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seed(merchantId, net, "FINALIZED");

        HttpResponse<String> res = approve(settlementId);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("9000.00");   // payout = net − reserve

        BigDecimal payoutAmount = jdbc.queryForObject(
                "SELECT amount FROM payout WHERE settlement_id = ?", BigDecimal.class, settlementId);
        assertThat(payoutAmount).isEqualByComparingTo("9000.00");

        // reserve HELD 행 검증
        assertThat(countReserve(settlementId)).isEqualTo(1);
        BigDecimal rsvAmount = jdbc.queryForObject(
                "SELECT amount FROM reserve WHERE settlement_id = ?", BigDecimal.class, settlementId);
        assertThat(rsvAmount).isEqualByComparingTo("1000.00");
        String status = jdbc.queryForObject(
                "SELECT status FROM reserve WHERE settlement_id = ?", String.class, settlementId);
        assertThat(status).isEqualTo("HELD");
        String ref = jdbc.queryForObject(
                "SELECT transfer_ref FROM reserve WHERE settlement_id = ?", String.class, settlementId);
        assertThat(ref).isEqualTo("RSV-" + settlementId);
        LocalDate holdUntil = jdbc.queryForObject(
                "SELECT hold_until FROM reserve WHERE settlement_id = ?", java.sql.Date.class, settlementId)
                .toLocalDate();
        assertThat(holdUntil).isEqualTo(LocalDate.now(KST).plusDays(7));

        // submit 은 payoutAmount(net−reserve) 로 1회 — 유보는 미제출(HELD only, Phase 1)
        verify(bankTransferPort, times(1))
                .submit(eq("PO-" + settlementId), any(), eq(new BigDecimal("9000.00")));
    }

    @Test
    @DisplayName("유보 정책 X(미설정): payout=net, reserve 행 0건 (하위호환)")
    void noReserveConfig_payoutEqualsNet_noReserveRow() throws Exception {
        long merchantId = 802L;
        BigDecimal net = new BigDecimal("10000.00");
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seed(merchantId, net, "FINALIZED");

        HttpResponse<String> res = approve(settlementId);
        assertThat(res.statusCode()).isEqualTo(200);

        BigDecimal payoutAmount = jdbc.queryForObject(
                "SELECT amount FROM payout WHERE settlement_id = ?", BigDecimal.class, settlementId);
        assertThat(payoutAmount).isEqualByComparingTo(net);
        assertThat(countReserve(settlementId)).isEqualTo(0);
        verify(bankTransferPort, times(1))
                .submit(eq("PO-" + settlementId), any(), eq(new BigDecimal("10000.00")));
    }

    @Test
    @DisplayName("cap 소진: 기존 HELD held≥cap → reserve=0 → payout=net, 신규 reserve 행 미생성")
    void capExhausted_reserveZero_payoutEqualsNet() throws Exception {
        long merchantId = 803L;
        BigDecimal net = new BigDecimal("10000.00");
        seedReserveConfig(merchantId, "0.1000", "1000.00", 7);   // cap 1000
        // 기존 HELD reserve(가상의 이전 정산 9990001) 로 current_held=1000 ≥ cap 1000 → room 0
        seedHeldReserve(9990001L, merchantId, "1000.00");
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seed(merchantId, net, "FINALIZED");

        HttpResponse<String> res = approve(settlementId);
        assertThat(res.statusCode()).isEqualTo(200);

        BigDecimal payoutAmount = jdbc.queryForObject(
                "SELECT amount FROM payout WHERE settlement_id = ?", BigDecimal.class, settlementId);
        assertThat(payoutAmount).isEqualByComparingTo(net);
        assertThat(countReserve(settlementId)).isEqualTo(0);   // 신규 정산엔 reserve 행 없음
        verify(bankTransferPort, times(1))
                .submit(eq("PO-" + settlementId), any(), eq(new BigDecimal("10000.00")));
    }

    // --- helpers ---

    private void seedHeldReserve(long settlementId, long merchantId, String amount) {
        jdbc.update("""
            INSERT INTO reserve
                (settlement_id, merchant_id, amount, status, hold_until, transfer_ref,
                 attempt_count, last_error, held_at, released_at, created_at, updated_at)
            VALUES (?, ?, ?, 'HELD', ?, ?, 1, NULL,
                    CURRENT_TIMESTAMP(3), NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """, settlementId, merchantId, new BigDecimal(amount),
            java.sql.Date.valueOf(LocalDate.now(KST).plusDays(7)), "RSV-" + settlementId);
    }

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

    private int countReserve(long settlementId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reserve WHERE settlement_id = ?", Integer.class, settlementId);
        return c == null ? 0 : c;
    }
}
