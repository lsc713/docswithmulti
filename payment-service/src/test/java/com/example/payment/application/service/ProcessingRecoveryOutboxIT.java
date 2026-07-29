package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.domain.entity.*;
import com.example.payment.infrastructure.persistence.*;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * OUTBOX 모드에서 processing-recovery → saveTx3 → outbox INSERT 경로의 정합성(멱등성) 통합테스트.
 *
 * 검증 대상:
 *   1. PROCESSING 5분 초과 + PG APPROVED → saveTx3 실행 → outbox 행 1개 생성
 *   2. outbox 행 선존재 + 동일 cancelRequestId로 복구 재실행 → UK ON DUPLICATE 무시 → 행 1개, 예외 없음
 *
 * 외부 스텁:
 *   - PgCancelPort: HTTP 외부 PG 클라이언트. getStatus() → APPROVED로 스텁해 saveTx3 경로를 유도.
 *   - KafkaTemplate: CancelEventOutboxPublisher 스케줄러 빈이 OUTBOX 모드에서 KafkaTemplate을 주입받아
 *       컨텍스트 기동 시 필요. 이 테스트는 발행 스케줄러를 실행하지 않으므로 스텁으로 충분.
 *   - RedissonClient: CancelEventOutboxPublisher 스케줄러 빈이 분산락에 사용. 컨텍스트 기동 스텁.
 *   - RiskManagementPort: PG APPROVED 경로에서는 보상 호출 없음. 스텁 불필요(실제 빈은 HTTP 클라이언트이나
 *       이 경로에서 호출되지 않으므로 MockitoBean으로 대체해 HTTP 연결 오류 방지).
 *
 * 픽스처 구성:
 *   - Payment: COMPLETED, paymentKey="recovery_pay_001"
 *   - PaymentItem: ACTIVE 1개 (cancelAmount=50,000원)
 *   - CancelRequest: PROCESSING, updatedAt=10분 전 (5분 임계값 초과)
 */
@Testcontainers
@SpringBootTest(properties = "cancel.publish.mode=OUTBOX")
@DisplayName("OUTBOX 모드에서 processing-recovery가 outbox 행을 멱등 생성")
class ProcessingRecoveryOutboxIT {

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

    // ── 외부 의존성 Mock ──────────────────────────────────────────────────────
    @MockitoBean PgCancelPort pgCancelPort;
    @MockitoBean com.example.payment.application.interfaces.RiskManagementPort riskManagementPort;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;  // 스케줄러 빈 기동용
    @MockitoBean RedissonClient redissonClient;                 // 스케줄러 빈 기동용

    // ── 테스트 대상 ───────────────────────────────────────────────────────────
    @Autowired ProcessingRecoveryService recoveryService;
    @Autowired CancelEventOutboxRepository outboxRepository;

    // ── 직접 조회/삽입용 JPA 레포지토리 ────────────────────────────────────────
    @Autowired PaymentJpaRepository paymentJpaRepository;
    @Autowired PaymentItemJpaRepository paymentItemJpaRepository;
    @Autowired CancelRequestJpaRepository cancelRequestJpaRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private long paymentId;
    private long itemId;
    private long cancelRequestId;

    @BeforeEach
    void insertFixture() {
        // 1) Payment: COMPLETED
        PaymentJpaEntity savedPayment = paymentJpaRepository.save(
            PaymentJpaEntity.from(
                Payment.of("recovery_pay_001", 1L, 1L, "TOSS",
                    BigDecimal.valueOf(100_000), "KRW", 90)
            )
        );
        paymentId = savedPayment.getId();

        // 2) PaymentItem: ACTIVE, 50,000원
        PaymentItemJpaEntity savedItem = paymentItemJpaRepository.save(
            PaymentItemJpaEntity.from(
                PaymentItem.of(paymentId, 10L, 1L, 2L, "테스트상품", BigDecimal.valueOf(50_000))
            )
        );
        itemId = savedItem.getId();

        // 3) CancelRequest: PROCESSING, updatedAt=10분 전 (임계값 5분 초과)
        //    CancelRequest.reconstruct()로 도메인 객체 생성 후 JPA 엔티티로 변환.
        //    updatedAt이 10분 전이어야 findProcessingUpdatedBefore(now - 5min) 조회에 걸림.
        LocalDateTime tenMinutesAgo = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        CancelRequest processingRequest = CancelRequest.reconstruct(
            null,                          // id: DB 자동 생성
            paymentId,
            "test_hash_recovery_001",
            BigDecimal.valueOf(50_000),
            "복구 테스트",
            List.of(itemId),
            CancelStatus.PROCESSING,
            0,                             // pgRetryCount
            null,                          // completedAt
            null,                          // pgPendingSince
            tenMinutesAgo.toInstant(ZoneOffset.UTC),
            tenMinutesAgo.toInstant(ZoneOffset.UTC)
        );
        CancelRequestJpaEntity saved = cancelRequestJpaRepository.save(
            CancelRequestJpaEntity.from(processingRequest)
        );
        // updatedAt을 10분 전으로 강제 설정 (JPA @PreUpdate로 덮어쓸 수 있으므로 직접 수정 후 재저장)
        saved.setUpdatedAt(tenMinutesAgo);
        cancelRequestJpaRepository.save(saved);
        cancelRequestId = saved.getId();

        // PG getStatus: APPROVED 반환
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.approved("pg_tx_recovery_001"));
    }

    @AfterEach
    void cleanup() {
        cancelRequestJpaRepository.deleteAll();
        paymentItemJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
        // cancel_event_outbox는 @Transactional 롤백이 없으므로 직접 삭제
        // (이 IT는 @Transactional을 붙이지 않음 — TX3가 REQUIRES_NEW이므로 중첩 롤백 불가)
        // outbox 테이블은 별도 native query로 정리
        clearOutboxTable();
    }

    private void clearOutboxTable() {
        // 네이티브 쿼리로 outbox 테이블 전체 삭제
        // CancelEventOutboxJpaRepository를 직접 autowire하는 것과 동일한 효과
        outboxJpaRepository.deleteAll();
    }

    @Autowired
    CancelEventOutboxJpaRepository outboxJpaRepository;

    // ──────────────────────────────────────────────────────────────────────────
    // TC-1: 복구 실행 → outbox 행 1개 생성
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PROCESSING 복구 → saveTx3 재실행 → outbox 행 1개 생성")
    void recovery_creates_outbox_row() {
        // when
        recoveryService.recoverAll();

        // then: outbox에 정확히 1개의 행, cancelRequestId 일치
        List<CancelEventOutboxRepository.PendingOutbox> pending = outboxRepository.findPendingBatch(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).cancelRequestId()).isEqualTo(cancelRequestId);

        // CancelRequest 상태도 COMPLETED로 전환됐는지 추가 검증
        CancelRequestJpaEntity cr = cancelRequestJpaRepository.findById(cancelRequestId).orElseThrow();
        assertThat(cr.getStatus()).isEqualTo(CancelStatus.COMPLETED);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TC-2: outbox 행 선존재 + 복구 재실행 → 멱등 (중복 없음, 예외 없음)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("이미 outbox 행이 있으면 복구 재실행해도 중복/예외 없음 (UK ON DUPLICATE KEY)")
    void recovery_is_idempotent_when_outbox_row_preexists() {
        // given: 동일 cancelRequestId의 outbox 행 선존재
        // insertPending은 @Modifying 네이티브 쿼리이므로 트랜잭션 컨텍스트가 필요
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            outboxRepository.insertPending(cancelRequestId, "{\"cancelRequestId\":" + cancelRequestId + "}")
        );
        assertThat(outboxRepository.findPendingBatch(10)).hasSize(1);

        // when: 복구 재실행 (같은 cancelRequestId → saveTx3 → insertPending → UK 충돌 → 무시)
        // 단, CancelRequest가 이미 COMPLETED라면 saveTx3가 toCompleted()에서 상태 전이 오류를 낼 수 있음.
        // 실제 처리 순서: recoverAll()이 PROCESSING 상태 조회 → 이미 COMPLETED이면 조회 안 됨.
        // 따라서 이 TC는 outbox만 선존재하고 cancel_request는 여전히 PROCESSING인 시나리오.
        // (= TX3 롤백 후 outbox만 남은 경합 상황 재현)
        recoveryService.recoverAll();

        // then: 여전히 1개 (중복 삽입 없음), 예외도 없음
        List<CancelEventOutboxRepository.PendingOutbox> pending = outboxRepository.findPendingBatch(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).cancelRequestId()).isEqualTo(cancelRequestId);
    }
}
