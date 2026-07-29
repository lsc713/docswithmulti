package com.example.payment.integration;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.application.interfaces.RiskManagementPort;
import com.example.payment.application.service.CancelTxWriter;
import com.example.payment.common.exception.BusinessException;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.infrastructure.persistence.CancelRequestJpaEntity;
import com.example.payment.infrastructure.persistence.CancelRequestJpaRepository;
import com.example.payment.infrastructure.persistence.PaymentItemJpaEntity;
import com.example.payment.infrastructure.persistence.PaymentItemJpaRepository;
import com.example.payment.infrastructure.persistence.PaymentJpaEntity;
import com.example.payment.infrastructure.persistence.PaymentJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RESIL-03: ProcessingRecovery 동시성 가드 재현.
 *
 * D-02: 멀티 스케줄러 인스턴스 동시 실행은 Testcontainers(실 MySQL) + 같은 JVM 동시 스레드
 * (ExecutorService + CountDownLatch)로 재현한다. 실제 2 인스턴스 구동은 하지 않는다.
 *
 * 검증 (A): incrementPgRetryCount 원자 UPDATE를 N 스레드가 동시 호출 → 최종 카운트 정확히 N(유실 0).
 * 검증 (B): 같은 CancelRequest 로 saveTx3(TX3 재실행)를 2 스레드가 동시 시도 → 정확히 1건 COMPLETED.
 * 검증 (C, CR-03): 같은 PROCESSING CancelRequest 에 compareAndSetFailed 원자 UPDATE를 N 스레드가 동시
 * 호출 → 정확히 1스레드만 rowcount=1(승자) 나머지는 0(패자) — compensateAndFail이 이 가드를 compensate
 * 호출 전에 두어 이중 보상을 막는다(D-04 패턴 재사용, 레코드 단위 분산락 없이).
 *
 * 패자 스레드의 InvalidPaymentItemStatusException(BusinessException)은 정상 레이스 결과다
 * (findAllByPaymentIdForUpdate 행 락으로 순서가 강제되고, 승자 커밋 후 재조회한 패자가 이미
 * CANCELLED된 아이템을 보고 거부) — 테스트 실패 조건이 아니며, 최종 DB 상태만 단언한다(RESEARCH Pitfall 1).
 *
 * ProcessingRecoveryOutboxIT과 같은 소스셋 컨벤션(자체 Testcontainers @Container + 외부 포트 MockitoBean)을
 * 따른다. payment-service의 AbstractRepositoryTest는 클래스 레벨 @Transactional(자동 롤백)을 걸어두는데,
 * 이는 워커 스레드가 메인 스레드의 미커밋 픽스처를 볼 수 없게 만들어 동시성 재현과 충돌하므로 상속하지 않는다.
 */
@Testcontainers
@SpringBootTest(properties = "cancel.publish.mode=OUTBOX")
@DisplayName("ProcessingRecoveryConcurrencyIT: 스케줄러 동시 실행 재현 (RESIL-03, D-02)")
class ProcessingRecoveryConcurrencyIT {

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

    // ── 외부 의존성 Mock (스케줄러/HTTP 클라이언트 빈 기동용, ProcessingRecoveryOutboxIT 컨벤션) ──
    @MockitoBean PgCancelPort pgCancelPort;
    @MockitoBean RiskManagementPort riskManagementPort;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean RedissonClient redissonClient;

    @Autowired CancelRequestRepository cancelRequestRepository;
    @Autowired CancelRequestJpaRepository cancelRequestJpaRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentJpaRepository paymentJpaRepository;
    @Autowired PaymentItemJpaRepository paymentItemJpaRepository;
    @Autowired CancelTxWriter cancelTxWriter;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    // ── 검증 (A): pg_retry_count 원자 UPDATE 동시 호출 — 유실 0 ──────────────────

    @Test
    @DisplayName("N 스레드가 incrementPgRetryCount 동시 호출 → 최종 pg_retry_count == N (유실 0)")
    void concurrent_increment_never_loses_updates() throws Exception {
        long paymentId = insertPayment("concurrency_pay_a");
        long cancelRequestId = insertCancelRequest(paymentId, "hash_concurrency_a", List.of(1L));

        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                // 각 호출 독립 TX (스케줄러 각 실행이 별도 TX인 것과 동일)
                tx.executeWithoutResult(s -> cancelRequestRepository.incrementPgRetryCount(cancelRequestId));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        int finalCount = cancelRequestJpaRepository.findById(cancelRequestId).orElseThrow()
            .toDomain().getPgRetryCount();
        assertThat(finalCount).as("동시 호출 유실 0 — 정확히 N").isEqualTo(threads);
    }

    // ── 검증 (B): 같은 CancelRequest 로 saveTx3 동시 재실행 — 정확히 1건 COMPLETED ──

    @Test
    @DisplayName("두 스레드가 같은 CancelRequest 로 saveTx3 동시 시도 → 정확히 1건 COMPLETED, 이중취소 0")
    void concurrent_tx3_rerun_completes_exactly_once() throws Exception {
        long paymentId = insertPayment("concurrency_pay_b");
        long itemId = insertPaymentItem(paymentId, 50_000L);
        String requestHash = "hash_concurrency_b";
        insertCancelRequest(paymentId, requestHash, List.of(itemId));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger raceLoss = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    tx.execute(status -> {
                        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                        CancelRequest cancelRequest = cancelRequestRepository
                            .findByPaymentIdAndRequestHash(paymentId, requestHash).orElseThrow();
                        return cancelTxWriter.saveTx3(cancelRequest, payment, cancelRequest.getCancelItemIds());
                    });
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    // 정상 레이스 패자(예: InvalidPaymentItemStatusException) — 테스트 실패 조건 아님(RESEARCH Pitfall 1)
                    raceLoss.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(success.get()).as("승자 정확히 1명").isEqualTo(1);
        assertThat(raceLoss.get()).as("패자 정확히 1명(정상 레이스)").isEqualTo(1);

        CancelRequestJpaEntity finalRow = cancelRequestJpaRepository.findByPaymentIdAndRequestHash(
            paymentId, requestHash).orElseThrow();
        assertThat(finalRow.getStatus()).as("정확히 1건 COMPLETED").isEqualTo(CancelStatus.COMPLETED);

        long cancelledItemCount = paymentItemJpaRepository.findAllByPaymentIdOrderByIdAsc(paymentId)
            .stream().filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED).count();
        assertThat(cancelledItemCount).as("이중취소 0 — 아이템 정확히 1회만 CANCELLED").isEqualTo(1);
    }

    // ── 검증 (C): compareAndSetFailed 원자 UPDATE 동시 호출 — 정확히 1승자 ─────

    @Test
    @DisplayName("N 스레드가 같은 PROCESSING 건에 compareAndSetFailed 동시 호출 → 정확히 1건만 rowcount=1(승자), 나머지 0")
    void concurrent_compare_and_set_failed_has_exactly_one_winner() throws Exception {
        long paymentId = insertPayment("concurrency_pay_c");
        long cancelRequestId = insertCancelRequest(paymentId, "hash_concurrency_c", List.of(1L));

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                // 각 호출 독립 TX (스케줄러 각 실행/파드가 별도 TX인 것과 동일)
                Integer updated = tx.execute(s -> cancelRequestRepository.compareAndSetFailed(cancelRequestId));
                if (updated != null && updated == 1) {
                    winners.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(winners.get()).as("정확히 1스레드만 FAILED 전이에 성공 — 나머지는 compensate 호출 skip 대상").isEqualTo(1);

        CancelStatus finalStatus = cancelRequestJpaRepository.findById(cancelRequestId)
            .orElseThrow().toDomain().getStatus();
        assertThat(finalStatus).as("최종 상태는 정확히 1회 전이된 FAILED").isEqualTo(CancelStatus.FAILED);
    }

    // ── 픽스처 헬퍼 (TransactionTemplate 커밋 — 워커 스레드가 볼 수 있어야 함) ──────

    private long insertPayment(String paymentKey) {
        return tx.execute(status -> paymentJpaRepository.save(
            PaymentJpaEntity.from(Payment.of(paymentKey, 1L, 1L, "TOSS",
                BigDecimal.valueOf(100_000), "KRW", 90))
        ).getId());
    }

    private long insertPaymentItem(long paymentId, long amount) {
        return tx.execute(status -> paymentItemJpaRepository.save(
            PaymentItemJpaEntity.from(
                PaymentItem.of(paymentId, 10L, 1L, 2L, "테스트상품", BigDecimal.valueOf(amount))
            )
        ).getId());
    }

    private long insertCancelRequest(long paymentId, String requestHash, List<Long> itemIds) {
        return tx.execute(status -> {
            LocalDateTime tenMinutesAgo = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);
            CancelRequest cancelRequest = CancelRequest.reconstruct(
                null, paymentId, requestHash,
                BigDecimal.valueOf(50_000), "동시성 테스트",
                itemIds, CancelStatus.PROCESSING, 0,
                null, null,
                tenMinutesAgo.toInstant(ZoneOffset.UTC),
                tenMinutesAgo.toInstant(ZoneOffset.UTC)
            );
            return cancelRequestJpaRepository.save(CancelRequestJpaEntity.from(cancelRequest)).getId();
        });
    }
}
