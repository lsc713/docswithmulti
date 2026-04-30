# TX3 Inline Kafka 발행 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AFTER_COMMIT 이벤트 방식을 제거하고 TX3 마지막에 `kafkaTemplate.send()` 직접 호출로 전환한다. 실패 시 TX3 롤백 → CancelRequest PROCESSING 유지 → processing-recovery 재처리.

**Architecture:** `CancelTxWriter.saveTx3()`에서 DB 저장 완료 후 `kafkaTemplate.send().get(5s)` 직접 호출. 실패 시 예외 throw → `@Transactional` 롤백. `AFTER_COMMIT` 리스너, `CancelCompletedEvent`, `failed_kafka_event` 테이블·클래스, `failed-kafka-publisher` 스케줄러 전체 삭제.

**Tech Stack:** Spring Boot 3, Spring Kafka `KafkaTemplate`, Flyway V11, Redisson, JUnit 5 + Mockito

---

## 파일 맵

### 수정
| 파일 | 변경 내용 |
|------|---------|
| `application/service/CancelTxWriter.java` | `ApplicationEventPublisher` 제거, `KafkaTemplate` + topic 추가, `saveTx3()` 말미에 직접 send |
| `resources/application.yml` | `scheduler.lock.failed-kafka-publisher` 키 제거 |
| `docs/kafka-design.md` | Section 5 Producer 설계 → TX3 인라인 방식으로 교체 |
| `CLAUDE.md` | 스케줄러 4→3개, failed-kafka-retry 제거 |

### 삭제 (소스)
| 파일 |
|------|
| `application/event/CancelCompletedEvent.java` |
| `infrastructure/messaging/CancelEventPublisher.java` |
| `application/service/FailedKafkaPublisherService.java` |
| `infrastructure/scheduler/FailedKafkaPublisherScheduler.java` |
| `application/interfaces/FailedKafkaEventRepository.java` |
| `infrastructure/persistence/FailedKafkaEventJpaEntity.java` |
| `infrastructure/persistence/FailedKafkaEventJpaRepository.java` |
| `infrastructure/persistence/FailedKafkaEventRepositoryImpl.java` |

### 삭제 (테스트)
| 파일 |
|------|
| `CancelEventPublisherTest.java` |
| `FailedKafkaPublisherServiceTest.java` |
| `FailedKafkaPublisherSchedulerTest.java` |

### 추가
| 파일 | 이유 |
|------|------|
| `db/migration/V11__drop_failed_kafka_event.sql` | failed_kafka_event 테이블 삭제 |

---

## Task 1: V11 마이그레이션 작성

**Files:**
- Create: `payment-service/src/main/resources/db/migration/V11__drop_failed_kafka_event.sql`

- [ ] **Step 1: SQL 파일 작성**

```sql
-- V11__drop_failed_kafka_event.sql
-- AFTER_COMMIT + failed_kafka_event 방식 제거 → TX3 인라인 Kafka 발행으로 전환
DROP TABLE IF EXISTS failed_kafka_event;
```

---

## Task 2: CancelTxWriter 수정 — KafkaTemplate 직접 호출

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelTxWriter.java`
- Modify: `payment-service/src/test/java/com/example/payment/application/service/CancelTxWriterTest.java`

- [ ] **Step 1: CancelTxWriterTest 수정**

```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelTxWriter")
class CancelTxWriterTest {

    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock PaymentItemRepository paymentItemRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    private CancelTxWriter writer;
    private Payment payment;
    private PaymentItem itemA;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        CancelDomainService domainService = new CancelDomainService(new CancelPeriodPolicy(clock));
        writer = new CancelTxWriter(
            cancelRequestRepository, paymentItemRepository, paymentRepository,
            kafkaTemplate, domainService
        );
        ReflectionTestUtils.setField(writer, "topic", "payment.cancelled");
        payment = PaymentFixture.completedPayment();
        itemA = PaymentItem.reconstruct(1L, payment.getId(), 10L, 100L, 200L, "상품A",
            BigDecimal.valueOf(30000), PaymentItemStatus.ACTIVE);
    }

    @Test
    @DisplayName("saveTx1: PENDING 상태로 CancelRequest를 저장한다")
    void saveTx1_savesCancelRequestAsPending() {
        CancelRequest req = CancelRequest.create(
            payment.getId(), "hash-001", BigDecimal.valueOf(30000), "고객 변심", List.of(1L));
        when(cancelRequestRepository.save(any())).thenAnswer(inv -> {
            CancelRequest cr = inv.getArgument(0);
            return CancelRequest.reconstruct(1L, cr.getPaymentId(), cr.getRequestHash(),
                cr.getCancelAmount(), cr.getCancelReason(), cr.getCancelItemIds(), cr.getStatus(),
                0, null, null, cr.getCreatedAt(), cr.getUpdatedAt());
        });

        CancelRequest result = writer.saveTx1(req);

        assertEquals(CancelStatus.PENDING, result.getStatus());
        verify(cancelRequestRepository).save(req);
    }

    @Test
    @DisplayName("saveTx2: PROCESSING 상태로 전환 후 저장한다")
    void saveTx2_transitionsToCancelRequestToProcessing() {
        CancelRequest req = CancelRequest.reconstruct(1L, payment.getId(), "hash-001",
            BigDecimal.valueOf(30000), "고객 변심", List.of(1L), CancelStatus.PENDING,
            0, null, null, Instant.now(), Instant.now());
        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelRequest result = writer.saveTx2(req);

        assertEquals(CancelStatus.PROCESSING, result.getStatus());
    }

    @Test
    @DisplayName("saveTx3: FOR UPDATE 재조회 후 COMPLETED 저장 + Kafka 직접 발행")
    void saveTx3_reloadsItemsAndSendsToKafka() {
        CancelRequest req = CancelRequest.reconstruct(1L, payment.getId(), "hash-001",
            BigDecimal.valueOf(30000), "고객 변심", List.of(1L), CancelStatus.PROCESSING,
            0, null, null, Instant.now(), Instant.now());

        when(paymentItemRepository.findAllByPaymentIdForUpdate(payment.getId()))
            .thenReturn(List.of(itemA));
        when(paymentItemRepository.saveAll(anyList())).thenReturn(List.of(itemA));
        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        CancelRequest result = writer.saveTx3(req, payment, List.of(1L));

        assertEquals(CancelStatus.COMPLETED, result.getStatus());
        verify(paymentItemRepository).findAllByPaymentIdForUpdate(payment.getId());
        verify(kafkaTemplate).send(eq("payment.cancelled"), eq("1"), anyString());
    }

    @Test
    @DisplayName("saveTx3: Kafka 발행 실패 시 예외 발생 → TX3 롤백")
    void saveTx3_kafkaFailure_throwsException() {
        CancelRequest req = CancelRequest.reconstruct(1L, payment.getId(), "hash-001",
            BigDecimal.valueOf(30000), "고객 변심", List.of(1L), CancelStatus.PROCESSING,
            0, null, null, Instant.now(), Instant.now());

        when(paymentItemRepository.findAllByPaymentIdForUpdate(payment.getId()))
            .thenReturn(List.of(itemA));
        when(paymentItemRepository.saveAll(anyList())).thenReturn(List.of(itemA));
        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));

        assertThrows(RuntimeException.class,
            () -> writer.saveTx3(req, payment, List.of(1L)));
    }
}
```

- [ ] **Step 2: CancelTxWriter 구현 수정**

```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.domain.service.CancelItemCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * TX 경계 전담 클래스.
 *
 * TX1: CancelRequest PENDING INSERT
 * TX2: CancelRequest PROCESSING UPDATE
 * TX3: PaymentItem + Payment + CancelRequest(COMPLETED) + Kafka 직접 발행
 *      발행 실패 시 예외 throw → TX3 롤백 → CancelRequest PROCESSING 유지
 *      → processing-recovery 스케줄러가 재처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelTxWriter {

    private final CancelRequestRepository cancelRequestRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CancelDomainService cancelDomainService;

    @Value("${kafka.topic.payment-cancelled}")
    private String topic;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CancelRequest saveTx1(CancelRequest cancelRequest) {
        return cancelRequestRepository.save(cancelRequest);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CancelRequest saveTx2(CancelRequest cancelRequest) {
        cancelRequest.toProcessing();
        return cancelRequestRepository.save(cancelRequest);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CancelRequest saveTx3(
        CancelRequest cancelRequest, Payment payment, List<Long> targetItemIds
    ) {
        List<PaymentItem> freshItems =
            paymentItemRepository.findAllByPaymentIdForUpdate(payment.getId());

        List<CancelItemCommand> commands = targetItemIds.stream()
            .map(CancelItemCommand::of)
            .toList();

        cancelDomainService.apply(payment, commands, freshItems);
        paymentItemRepository.saveAll(freshItems);
        paymentRepository.save(payment);

        cancelRequest.toCompleted();
        cancelRequest = cancelRequestRepository.save(cancelRequest);

        // TX3 마지막: Kafka 직접 발행
        // 실패 시 예외 발생 → @Transactional 롤백 → CancelRequest PROCESSING 유지
        String payload = buildPayload(cancelRequest, payment, freshItems, targetItemIds);
        try {
            kafkaTemplate.send(topic, String.valueOf(cancelRequest.getId()), payload)
                .get(5, TimeUnit.SECONDS);
            log.debug("[kafka] TX3 발행 완료. cancelRequestId={}", cancelRequest.getId());
        } catch (Exception e) {
            log.error("[kafka] TX3 발행 실패 → TX3 롤백. cancelRequestId={}", cancelRequest.getId(), e);
            throw new RuntimeException(
                "[kafka] TX3 Kafka 발행 실패. cancelRequestId=" + cancelRequest.getId(), e);
        }

        return cancelRequest;
    }

    private String buildPayload(
        CancelRequest cancelRequest, Payment payment,
        List<PaymentItem> freshItems, List<Long> targetItemIds
    ) {
        String itemsJson = freshItems.stream()
            .filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED
                && targetItemIds.contains(i.getId()))
            .map(i -> String.format(
                "{\"paymentItemId\":%d,\"orderItemId\":%d,\"itemAmount\":%s}",
                i.getId(), i.getOrderItemId(), i.getItemAmount().toPlainString()
            ))
            .collect(Collectors.joining(",", "[", "]"));

        Instant cancelledAt = cancelRequest.getCompletedAt() != null
            ? cancelRequest.getCompletedAt() : Instant.now();

        return String.format(
            "{\"cancelRequestId\":%d,\"paymentKey\":\"%s\",\"merchantId\":%d," +
            "\"cancelledItems\":%s,\"cancelledAt\":\"%s\"}",
            cancelRequest.getId(),
            payment.getPaymentKey(),
            payment.getMerchantId(),
            itemsJson,
            cancelledAt
        );
    }
}
```

- [ ] **Step 3: 테스트 실행**

```bash
./gradlew :payment-service:test --tests "*CancelTxWriterTest*"
```

Expected: 4 tests passed

---

## Task 3: 불필요 파일 삭제

- [ ] **Step 1: 소스 파일 삭제**

```bash
rm payment-service/src/main/java/com/example/payment/application/event/CancelCompletedEvent.java
rm payment-service/src/main/java/com/example/payment/infrastructure/messaging/CancelEventPublisher.java
rm payment-service/src/main/java/com/example/payment/application/service/FailedKafkaPublisherService.java
rm payment-service/src/main/java/com/example/payment/infrastructure/scheduler/FailedKafkaPublisherScheduler.java
rm payment-service/src/main/java/com/example/payment/application/interfaces/FailedKafkaEventRepository.java
rm payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventJpaEntity.java
rm payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventJpaRepository.java
rm payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventRepositoryImpl.java
```

- [ ] **Step 2: 테스트 파일 삭제**

```bash
rm payment-service/src/test/java/com/example/payment/infrastructure/messaging/CancelEventPublisherTest.java
rm payment-service/src/test/java/com/example/payment/application/service/FailedKafkaPublisherServiceTest.java
rm payment-service/src/test/java/com/example/payment/infrastructure/scheduler/FailedKafkaPublisherSchedulerTest.java
```

- [ ] **Step 3: 전체 테스트**

```bash
./gradlew :payment-service:test
```

Expected: BUILD SUCCESSFUL

---

## Task 4: application.yml + 문서 업데이트

- [ ] **Step 1: application.yml 수정** — `failed-kafka-publisher` 락 키 제거

- [ ] **Step 2: kafka-design.md Section 5 업데이트** — TX3 인라인 방식으로 교체

- [ ] **Step 3: CLAUDE.md 업데이트** — 스케줄러 3개로 수정, failed-kafka-retry 제거

---

## Task 5: 커밋 + PR

- [ ] **Step 1: v3 브랜치 생성 + 커밋**

```bash
git checkout -b v3
git add -A
git commit -m "feat(payment): TX3 인라인 Kafka 발행 전환 + AFTER_COMMIT/failed_kafka_event 제거"
```

- [ ] **Step 2: PR 생성**

```bash
git push -u origin v3
gh pr create --base v2 --head v3 --title "feat(payment): TX3 인라인 Kafka 발행 전환" --body "..."
```
