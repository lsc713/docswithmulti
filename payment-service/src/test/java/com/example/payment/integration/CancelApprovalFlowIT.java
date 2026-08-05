package com.example.payment.integration;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.application.interfaces.RiskManagementPort;
import com.example.payment.domain.entity.*;
import com.example.payment.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 취소 승인 워크플로우 종단간 통합 테스트 (Testcontainers + 실제 MySQL, HTTP 경유).
 *
 * approve()가 기존 {@code CancelPaymentService.cancel()}(TX1/TX2/TX3)을 그대로 호출하는
 * 새 호출자일 뿐임을 증명한다 — 승인 코어가 취소 코어를 변경하지 않았다는 회귀 증거.
 * risk/PG 스텁·Testcontainers 부트스트랩은 {@link CancelFlowIntegrationTest}와 동일 관행을 재사용한다.
 */
@Testcontainers
@SpringBootTest(properties = "cancel.publish.mode=INLINE")
@DisplayName("CancelApprovalFlow 종단간 통합 테스트 (Testcontainers)")
class CancelApprovalFlowIT {

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

    // ── 외부 의존성 Mock (기존 CancelFlowIntegrationTest와 동일 스텁 방식) ─────
    @MockitoBean RiskManagementPort riskManagementPort;
    @MockitoBean PgCancelPort pgCancelPort;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean RedissonClient redissonClient;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired PaymentJpaRepository paymentJpaRepository;
    @Autowired PaymentItemJpaRepository paymentItemJpaRepository;
    @Autowired CancelRequestJpaRepository cancelRequestJpaRepository;
    @Autowired CancelApprovalJpaRepository cancelApprovalJpaRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    MockMvc mockMvc;

    private static final long OWNER_USER_ID = 7L;
    private static final long MERCHANT_ID = 42L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L,
                BigDecimal.valueOf(10_000_000),
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(9_900_000)));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(PgCancelResult.approved("pg-tx-approval-it"));
    }

    @AfterEach
    void cleanup() {
        cancelApprovalJpaRepository.deleteAll();
        cancelRequestJpaRepository.deleteAll();
        paymentItemJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
    }

    /** COMPLETED 결제 + 아이템 2개 시드 (CancelFlowIntegrationTest#insertTestData 관행 재사용). */
    private long seedCompletedPayment(String paymentKey) {
        PaymentJpaEntity savedPayment = paymentJpaRepository.save(
            PaymentJpaEntity.from(
                Payment.of(paymentKey, MERCHANT_ID, OWNER_USER_ID, "TOSS",
                    BigDecimal.valueOf(100_000), "KRW", 90)
            )
        );
        long paymentId = savedPayment.getId();

        paymentItemJpaRepository.save(PaymentItemJpaEntity.from(
            PaymentItem.of(paymentId, 10L, 1L, 2L, "상품A", BigDecimal.valueOf(30_000))));
        paymentItemJpaRepository.save(PaymentItemJpaEntity.from(
            PaymentItem.of(paymentId, 11L, 1L, 2L, "상품B", BigDecimal.valueOf(70_000))));

        return paymentId;
    }

    private long requestCancelApproval(String paymentKey) throws Exception {
        String body = mockMvc.perform(post("/v1/payments/{paymentKey}/cancel-requests", paymentKey)
                .header("X-User-Id", String.valueOf(OWNER_USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": "고객 변심"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andReturn().getResponse().getContentAsString();

        return ((Number) objectMapper.readValue(body, Map.class).get("id")).longValue();
    }

    // ──────────────────────────────────────────────────────────
    // 시나리오 A: 승인 → 실제 취소 실행(TX1/TX2/TX3)까지 관통
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A: 요청 → ADMIN 승인 → 기존 취소 코어 실행 → payment CANCELLED + cancel_request COMPLETED + 링크")
    void approve_drivesRealCancelExecution() throws Exception {
        String paymentKey = "it_cap_approve_001";
        long paymentId = seedCompletedPayment(paymentKey);

        long approvalId = requestCancelApproval(paymentKey);

        mockMvc.perform(post("/v1/cancel-requests/{id}/approve", approvalId)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.cancelRequestId").isNotEmpty());

        CancelApproval approval = cancelApprovalJpaRepository.findById(approvalId).orElseThrow().toDomain();
        assertThat(approval.getStatus()).isEqualTo(CancelApprovalStatus.APPROVED);
        assertThat(approval.getCancelRequestId()).isNotNull();

        CancelRequestJpaEntity cancelRequest =
            cancelRequestJpaRepository.findById(approval.getCancelRequestId()).orElseThrow();
        assertThat(cancelRequest.getStatus()).isEqualTo(CancelStatus.COMPLETED);

        assertThat(paymentJpaRepository.findById(paymentId).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.CANCELLED);
    }

    // ──────────────────────────────────────────────────────────
    // 시나리오 B: 반려 → 결제 불변 + 재요청 허용
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("B: 요청 → ADMIN 반려 → payment 여전히 COMPLETED, cancel_request 미생성, 재요청 허용")
    void reject_leavesPaymentUntouched_andAllowsReRequest() throws Exception {
        String paymentKey = "it_cap_reject_001";
        long paymentId = seedCompletedPayment(paymentKey);

        long approvalId = requestCancelApproval(paymentKey);

        mockMvc.perform(post("/v1/cancel-requests/{id}/reject", approvalId)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decisionReason": "서류 미비"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.decisionReason").value("서류 미비"));

        assertThat(paymentJpaRepository.findById(paymentId).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(cancelRequestJpaRepository.findAll()).isEmpty();

        // 재요청 허용 — 이전 승인 건은 REJECTED(더 이상 active REQUESTED 아님)라서 새 요청 생성 가능
        long secondApprovalId = requestCancelApproval(paymentKey);
        assertThat(secondApprovalId).isNotEqualTo(approvalId);
    }

    // ──────────────────────────────────────────────────────────
    // 시나리오 C: 스코프 — 타 가맹점 MERCHANT 승인 시도 → 403, 실행 없음
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("C: merchantId 불일치 MERCHANT 승인 시도 → 403, payment 여전히 COMPLETED, 취소 미실행")
    void approve_withMismatchedMerchantScope_returns403_andSkipsExecution() throws Exception {
        String paymentKey = "it_cap_scope_001";
        long paymentId = seedCompletedPayment(paymentKey);

        long approvalId = requestCancelApproval(paymentKey);

        mockMvc.perform(post("/v1/cancel-requests/{id}/approve", approvalId)
                .header("X-User-Role", "MERCHANT")
                .header("X-User-Id", "3")
                .header("X-Merchant-Id", "99")) // payment merchantId=42와 불일치
            .andExpect(status().isForbidden());

        assertThat(paymentJpaRepository.findById(paymentId).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(cancelRequestJpaRepository.findAll()).isEmpty();

        Optional<CancelApprovalJpaEntity> approval = cancelApprovalJpaRepository.findById(approvalId);
        assertThat(approval).isPresent();
        assertThat(approval.get().toDomain().getStatus()).isEqualTo(CancelApprovalStatus.REQUESTED);
    }
}
