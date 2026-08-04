package com.example.settlement.integration;

import com.example.settlement.application.interfaces.MerchantPayoutAccountRepository;
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
 * Payout 조회/검증 통합테스트 (ACCT-02/PAY-03/ACCT-01 edge):
 * GET 계좌·GET 지급은 없으면 커스텀 예외 → 404 {code,message}, 있으면 200. 공백 필드 PUT → 400, 아무 것도 안 씀.
 *
 * <p>harness = MerchantSettlementConfigIntegrationTest 복제(MySQL only + RANDOM_PORT + kafka listener off,
 * java.net.http.HttpClient). 은행 미접근 — IT 프로파일(!local)의 BankTransferHttpClient stub 빈으로 충분(@MockitoBean 불필요).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Payout query: GET 계좌/지급 200·404(커스텀 예외) + 공백 필드 PUT 400")
class PayoutQueryIntegrationTest {

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
    @Autowired MerchantPayoutAccountRepository accountRepo;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payout");
        jdbc.update("DELETE FROM merchant_payout_account");
    }

    // --- ACCT-02: GET account ---

    @Test
    @DisplayName("미설정 계좌 GET → 404 PAYOUT_ACCOUNT_NOT_FOUND {code,message}")
    void getMissingAccount_404() throws Exception {
        HttpResponse<String> resp = getAccount(999);
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(resp.body()).contains("PAYOUT_ACCOUNT_NOT_FOUND");
    }

    @Test
    @DisplayName("PUT 후 GET 계좌 → 200 + 저장 필드 반환(DB 왕복)")
    void putThenGetAccount_200() throws Exception {
        assertThat(putAccount(601, "004", "123-456", "홍길동").statusCode()).isEqualTo(200);

        HttpResponse<String> resp = getAccount(601);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"bankCode\":\"004\"")
                .contains("\"accountNumber\":\"123-456\"")
                .contains("홍길동")
                .contains("\"active\":true");
    }

    // --- ACCT-01 edge: blank field 400 ---

    @Test
    @DisplayName("공백 bankCode PUT → 400, 아무 것도 안 씀")
    void putBlankBankCode_400_nothingWritten() throws Exception {
        HttpResponse<String> resp = putAccount(602, "", "123-456", "홍길동");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(accountRepo.findActive(602)).isEmpty();   // 거부된 요청은 아무 것도 쓰지 않음
    }

    // --- PAY-03: GET payout ---

    @Test
    @DisplayName("지급 건 없는 정산 GET → 404 PAYOUT_NOT_FOUND {code,message}")
    void getMissingPayout_404() throws Exception {
        HttpResponse<String> resp = getPayout(12345);
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(resp.body()).contains("PAYOUT_NOT_FOUND");
    }

    @Test
    @DisplayName("지급 건 시드 후 GET → 200 + 상태 반환")
    void seededPayout_get_200() throws Exception {
        long settlementId = 7001L;
        seedPayout(settlementId, "PO-" + settlementId, "PROCESSING");

        HttpResponse<String> resp = getPayout(settlementId);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("PROCESSING");
    }

    // --- helpers ---

    private HttpResponse<String> putAccount(long merchantId, String bankCode, String accountNumber,
                                            String holderName) throws Exception {
        String body = "{\"bankCode\":\"" + bankCode + "\",\"accountNumber\":\"" + accountNumber
                + "\",\"holderName\":\"" + holderName + "\"}";
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/settlements/payout-account/" + merchantId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAccount(long merchantId) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/settlements/payout-account/" + merchantId))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getPayout(long settlementId) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/settlements/" + settlementId + "/payout"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void seedPayout(long settlementId, String transferRef, String status) {
        jdbc.update("INSERT INTO payout "
                + "(settlement_id, merchant_id, amount, status, transfer_ref, attempt_count, "
                + " requested_at, created_at, updated_at) "
                + "VALUES (?, 700, 10000.00, ?, ?, 1, NOW(3), NOW(3), NOW(3))",
                settlementId, status, transferRef);
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
