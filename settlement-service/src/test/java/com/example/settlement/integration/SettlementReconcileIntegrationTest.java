package com.example.settlement.integration;

import com.example.settlement.application.interfaces.PaymentSettlementPort;
import com.example.settlement.application.interfaces.PaymentSettlementPort.CancelView;
import com.example.settlement.application.interfaces.PaymentSettlementPort.PaymentView;
import com.example.settlement.application.service.SettlementConfigService;
import com.example.settlement.application.service.SettlementReconcileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * reconcile → finalize tracer e2e (RECON-01/02): 주마감된 OPEN 헤더 → payment 조회 stub 이
 * event-lost SALE 1 + CANCEL 1 을 돌려줌 → reconcileService.runOnce 가 기존 record() 경로로 back-fill →
 * Σlines 권위로 fee/vat/net 계산 → OPEN→FINALIZED.
 *
 * <p>harness = SaleLedgerIntegrationTest 재사용: 실 MySQL + 실 Kafka(Testcontainers, SALE/CANCEL 컨슈머 기동용)
 * + mock RedissonClient(TestRedissonConfig, 실 Redis 불요) + stub PaymentSettlementPort(@MockitoBean).
 * Redisson starter 가 클래스패스에 있으나 실 Redis 없이 컨텍스트 기동 — Pitfall 1 해소 증명.
 *
 * <p>KST 주: 2026-07-27(월)~2026-08-02(일). stub 이벤트 시각은 모두 이 주에 귀속.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Settlement reconcile→finalize tracer")
class SettlementReconcileIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("settlement_db")
            .withUsername("settlement")
            .withPassword("settlement");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementReconcileService reconcileService;
    @Autowired SettlementConfigService configService;

    @MockitoBean PaymentSettlementPort paymentSettlementPort;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 27);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 2);
    private static final LocalDate CUTOFF = LocalDate.of(2026, 8, 4); // period_end < cutoff → eligible

    @Test
    @DisplayName("RECON-01/02: OPEN 헤더 back-fill(SALE+CANCEL) → Σlines fee/vat/net → OPEN→FINALIZED, 재실행 무변화")
    void reconcileBackfillsAndFinalizes() {
        long merchantId = 501L;
        long settlementId = seedOpenHeader(merchantId);
        configService.setRate(merchantId, new BigDecimal("0.10"));

        // event-lost: 조회에는 있으나 원장 라인은 없음(컨슈머가 놓친 상태).
        PaymentView pv = new PaymentView(
                "PAY-RECON-1", merchantId, new BigDecimal("50000"), "COMPLETED",
                Instant.parse("2026-07-31T00:00:00.123Z"),          // KST 07-31 → 주 07-27
                List.of(new CancelView("1001", new BigDecimal("20000"),
                        Instant.parse("2026-07-31T01:00:00Z"))));    // KST 07-31 → 주 07-27
        when(paymentSettlementPort.fetch(eq(merchantId), any(), any())).thenReturn(List.of(pv));

        reconcileService.runOnce(CUTOFF);

        // back-fill: 두 라인 적재.
        assertThat(lineCount("sale:PAY-RECON-1")).isEqualTo(1);
        assertThat(lineCount("cancel:1001")).isEqualTo(1);
        assertThat(linesOf(settlementId)).isEqualTo(2);

        // OPEN→FINALIZED + finalized_at.
        assertThat(statusOf(settlementId)).isEqualTo("FINALIZED");
        assertThat(finalizedAtNull(settlementId)).isFalse();

        // Σlines 권위 fee/vat/net: gross=50000, cancel=20000, rate=0.10
        // fee=5000.00, vat=500.00, net=50000-20000-5000-500=24500.00
        assertThat(decOf(settlementId, "fee_amount")).isEqualByComparingTo("5000.00");
        assertThat(decOf(settlementId, "vat_amount")).isEqualByComparingTo("500.00");
        assertThat(decOf(settlementId, "net_amount")).isEqualByComparingTo("24500.00");
        // 헤더 gross/cancel 은 record() 원자 증분으로 Σlines 와 일치(drift 없음).
        assertThat(decOf(settlementId, "gross_amount")).isEqualByComparingTo("50000");
        assertThat(decOf(settlementId, "cancel_amount")).isEqualByComparingTo("20000");

        // 재실행: FINALIZED 는 findFinalizable 에서 제외 → 신규 라인/변경 없음(dedup + 재-finalize 없음).
        reconcileService.runOnce(CUTOFF);
        assertThat(linesOf(settlementId)).isEqualTo(2);
        assertThat(statusOf(settlementId)).isEqualTo("FINALIZED");
    }

    private long seedOpenHeader(long merchantId) {
        jdbc.update("""
            INSERT INTO settlement
                (merchant_id, period_start, period_end, gross_amount, cancel_amount,
                 fee_amount, vat_amount, net_amount, status, created_at, updated_at)
            VALUES (?, ?, ?, 0, 0, 0, 0, 0, 'OPEN', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """, merchantId, java.sql.Date.valueOf(PERIOD_START), java.sql.Date.valueOf(PERIOD_END));
        Long id = jdbc.queryForObject(
                "SELECT id FROM settlement WHERE merchant_id = ? AND period_start = ?",
                Long.class, merchantId, java.sql.Date.valueOf(PERIOD_START));
        assertThat(id).isNotNull();
        return id;
    }

    private int lineCount(String eventId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_line WHERE event_id = ?", Integer.class, eventId);
        return c == null ? 0 : c;
    }

    private int linesOf(long settlementId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_line WHERE settlement_id = ?", Integer.class, settlementId);
        return c == null ? 0 : c;
    }

    private String statusOf(long settlementId) {
        return jdbc.queryForObject("SELECT status FROM settlement WHERE id = ?", String.class, settlementId);
    }

    private boolean finalizedAtNull(long settlementId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE id = ? AND finalized_at IS NULL",
                Integer.class, settlementId);
        return c != null && c > 0;
    }

    private BigDecimal decOf(long settlementId, String col) {
        return jdbc.queryForObject(
                "SELECT " + col + " FROM settlement WHERE id = ?", BigDecimal.class, settlementId);
    }
}
