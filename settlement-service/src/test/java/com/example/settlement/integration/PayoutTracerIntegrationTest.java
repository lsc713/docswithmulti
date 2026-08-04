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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Payout tracer e2e (ACCT-01/PAY-01/CONFIRM-01/CONFIRM-03): 계좌 PUT → FINALIZED 정산 승인 → PROCESSING + submit →
 * 서명 webhook → PAID. 잘못된 서명 → 401 + 상태 무변경.
 *
 * <p>harness = MerchantSettlementConfigIntegrationTest 복제(MySQL only + RANDOM_PORT + kafka listener off,
 * java.net.http.HttpClient + @LocalServerPort). 은행은 @MockitoBean BankTransferPort 로 대체(reconcile IT 관례).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Payout tracer: account → approve(PROCESSING+submit) → webhook PAID / 401 무변경")
class PayoutTracerIntegrationTest {

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

    private static final String SECRET = "local-dev-secret";   // application.yml payout.webhook.secret default
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 27);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 2);

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @MockitoBean BankTransferPort bankTransferPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payout");
        jdbc.update("DELETE FROM merchant_payout_account");
        jdbc.update("DELETE FROM settlement");
        when(bankTransferPort.submit(any(), any(), any())).thenReturn(new TransferAck(true));
    }

    @Test
    @DisplayName("계좌 PUT → 승인 PROCESSING+submit → 서명 webhook → PAID(paid_at 세팅)")
    void happyPath_accountApproveSubmitWebhookPaid() throws Exception {
        long merchantId = 601L;
        BigDecimal net = new BigDecimal("24500.00");

        // 1) 계좌 upsert
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);

        // 2) FINALIZED 정산 헤더(net>0) 시드
        long settlementId = seedFinalized(merchantId, net);

        // 3) 승인 → 200 + PROCESSING
        HttpResponse<String> approve = post("/v1/settlements/" + settlementId + "/payout", null, null);
        assertThat(approve.statusCode()).isEqualTo(200);
        assertThat(approve.body()).contains("PROCESSING");

        String ref = "PO-" + settlementId;
        assertThat(statusOf(ref)).isEqualTo("PROCESSING");
        assertThat(transferRefOf(settlementId)).isEqualTo(ref);
        assertThat(amountOf(ref)).isEqualByComparingTo(net);
        verify(bankTransferPort).submit(eq(ref), any(), eq(net));   // save 후 제출

        // 4) 서명 webhook PAID → PROCESSING→PAID
        HttpResponse<String> cb = post("/v1/payouts/callback",
                "{\"transferRef\":\"" + ref + "\",\"result\":\"PAID\"}", SECRET);
        assertThat(cb.statusCode()).isEqualTo(200);
        assertThat(statusOf(ref)).isEqualTo("PAID");
        assertThat(paidAtNull(ref)).isFalse();
    }

    @Test
    @DisplayName("잘못된 서명 webhook → 401, 상태 PROCESSING 유지")
    void mismatchedSignature_401_unchanged() throws Exception {
        long merchantId = 602L;
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seedFinalized(merchantId, new BigDecimal("10000.00"));
        assertThat(post("/v1/settlements/" + settlementId + "/payout", null, null).statusCode()).isEqualTo(200);

        String ref = "PO-" + settlementId;
        HttpResponse<String> cb = post("/v1/payouts/callback",
                "{\"transferRef\":\"" + ref + "\",\"result\":\"PAID\"}", "wrong-secret");
        assertThat(cb.statusCode()).isEqualTo(401);
        assertThat(statusOf(ref)).isEqualTo("PROCESSING");   // 상태 무변경
        assertThat(paidAtNull(ref)).isTrue();
    }

    // --- helpers ---

    private HttpResponse<String> putAccount(long merchantId) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/settlements/payout-account/" + merchantId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"bankCode\":\"004\",\"accountNumber\":\"123-456\",\"holderName\":\"홍길동\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String signature) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json");
        if (signature != null) b.header("X-Bank-Signature", signature);
        b.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private long seedFinalized(long merchantId, BigDecimal net) {
        jdbc.update("""
            INSERT INTO settlement
                (merchant_id, period_start, period_end, gross_amount, cancel_amount,
                 fee_amount, vat_amount, net_amount, status, finalized_at, created_at, updated_at)
            VALUES (?, ?, ?, 0, 0, 0, 0, ?, 'FINALIZED',
                    CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """, merchantId, java.sql.Date.valueOf(PERIOD_START), java.sql.Date.valueOf(PERIOD_END), net);
        return jdbc.queryForObject(
                "SELECT id FROM settlement WHERE merchant_id = ? AND period_start = ?",
                Long.class, merchantId, java.sql.Date.valueOf(PERIOD_START));
    }

    private String statusOf(String transferRef) {
        return jdbc.queryForObject("SELECT status FROM payout WHERE transfer_ref = ?", String.class, transferRef);
    }

    private String transferRefOf(long settlementId) {
        return jdbc.queryForObject(
                "SELECT transfer_ref FROM payout WHERE settlement_id = ?", String.class, settlementId);
    }

    private BigDecimal amountOf(String transferRef) {
        return jdbc.queryForObject(
                "SELECT amount FROM payout WHERE transfer_ref = ?", BigDecimal.class, transferRef);
    }

    private boolean paidAtNull(String transferRef) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payout WHERE transfer_ref = ? AND paid_at IS NULL",
                Integer.class, transferRef);
        return c != null && c > 0;
    }
}
