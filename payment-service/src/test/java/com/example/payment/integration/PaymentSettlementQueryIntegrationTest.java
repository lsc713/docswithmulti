package com.example.payment.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /v1/payments/settlement 통합 테스트 (RECON-03, decision D-Q1).
 *
 * SALE 윈도우(payment.created_at)와 CANCEL 윈도우(cancel_request.completed_at)의 독립성 —
 * 특히 이전 주에 생성됐지만 이번 주에 취소 완료된 "carrier" — 을 실 MySQL(Testcontainers)로 증명하고,
 * 비-COMPLETED/윈도우 밖 취소 제외, read-only 불변, 잘못된 입력 400을 검증한다.
 * 좌석은 JdbcTemplate로 직접 시드해 created_at/completed_at을 정확히 통제한다(취소 코어 무변경).
 */
@Testcontainers
@SpringBootTest(properties = "cancel.publish.mode=INLINE")
@DisplayName("PaymentSettlement 조회 통합 테스트 (Testcontainers)")
class PaymentSettlementQueryIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean RedissonClient redissonClient;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    MockMvc mockMvc;

    private static final long MERCHANT = 7001L;
    private static final String FROM = "2026-07-27T00:00:00Z"; // 정산 주 시작
    private static final String TO   = "2026-08-03T00:00:00Z"; // 정산 주 끝 (배타)

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM cancel_request");
        jdbcTemplate.update("DELETE FROM payment_item");
        jdbcTemplate.update("DELETE FROM payment");
    }

    // ---- seed helpers -------------------------------------------------------

    private long insertPayment(String key, long merchantId, String status, String createdAtUtc) {
        jdbcTemplate.update(
            "INSERT INTO payment (payment_key, merchant_id, user_id, order_id, pg_type, total_amount, "
                + "currency, cancel_period_days, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'KRW', 90, ?, ?, ?)",
            key, merchantId, 100L, 0L, "TOSS", 30000, status, createdAtUtc, createdAtUtc);
        return jdbcTemplate.queryForObject("SELECT id FROM payment WHERE payment_key = ?", Long.class, key);
    }

    private long insertCancel(long paymentId, String hash, String status, String completedAtUtc) {
        jdbcTemplate.update(
            "INSERT INTO cancel_request (payment_id, request_hash, cancel_amount, cancel_item_ids, "
                + "status, completed_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, '[1]', ?, ?, ?, ?)",
            paymentId, hash, 10000, status, completedAtUtc, "2026-07-01T00:00:00", "2026-07-01T00:00:00");
        return jdbcTemplate.queryForObject(
            "SELECT id FROM cancel_request WHERE payment_id = ? AND request_hash = ?", Long.class, paymentId, hash);
    }

    private JsonNode findByKey(JsonNode arr, String key) {
        for (JsonNode n : arr) {
            if (key.equals(n.get("paymentKey").asText())) {
                return n;
            }
        }
        return null;
    }

    // ---- tests --------------------------------------------------------------

    @Test
    @DisplayName("SALE(created_at)·CANCEL(completed_at) 독립 윈도우 + cross-week carrier + 제외 규칙")
    void windowSemantics() throws Exception {
        // A: 윈도우 내 SALE, 취소 없음 → 반환
        insertPayment("pay_A_insale", MERCHANT, "COMPLETED", "2026-07-28T10:00:00");
        // B: 윈도우 밖 SALE, 취소 없음 → 제외
        insertPayment("pay_B_outsale", MERCHANT, "COMPLETED", "2026-07-20T10:00:00");
        // C: 이전 주 생성(윈도우 밖) 이지만 이번 주 취소 완료 → carrier 로 반환
        long cId = insertPayment("pay_C_carrier", MERCHANT, "CANCELLED", "2026-07-20T09:00:00");
        long inWindowCancelId = insertCancel(cId, "hash_c_in", "COMPLETED", "2026-07-29T12:00:00"); // 포함
        insertCancel(cId, "hash_c_out", "COMPLETED", "2026-08-05T12:00:00");                        // 윈도우 밖 → 제외
        insertCancel(cId, "hash_c_failed", "FAILED", "2026-07-29T13:00:00");                        // 비-COMPLETED → 제외
        // D: 다른 가맹점, 윈도우 내 → 제외
        insertPayment("pay_D_other", 9999L, "COMPLETED", "2026-07-28T10:00:00");

        String body = mockMvc.perform(get("/v1/payments/settlement")
                .param("merchantId", String.valueOf(MERCHANT))
                .param("from", FROM)
                .param("to", TO))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode arr = objectMapper.readTree(body);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(2); // A + C only (B out-of-window, D other-merchant)

        JsonNode a = findByKey(arr, "pay_A_insale");
        assertThat(a).isNotNull();
        assertThat(a.get("merchantId").asLong()).isEqualTo(MERCHANT);
        assertThat(a.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(a.get("totalAmount").asInt()).isEqualTo(30000);
        assertThat(a.get("createdAt").asText()).isEqualTo("2026-07-28T10:00:00Z"); // ISO-8601 UTC Z
        assertThat(a.get("cancels").size()).isZero();

        JsonNode c = findByKey(arr, "pay_C_carrier");
        assertThat(c).isNotNull();
        assertThat(c.get("createdAt").asText()).isEqualTo("2026-07-20T09:00:00Z"); // parent out-of-window
        assertThat(c.get("cancels").size()).isEqualTo(1); // ONLY the in-window COMPLETED cancel
        JsonNode cancel = c.get("cancels").get(0);
        assertThat(cancel.get("cancelRequestId").asLong()).isEqualTo(inWindowCancelId);
        assertThat(cancel.get("cancelAmount").asInt()).isEqualTo(10000);
        assertThat(cancel.get("completedAt").asText()).isEqualTo("2026-07-29T12:00:00Z");

        assertThat(findByKey(arr, "pay_B_outsale")).isNull();
        assertThat(findByKey(arr, "pay_D_other")).isNull();
    }

    @Test
    @DisplayName("read-only: 조회 전후 payment/payment_item/cancel_request 행수·컬럼 불변")
    void readOnlyInvariance() throws Exception {
        long cId = insertPayment("pay_ro", MERCHANT, "CANCELLED", "2026-07-20T09:00:00");
        insertCancel(cId, "hash_ro", "COMPLETED", "2026-07-29T12:00:00");
        insertPayment("pay_ro_sale", MERCHANT, "COMPLETED", "2026-07-28T10:00:00");

        Map<String, Object> before = snapshot();

        mockMvc.perform(get("/v1/payments/settlement")
                .param("merchantId", String.valueOf(MERCHANT))
                .param("from", FROM).param("to", TO))
            .andExpect(status().isOk());

        assertThat(snapshot()).isEqualTo(before);
    }

    private Map<String, Object> snapshot() {
        return Map.of(
            "payments", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Long.class),
            "items", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_item", Long.class),
            "cancels", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cancel_request", Long.class),
            "sampleCancelStatus", jdbcTemplate.queryForObject(
                "SELECT status FROM cancel_request WHERE request_hash = 'hash_ro'", String.class),
            "sampleCancelCompletedAt", String.valueOf(jdbcTemplate.queryForObject(
                "SELECT completed_at FROM cancel_request WHERE request_hash = 'hash_ro'", java.sql.Timestamp.class)));
    }

    @Test
    @DisplayName("입력 검증: merchantId<=0, from>=to, >60일 윈도우 → 400")
    void inputValidation() throws Exception {
        mockMvc.perform(get("/v1/payments/settlement")
                .param("merchantId", "0").param("from", FROM).param("to", TO))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/v1/payments/settlement")
                .param("merchantId", String.valueOf(MERCHANT)).param("from", TO).param("to", FROM))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/v1/payments/settlement")
                .param("merchantId", String.valueOf(MERCHANT))
                .param("from", "2026-01-01T00:00:00Z").param("to", "2026-04-01T00:00:00Z")) // ~90일
            .andExpect(status().isBadRequest());
    }
}
