package com.example.settlement.integration;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.application.interfaces.BankTransferPort.TransferAck;
import com.example.settlement.application.interfaces.OperationAlertPort;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RETRY-01: FAILED payout 재시도 → attempt 상한까지 재제출(same transfer_ref) → 소진 시 DEAD + alert-once.
 * claimForRetry(FAILED→PROCESSING attempt++) / markDeadIfExhausted(FAILED→DEAD) 원자 UPDATE 검증.
 * PayoutPollIntegrationTest harness 복제(MySQL only), 은행+알림은 @MockitoBean. retryFailed() 직접 호출.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=false",
                "payout.retry.max-attempts=5", "payout.retry.grace-seconds=60"})
@Testcontainers
@DisplayName("Payout 재시도: FAILED<max → 재제출+attempt++; FAILED>=max → DEAD + alert once (no re-alert)")
class PayoutRetryIntegrationTest {

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
    @MockitoBean OperationAlertPort operationAlertPort;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payout");
        jdbc.update("DELETE FROM merchant_payout_account");
        when(bankTransferPort.submit(any(), any(), any())).thenReturn(new TransferAck(true));
    }

    @Test
    @DisplayName("FAILED + attempt<max + grace 초과 → 재제출(same ref) + PROCESSING + attempt++")
    void failedUnderCap_resubmitsSameRefAndIncrementsAttempt() {
        seedAccount(700L);
        seedPayout(901L, "PO-901", 700L, "FAILED", 2, 120);   // grace(60s) 초과

        payoutResultService.retryFailed();

        assertThat(statusOf("PO-901")).isEqualTo("PROCESSING");
        assertThat(attemptOf("PO-901")).isEqualTo(3);
        verify(bankTransferPort, times(1)).submit(eq("PO-901"), any(), any());
        verify(operationAlertPort, never()).alert(any());
    }

    @Test
    @DisplayName("FAILED + attempt>=max → DEAD + alert 정확히 1회; submit 미호출")
    void failedAtCap_deadAndAlertOnce() {
        seedAccount(700L);
        seedPayout(902L, "PO-902", 700L, "FAILED", 5, 120);   // attempt==max

        payoutResultService.retryFailed();

        assertThat(statusOf("PO-902")).isEqualTo("DEAD");
        verify(operationAlertPort, times(1)).alert(contains("PO-902"));
        verify(bankTransferPort, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("DEAD 이후 두 번째 tick → 재알림 없음(times(1)) + DEAD 유지")
    void afterDead_noReAlert() {
        seedAccount(700L);
        seedPayout(903L, "PO-903", 700L, "FAILED", 5, 120);

        payoutResultService.retryFailed();   // → DEAD + alert 1
        payoutResultService.retryFailed();   // DEAD 라 재선별 안 됨

        assertThat(statusOf("PO-903")).isEqualTo("DEAD");
        verify(operationAlertPort, times(1)).alert(contains("PO-903"));
    }

    @Test
    @DisplayName("두 tick 이 하나의 FAILED 행을 봐도 정확히 1건만 claim(재제출 1회, 이중지급 없음)")
    void twoTicks_claimOnce_noDoubleResubmit() {
        seedAccount(700L);
        seedPayout(904L, "PO-904", 700L, "FAILED", 2, 120);

        payoutResultService.retryFailed();   // claim → PROCESSING, submit 1
        payoutResultService.retryFailed();   // 이미 PROCESSING → FAILED select 미매치, no-op

        assertThat(statusOf("PO-904")).isEqualTo("PROCESSING");
        assertThat(attemptOf("PO-904")).isEqualTo(3);
        verify(bankTransferPort, times(1)).submit(eq("PO-904"), any(), any());
    }

    @Test
    @DisplayName("fresh FAILED(updated_at=now, grace 내) → 미선별 → FAILED 유지, 무제출/무알림")
    void freshFailed_notSelected() {
        seedAccount(700L);
        seedPayout(905L, "PO-905", 700L, "FAILED", 2, 0);     // grace 내

        payoutResultService.retryFailed();

        assertThat(statusOf("PO-905")).isEqualTo("FAILED");
        assertThat(attemptOf("PO-905")).isEqualTo(2);
        verify(bankTransferPort, never()).submit(any(), any(), any());
        verify(operationAlertPort, never()).alert(any());
    }

    @Test
    @DisplayName("활성 계좌 없음 → skip(로그) → FAILED 유지, claim/DEAD/alert 없음(계좌 복구 시 재개)")
    void noActiveAccount_skippedLeftFailed() {
        // 계좌 미시딩(비활성/미설정)
        seedPayout(906L, "PO-906", 700L, "FAILED", 2, 120);

        payoutResultService.retryFailed();

        assertThat(statusOf("PO-906")).isEqualTo("FAILED");
        assertThat(attemptOf("PO-906")).isEqualTo(2);
        verify(bankTransferPort, never()).submit(any(), any(), any());
        verify(operationAlertPort, never()).alert(any());
    }

    private void seedAccount(long merchantId) {
        jdbc.update("INSERT INTO merchant_payout_account "
                + "(merchant_id, bank_code, account_number, holder_name, active, created_at, updated_at) "
                + "VALUES (?, '004', '110-1234', 'HOLDER', TRUE, NOW(3), NOW(3))", merchantId);
    }

    private void seedPayout(long settlementId, String transferRef, long merchantId,
                            String status, int attemptCount, int updatedSecondsAgo) {
        jdbc.update("INSERT INTO payout "
                + "(settlement_id, merchant_id, amount, status, transfer_ref, attempt_count, "
                + " requested_at, created_at, updated_at) "
                + "VALUES (?, ?, 10000.00, ?, ?, ?, NOW(3), NOW(3), "
                + " DATE_SUB(NOW(3), INTERVAL " + updatedSecondsAgo + " SECOND))",
                settlementId, merchantId, status, transferRef, attemptCount);
    }

    private String statusOf(String transferRef) {
        return jdbc.queryForObject("SELECT status FROM payout WHERE transfer_ref = ?", String.class, transferRef);
    }

    private int attemptOf(String transferRef) {
        Integer c = jdbc.queryForObject(
                "SELECT attempt_count FROM payout WHERE transfer_ref = ?", Integer.class, transferRef);
        return c == null ? -1 : c;
    }
}
