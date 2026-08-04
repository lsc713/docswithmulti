package com.example.settlement.integration;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.application.interfaces.BankTransferPort.TransferStatus;
import com.example.settlement.application.service.PayoutResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Payout poll backstop (CONFIRM-02): grace 지난 PROCESSING 만 은행 조회 → SAME applyResult 로 PAID 수렴.
 * fresh PROCESSING 은 미선별. @Scheduled 대기 없이 pollStuckProcessing() 직접 호출(mock 락은 false 반환).
 * harness = MerchantSettlementConfigIntegrationTest 복제(MySQL only), 은행은 @MockitoBean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Payout poll backstop: stuck PROCESSING → getStatus → applyResult PAID; fresh 미선별")
class PayoutPollIntegrationTest {

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
    @Autowired PayoutResultService payoutResultService;

    @MockitoBean BankTransferPort bankTransferPort;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payout");
    }

    @Test
    @DisplayName("grace 지난 PROCESSING → getStatus PAID → PROCESSING→PAID(paid_at 세팅)")
    void stuckProcessing_convergesToPaid() {
        seedPayout(801L, "PO-801", "PROCESSING", 120);   // grace(60s) 초과
        when(bankTransferPort.getStatus(any())).thenReturn(TransferStatus.PAID);

        payoutResultService.pollStuckProcessing();

        assertThat(statusOf("PO-801")).isEqualTo("PAID");
        assertThat(paidAtNull("PO-801")).isFalse();
    }

    @Test
    @DisplayName("fresh PROCESSING(updated_at=now) → 미선별 → PROCESSING 유지(getStatus PAID 여도 무변경)")
    void freshProcessing_notSelected() {
        seedPayout(802L, "PO-802", "PROCESSING", 0);     // 방금 갱신 — cutoff 이후
        when(bankTransferPort.getStatus(any())).thenReturn(TransferStatus.PAID);

        payoutResultService.pollStuckProcessing();

        assertThat(statusOf("PO-802")).isEqualTo("PROCESSING");
        assertThat(paidAtNull("PO-802")).isTrue();
    }

    private void seedPayout(long settlementId, String transferRef, String status, int updatedSecondsAgo) {
        jdbc.update("INSERT INTO payout "
                + "(settlement_id, merchant_id, amount, status, transfer_ref, attempt_count, "
                + " requested_at, created_at, updated_at) "
                + "VALUES (?, 700, 10000.00, ?, ?, 1, NOW(3), NOW(3), "
                + " DATE_SUB(NOW(3), INTERVAL " + updatedSecondsAgo + " SECOND))",
                settlementId, status, transferRef);
    }

    private String statusOf(String transferRef) {
        return jdbc.queryForObject("SELECT status FROM payout WHERE transfer_ref = ?", String.class, transferRef);
    }

    private boolean paidAtNull(String transferRef) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payout WHERE transfer_ref = ? AND paid_at IS NULL",
                Integer.class, transferRef);
        return c != null && c > 0;
    }
}
