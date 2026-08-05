package com.example.settlement.integration;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.application.interfaces.BankTransferPort.TransferStatus;
import com.example.settlement.application.service.PayoutResultService;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 순서 무관 수렴 + 중복 콜백 멱등 (CONFIRM-03): webhook·poll·중복 콜백이 모두 shared applyResult 로 funnel —
 * status-guarded UPDATE 가 승자 1건만 PROCESSING→PAID 반영, 두 번째 도착은 0-row no-op 로 paid_at 불변.
 *
 * <p>세 순서 조합(webhook→poll / poll→webhook / 콜백 2회) 모두 정확히 1회 terminal 전이만 발생함을 증명.
 * harness = MerchantSettlementConfigIntegrationTest 복제(MySQL only), 은행은 @MockitoBean(getStatus 제어).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Payout convergence: webhook↔poll 순서 무관 + 중복 콜백 멱등(0-row no-op, paid_at 불변)")
class PayoutConvergenceIntegrationTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired PayoutResultService payoutResultService;
    @LocalServerPort int port;

    @MockitoBean BankTransferPort bankTransferPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payout");
    }

    @Test
    @DisplayName("webhook→poll: webhook 이 PAID 확정 후 poll 은 이미 PAID 라 미선별 → 무변경(paid_at 불변)")
    void webhookThenPoll_singleTransition() throws Exception {
        String ref = "PO-9001";
        seedStaleProcessing(9001L, ref);   // grace(60s) 초과 → poll 대상 후보

        // webhook 이 먼저 PAID 확정
        assertThat(callback(ref).statusCode()).isEqualTo(200);
        assertThat(statusOf(ref)).isEqualTo("PAID");
        Timestamp firstPaidAt = paidAtOf(ref);
        assertThat(firstPaidAt).isNotNull();

        // poll: 은행이 PAID 라 해도, 이미 PAID 라 findStuckProcessing 미선별 → applyResult 미호출
        when(bankTransferPort.getStatus(any())).thenReturn(TransferStatus.PAID);
        payoutResultService.pollStuckProcessing();

        assertThat(statusOf(ref)).isEqualTo("PAID");
        assertThat(paidAtOf(ref)).isEqualTo(firstPaidAt);   // 두 번째 도착 무효과
    }

    @Test
    @DisplayName("poll→webhook: poll 이 PAID 확정 후 중복 콜백은 0-row no-op → 무변경(paid_at 불변)")
    void pollThenWebhook_singleTransition() throws Exception {
        String ref = "PO-9002";
        seedStaleProcessing(9002L, ref);

        // poll 이 먼저 PAID 확정
        when(bankTransferPort.getStatus(any())).thenReturn(TransferStatus.PAID);
        payoutResultService.pollStuckProcessing();
        assertThat(statusOf(ref)).isEqualTo("PAID");
        Timestamp firstPaidAt = paidAtOf(ref);
        assertThat(firstPaidAt).isNotNull();

        // 뒤늦은 webhook: 같은 transferRef → applyResult WHERE status='PROCESSING' 0-row no-op
        assertThat(callback(ref).statusCode()).isEqualTo(200);

        assertThat(statusOf(ref)).isEqualTo("PAID");
        assertThat(paidAtOf(ref)).isEqualTo(firstPaidAt);   // 두 번째 도착 무효과
    }

    @Test
    @DisplayName("중복 콜백: 같은 PAID 콜백 2회 → 첫 건만 전이, 둘째는 0-row no-op(paid_at 불변)")
    void duplicateCallback_singleTransition() throws Exception {
        String ref = "PO-9003";
        seedStaleProcessing(9003L, ref);

        assertThat(callback(ref).statusCode()).isEqualTo(200);
        assertThat(statusOf(ref)).isEqualTo("PAID");
        Timestamp firstPaidAt = paidAtOf(ref);
        assertThat(firstPaidAt).isNotNull();

        // 중복 콜백 — 이미 PAID 라 0-row no-op
        assertThat(callback(ref).statusCode()).isEqualTo(200);

        assertThat(statusOf(ref)).isEqualTo("PAID");
        assertThat(paidAtOf(ref)).isEqualTo(firstPaidAt);   // 정확히 1회 terminal 전이
    }

    // --- helpers ---

    /** grace(60s) 초과한 PROCESSING 건 시드(poll 후보) — PayoutPollIntegrationTest 패턴. */
    private void seedStaleProcessing(long settlementId, String transferRef) {
        jdbc.update("INSERT INTO payout "
                + "(settlement_id, merchant_id, amount, status, transfer_ref, attempt_count, "
                + " requested_at, created_at, updated_at) "
                + "VALUES (?, 700, 10000.00, 'PROCESSING', ?, 1, NOW(3), NOW(3), "
                + " DATE_SUB(NOW(3), INTERVAL 120 SECOND))",
                settlementId, transferRef);
    }

    private HttpResponse<String> callback(String transferRef) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/payouts/callback"))
                .header("Content-Type", "application/json")
                .header("X-Bank-Signature", SECRET)
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"transferRef\":\"" + transferRef + "\",\"result\":\"PAID\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String statusOf(String transferRef) {
        return jdbc.queryForObject("SELECT status FROM payout WHERE transfer_ref = ?", String.class, transferRef);
    }

    private Timestamp paidAtOf(String transferRef) {
        return jdbc.queryForObject(
                "SELECT paid_at FROM payout WHERE transfer_ref = ?", Timestamp.class, transferRef);
    }
}
