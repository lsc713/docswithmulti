package com.example.payment.integration;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.application.interfaces.RiskManagementPort;
import org.redisson.api.RedissonClient;
import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.application.service.CancelPaymentService;
import com.example.payment.domain.entity.*;
import com.example.payment.infrastructure.persistence.*;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 결제 취소 전체 플로우 통합 테스트 (Testcontainers + 실제 MySQL)
 *
 * TX1(PENDING) → Risk 호출 → TX2(PROCESSING) → PG 호출 → TX3(COMPLETED + Kafka 직접 발행)
 * 각 트랜잭션 커밋 이후 DB 상태를 직접 검증한다.
 *
 * Redis/Kafka 의존성은 test/resources/application.yml 에서 auto-configure 제외.
 */
@Testcontainers
@SpringBootTest
@DisplayName("CancelFlow 통합 테스트 (Testcontainers)")
class CancelFlowIntegrationTest {

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

    // ── 외부 의존성 Mock ────────────────────────────────────────
    @MockitoBean RiskManagementPort riskManagementPort;
    @MockitoBean PgCancelPort pgCancelPort;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate; // Kafka 실제 발행 차단
    @MockitoBean RedissonClient redissonClient;               // Redis 연결 없이 스케줄러 빈 생성

    // ── 실제 JPA 레포지토리 (직접 조회용) ─────────────────────
    @Autowired PaymentJpaRepository paymentJpaRepository;
    @Autowired PaymentItemJpaRepository paymentItemJpaRepository;
    @Autowired CancelRequestJpaRepository cancelRequestJpaRepository;

    // ── 테스트 대상 서비스 ─────────────────────────────────────
    @Autowired CancelPaymentService cancelPaymentService;

    private long paymentId;
    private long itemAId;
    private long itemBId;

    @BeforeEach
    void insertTestData() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        PaymentJpaEntity savedPayment = paymentJpaRepository.save(
            PaymentJpaEntity.from(
                Payment.of("it_pay_001", 1L, 1L, "TOSS",
                    BigDecimal.valueOf(100_000), "KRW", 90)
            )
        );
        paymentId = savedPayment.getId();

        PaymentItemJpaEntity a = paymentItemJpaRepository.save(
            PaymentItemJpaEntity.from(
                PaymentItem.of(paymentId, 10L, 1L, 2L, 0L, 1, "상품A", BigDecimal.valueOf(30_000))
            )
        );
        itemAId = a.getId();

        PaymentItemJpaEntity b = paymentItemJpaRepository.save(
            PaymentItemJpaEntity.from(
                PaymentItem.of(paymentId, 11L, 1L, 2L, 0L, 1, "상품B", BigDecimal.valueOf(70_000))
            )
        );
        itemBId = b.getId();
    }

    @AfterEach
    void cleanup() {
        cancelRequestJpaRepository.deleteAll();
        paymentItemJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
    }

    // ──────────────────────────────────────────────────────────
    // Happy Path: TX1 → TX2 → TX3 전체 성공
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 취소 — TX1·TX2·TX3 순서대로 커밋되고 최종 상태 COMPLETED")
    void shouldCompleteFullCancelFlow() {
        AtomicLong capturedCancelRequestId = new AtomicLong();

        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenAnswer(inv -> {
                long crId = inv.getArgument(1);
                capturedCancelRequestId.set(crId);

                // TX1 커밋 이후 → DB에서 PENDING 상태 확인
                Optional<CancelRequestJpaEntity> tx1State =
                    cancelRequestJpaRepository.findById(crId);
                assertThat(tx1State).isPresent();
                assertThat(tx1State.get().getStatus()).isEqualTo(CancelStatus.PENDING);

                return new RiskReserveResult(1L,
                    BigDecimal.valueOf(10_000_000),
                    BigDecimal.valueOf(30_000),
                    BigDecimal.valueOf(9_970_000));
            });

        when(pgCancelPort.cancel(any(), any(), any()))
            .thenAnswer(inv -> {
                long crId = capturedCancelRequestId.get();

                // TX2 커밋 이후 → DB에서 PROCESSING 상태 확인
                Optional<CancelRequestJpaEntity> tx2State =
                    cancelRequestJpaRepository.findById(crId);
                assertThat(tx2State).isPresent();
                assertThat(tx2State.get().getStatus()).isEqualTo(CancelStatus.PROCESSING);

                return PgCancelResult.approved("pg-tx-it-001");
            });

        CancelPaymentCommand command = new CancelPaymentCommand(
            "it_pay_001", "통합 테스트 변심", List.of(itemAId));

        CancelRequest result = cancelPaymentService.cancel(command);

        // 반환값 검증
        assertThat(result.getStatus()).isEqualTo(CancelStatus.COMPLETED);

        // TX3 이후 DB: cancel_request = COMPLETED
        CancelRequestJpaEntity finalCr =
            cancelRequestJpaRepository.findById(capturedCancelRequestId.get()).orElseThrow();
        assertThat(finalCr.getStatus()).isEqualTo(CancelStatus.COMPLETED);
        assertThat(finalCr.getCompletedAt()).isNotNull();

        // TX3 이후 DB: itemA = CANCELLED, itemB = ACTIVE
        assertThat(paymentItemJpaRepository.findById(itemAId).orElseThrow().getStatus())
            .isEqualTo(PaymentItemStatus.CANCELLED);
        assertThat(paymentItemJpaRepository.findById(itemBId).orElseThrow().getStatus())
            .isEqualTo(PaymentItemStatus.ACTIVE);

        // TX3 Kafka 직접 발행 호출 검증
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    // ──────────────────────────────────────────────────────────
    // 멱등성: 동일 요청 재전송 시 기존 COMPLETED 반환
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("멱등성 — 동일 요청 두 번 호출 시 Risk·PG는 한 번만 호출")
    void shouldReturnExistingCompletedOnDuplicateRequest() {
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L,
                BigDecimal.valueOf(10_000_000),
                BigDecimal.valueOf(30_000),
                BigDecimal.valueOf(9_970_000)));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(PgCancelResult.approved("pg-tx-it-002"));

        CancelPaymentCommand command = new CancelPaymentCommand(
            "it_pay_001", "중복 테스트", List.of(itemAId));

        CancelRequest first = cancelPaymentService.cancel(command);
        assertThat(first.getStatus()).isEqualTo(CancelStatus.COMPLETED);

        CancelRequest second = cancelPaymentService.cancel(command);
        assertThat(second.getStatus()).isEqualTo(CancelStatus.COMPLETED);

        // Risk·PG는 각각 한 번만 호출
        verify(riskManagementPort, times(1))
            .validateAndReserve(anyLong(), anyLong(), any(), any());
        verify(pgCancelPort, times(1)).cancel(any(), any(), any());

        // cancel_request 행도 1개만 존재
        assertThat(cancelRequestJpaRepository.findAll()).hasSize(1);
    }

    // ──────────────────────────────────────────────────────────
    // 전액 취소: 두 아이템 모두 취소 시 Payment = CANCELLED
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("전액 취소 — 두 아이템 모두 취소 시 Payment 상태 = CANCELLED")
    void shouldSetPaymentCancelledWhenAllItemsCancelled() {
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L,
                BigDecimal.valueOf(10_000_000),
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(9_900_000)));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(PgCancelResult.approved("pg-tx-it-003"));

        cancelPaymentService.cancel(new CancelPaymentCommand(
            "it_pay_001", "전액 취소", List.of(itemAId, itemBId)));

        assertThat(paymentItemJpaRepository.findById(itemAId).orElseThrow().getStatus())
            .isEqualTo(PaymentItemStatus.CANCELLED);
        assertThat(paymentItemJpaRepository.findById(itemBId).orElseThrow().getStatus())
            .isEqualTo(PaymentItemStatus.CANCELLED);
        assertThat(paymentJpaRepository.findById(paymentId).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.CANCELLED);
    }

    // ──────────────────────────────────────────────────────────
    // Risk 실패: cancel_request = FAILED
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Risk 실패 — cancel_request FAILED, TX3 미실행, PaymentItem 상태 유지")
    void shouldMarkCancelRequestFailedWhenRiskFails() {
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenThrow(new com.example.payment.common.exception.infrastructure.RiskServiceException("risk 다운"));
        doThrow(new com.example.payment.common.exception.infrastructure.RiskServiceException("보상 실패"))
            .when(riskManagementPort).compensate(anyLong(), anyLong(), any());

        org.junit.jupiter.api.Assertions.assertThrows(
            com.example.payment.common.exception.infrastructure.RiskServiceException.class,
            () -> cancelPaymentService.cancel(new CancelPaymentCommand(
                "it_pay_001", "risk 실패 테스트", List.of(itemAId)))
        );

        // cancel_request = FAILED
        List<CancelRequestJpaEntity> requests = cancelRequestJpaRepository.findAll();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getStatus()).isEqualTo(CancelStatus.FAILED);

        // TX3 미실행 → Kafka 발행 없음
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

        // PaymentItem 상태 변경 없음
        assertThat(paymentItemJpaRepository.findById(itemAId).orElseThrow().getStatus())
            .isEqualTo(PaymentItemStatus.ACTIVE);
    }

    // ──────────────────────────────────────────────────────────
    // Risk → PG 호출 순서 검증
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Risk → PG 순서 보장 — TX1 커밋 후 Risk, TX2 커밋 후 PG")
    void shouldInvokeRiskAndPgInCorrectOrder() {
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L,
                BigDecimal.valueOf(10_000_000),
                BigDecimal.valueOf(30_000),
                BigDecimal.valueOf(9_970_000)));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(PgCancelResult.approved("pg-tx-it-004"));

        cancelPaymentService.cancel(new CancelPaymentCommand(
            "it_pay_001", "순서 테스트", List.of(itemAId)));

        InOrder inOrder = inOrder(riskManagementPort, pgCancelPort);
        inOrder.verify(riskManagementPort).validateAndReserve(anyLong(), anyLong(), any(), any());
        inOrder.verify(pgCancelPort).cancel(anyString(), any(), anyString());
    }
}
