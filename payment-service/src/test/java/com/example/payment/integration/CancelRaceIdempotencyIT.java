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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RESIL-02: 멀티파드 동시 취소 레이스 재현 (Testcontainers 실 MySQL + 같은 JVM 동시 스레드).
 *
 * D-02: 실제 2 인스턴스는 구동하지 않는다 — 같은 JVM 안에서 ExecutorService + CountDownLatch 로
 * 동일 payment 에 대한 동일 취소 요청을 동시에 착지시켜, saveTx1 의 (payment_id, request_hash)
 * UK 위반이 500 이 아니라 handleExistingRequest 경유 멱등 응답(200 상당)으로 번역되는지 검증한다.
 *
 * 검증은 최종 DB 상태만 본다: cancel_request 정확히 1건, PaymentItem 이중취소 0,
 * 두 응답 모두 예외 없이 반환(500 아님).
 */
@Testcontainers
@SpringBootTest
@DisplayName("CancelRaceIdempotency 통합 테스트 (Testcontainers, RESIL-02)")
class CancelRaceIdempotencyIT {

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

    @BeforeEach
    void insertTestData() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));

        PaymentJpaEntity savedPayment = paymentJpaRepository.save(
            PaymentJpaEntity.from(
                Payment.of("it_pay_race_001", 1L, 1L, "TOSS",
                    BigDecimal.valueOf(30_000), "KRW", 90)
            )
        );
        paymentId = savedPayment.getId();

        PaymentItemJpaEntity a = paymentItemJpaRepository.save(
            PaymentItemJpaEntity.from(
                PaymentItem.of(paymentId, 10L, 1L, 2L, "상품A", BigDecimal.valueOf(30_000))
            )
        );
        itemAId = a.getId();
    }

    @AfterEach
    void cleanup() {
        cancelRequestJpaRepository.deleteAll();
        paymentItemJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
    }

    @Test
    @DisplayName("동시 취소 레이스 — cancel_request 정확히 1건 COMPLETED, 이중취소 0, 패자 응답도 200 상당(500 아님)")
    void concurrentCancelRequests_produceExactlyOneCompletedRow_loserGetsIdempotentResponse()
        throws Exception {
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L,
                BigDecimal.valueOf(10_000_000),
                BigDecimal.valueOf(30_000),
                BigDecimal.valueOf(9_970_000)));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(PgCancelResult.approved("pg-tx-race-001"));

        CancelPaymentCommand command = new CancelPaymentCommand(
            "it_pay_race_001", "동시 취소 레이스", List.of(itemAId));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CancelRequest>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return cancelPaymentService.cancel(command);
            }));
        }
        start.countDown();

        // 레이스 패자의 saveTx1 UK 위반은 CancelPaymentService 가 이미 handleExistingRequest 로
        // 번역하므로, 두 Future 모두 예외 없이 반환되어야 한다(500 이 아님을 여기서 증명).
        List<CancelRequest> results = new ArrayList<>();
        for (Future<CancelRequest> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // (a) cancel_request 정확히 1건, 최종 COMPLETED
        List<CancelRequestJpaEntity> rows = cancelRequestJpaRepository.findAll();
        assertThat(rows).as("(payment_id, request_hash) UK 로 정확히 1건만 존재").hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(CancelStatus.COMPLETED);

        // (b) 이중취소 없음 — itemA 는 정확히 1회 CANCELLED
        assertThat(paymentItemJpaRepository.findById(itemAId).orElseThrow().getStatus())
            .isEqualTo(PaymentItemStatus.CANCELLED);

        // (c) 두 응답 모두 예외 없이 반환되었고(500 아님), 승자/패자 상태는 진행 중/완료 중 하나
        assertThat(results).hasSize(2);
        results.forEach(r -> assertThat(r.getStatus())
            .isIn(CancelStatus.PENDING, CancelStatus.PROCESSING, CancelStatus.COMPLETED));

        // Risk/PG 는 승자 경로에서만 정확히 1회 호출 — 패자는 handleExistingRequest 로 즉시 반환
        verify(riskManagementPort, times(1))
            .validateAndReserve(anyLong(), anyLong(), any(), any());
        verify(pgCancelPort, times(1)).cancel(any(), any(), any());
    }
}
