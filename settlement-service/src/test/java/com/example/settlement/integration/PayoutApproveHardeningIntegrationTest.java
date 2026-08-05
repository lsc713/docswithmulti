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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PAY-02 hardening: 중복/경합 승인 → race-safe 409-return-existing (이중지급 차단).
 *
 * <p>harness = PayoutTracerIntegrationTest 복제(MySQL only + RANDOM_PORT + kafka listener off,
 * java.net.http.HttpClient + @LocalServerPort, @MockitoBean BankTransferPort). 커버:
 * 순차 중복(409+기존 바디) · 2-thread 경합(정확히 1행 + {200,409} + submit times(1)) · 3개 400 가드 · 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Payout PAY-02: 중복/경합 승인 → 409-return-existing (이중지급 차단)")
class PayoutApproveHardeningIntegrationTest {

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
    @DisplayName("순차 중복 승인 → 1차 200 PROCESSING, 2차 409 + 기존 payout 바디(id/status/amount)")
    void sequentialDuplicate_second409WithExistingBody() throws Exception {
        long merchantId = 701L;
        BigDecimal net = new BigDecimal("24500.00");
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seed(merchantId, net, "FINALIZED");

        HttpResponse<String> first = approve(settlementId);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("PROCESSING");

        Long payoutId = jdbc.queryForObject(
                "SELECT id FROM payout WHERE settlement_id = ?", Long.class, settlementId);

        HttpResponse<String> second = approve(settlementId);
        assertThat(second.statusCode()).isEqualTo(409);
        // 기존 payout 바디(새 행/{code,message} 아님)
        assertThat(second.body()).contains("\"id\":" + payoutId);
        assertThat(second.body()).contains("PROCESSING");
        assertThat(second.body()).contains("24500.00");
        assertThat(second.body()).doesNotContain("PAYOUT_ALREADY_EXISTS");   // {code,message} 아님

        assertThat(countPayout(settlementId)).isEqualTo(1);
        verify(bankTransferPort, times(1)).submit(eq("PO-" + settlementId), any(), any());
    }

    @Test
    @DisplayName("2-thread 경합 승인 → payout 정확히 1행 + {200,409} + submit times(1) (이중지급 차단)")
    void concurrentRace_exactlyOnePayoutAndOneSubmit() throws Exception {
        long merchantId = 702L;
        BigDecimal net = new BigDecimal("31000.00");
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seed(merchantId, net, "FINALIZED");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            CompletableFuture<Integer> a = fireOnLatch(pool, start, settlementId);
            CompletableFuture<Integer> b = fireOnLatch(pool, start, settlementId);
            start.countDown();   // 동시 출발
            CompletableFuture.allOf(a, b).join();

            List<Integer> codes = List.of(a.join(), b.join());
            assertThat(codes).containsExactlyInAnyOrder(200, 409);   // 500 없음
        } finally {
            pool.shutdownNow();
        }

        assertThat(countPayout(settlementId)).isEqualTo(1);   // 정확히 1행 — 이중지급 차단
        verify(bankTransferPort, times(1)).submit(eq("PO-" + settlementId), any(), any());   // 은행 제출 1회
    }

    @Test
    @DisplayName("가드1: OPEN 정산 승인 → 400 PAYOUT_NOT_PAYABLE")
    void guardNotFinalized_400() throws Exception {
        long merchantId = 703L;
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seed(merchantId, new BigDecimal("10000.00"), "OPEN");

        HttpResponse<String> res = approve(settlementId);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("PAYOUT_NOT_PAYABLE");
        assertThat(countPayout(settlementId)).isEqualTo(0);
    }

    @Test
    @DisplayName("가드2: net_amount 0 정산 승인 → 400 PAYOUT_NOT_PAYABLE")
    void guardNonPositiveNet_400() throws Exception {
        long merchantId = 704L;
        assertThat(putAccount(merchantId).statusCode()).isEqualTo(200);
        long settlementId = seed(merchantId, BigDecimal.ZERO, "FINALIZED");

        HttpResponse<String> res = approve(settlementId);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("PAYOUT_NOT_PAYABLE");
        assertThat(countPayout(settlementId)).isEqualTo(0);
    }

    @Test
    @DisplayName("가드3: 활성 계좌 없음 → 400 PAYOUT_ACCOUNT_INACTIVE")
    void guardNoActiveAccount_400() throws Exception {
        long merchantId = 705L;
        long settlementId = seed(merchantId, new BigDecimal("10000.00"), "FINALIZED");   // putAccount 생략

        HttpResponse<String> res = approve(settlementId);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("PAYOUT_ACCOUNT_INACTIVE");
        assertThat(countPayout(settlementId)).isEqualTo(0);
    }

    @Test
    @DisplayName("존재하지 않는 정산 승인 → 404 SETTLEMENT_NOT_FOUND")
    void missingSettlement_404() throws Exception {
        HttpResponse<String> res = approve(999_999L);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("SETTLEMENT_NOT_FOUND");
    }

    // --- helpers ---

    private CompletableFuture<Integer> fireOnLatch(ExecutorService pool, CountDownLatch start, long settlementId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start.await();
                return approve(settlementId).statusCode();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, pool);
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

    private int countPayout(long settlementId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payout WHERE settlement_id = ?", Integer.class, settlementId);
        return c == null ? 0 : c;
    }
}
