# Simplified Messaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Outbox Pattern을 `@TransactionalEventListener(AFTER_COMMIT) + failed_kafka_event`로 교체하고, `merchant.limit.updated` 페이로드를 `{ merchantId }`로 단순화한다.

**Architecture:**
- payment-service: TX3 내 `ApplicationEventPublisher.publishEvent()` → AFTER_COMMIT 리스너가 Kafka 발행 시도 → 실패 시 `failed_kafka_event` 테이블에 기록 → `failed-kafka-publisher` 스케줄러가 재시도.
- risk-management-service: `{ merchantId }`만 수신 후 `MerchantLimitClient.fetchDailyLimit(merchantId, kstToday)` 조회 → Redis·DB 갱신.
- `cancel_event_outbox` 테이블·관련 클래스 전체 삭제. `outbox-publisher` 스케줄러 삭제.

**Tech Stack:** Spring Boot 3, Spring Kafka, `@TransactionalEventListener`, Redisson 분산락, Flyway V10 마이그레이션

---

## 파일 맵

### 삭제 (payment-service)
| 파일 | 이유 |
|------|------|
| `application/interfaces/CancelEventOutboxRepository.java` | Outbox 제거 |
| `application/interfaces/CancelEventOutboxManager.java` | 미사용 인터페이스 |
| `application/interfaces/PendingOutbox.java` | Outbox 제거 |
| `application/interfaces/OutboxEventPublisher.java` | Outbox 제거 |
| `application/service/OutboxPublisherService.java` | Outbox 제거 |
| `infrastructure/messaging/KafkaOutboxPublisher.java` | Outbox 제거 |
| `infrastructure/persistence/CancelEventOutboxJpaEntity.java` | Outbox 제거 |
| `infrastructure/persistence/CancelEventOutboxJpaRepository.java` | Outbox 제거 |
| `infrastructure/persistence/CancelEventOutboxRepositoryImpl.java` | Outbox 제거 |
| `infrastructure/scheduler/OutboxPublisherScheduler.java` | Outbox 제거 |
| 테스트: `OutboxPublisherServiceTest.java` | |
| 테스트: `KafkaOutboxPublisherTest.java` | |
| 테스트: `OutboxPublisherSchedulerTest.java` | |

### 추가 (payment-service)
| 파일 | 역할 |
|------|------|
| `application/event/CancelCompletedEvent.java` | TX3에서 발행하는 Spring 애플리케이션 이벤트 |
| `application/interfaces/FailedKafkaEventRepository.java` | 실패 이벤트 저장소 인터페이스 |
| `infrastructure/persistence/FailedKafkaEventJpaEntity.java` | failed_kafka_event 엔티티 |
| `infrastructure/persistence/FailedKafkaEventJpaRepository.java` | Spring Data JPA 레포지토리 |
| `infrastructure/persistence/FailedKafkaEventRepositoryImpl.java` | 인터페이스 구현체 |
| `infrastructure/messaging/CancelEventPublisher.java` | @TransactionalEventListener(AFTER_COMMIT) 리스너 |
| `application/service/FailedKafkaPublisherService.java` | 재시도 서비스 |
| `infrastructure/scheduler/FailedKafkaPublisherScheduler.java` | 30초 스케줄러 |
| `db/migration/V10__replace_outbox_with_failed_kafka_event.sql` | DDL 마이그레이션 |

### 수정 (payment-service)
| 파일 | 변경 내용 |
|------|---------|
| `application/service/CancelTxWriter.java` | `outboxRepository` → `ApplicationEventPublisher` |
| `resources/application.yml` | `outbox-publisher` 락 키 → `failed-kafka-publisher` |
| 테스트: `CancelTxWriterTest.java` | outboxRepository Mock 제거, applicationEventPublisher Mock 추가 |

### 수정 (risk-management-service)
| 파일 | 변경 내용 |
|------|---------|
| `infrastructure/messaging/MerchantLimitUpdatedPayload.java` | `record(long merchantId)`로 단순화 |
| `infrastructure/messaging/MerchantLimitUpdatedConsumer.java` | `MerchantLimitClient` 주입, API 조회 후 Redis·DB 갱신 |
| 테스트: `MerchantLimitUpdatedConsumerTest.java` | 페이로드 변경, `MerchantLimitClient` Mock 추가 |

---

## Task 1: Flyway V10 마이그레이션 작성

**Files:**
- Create: `payment-service/src/main/resources/db/migration/V10__replace_outbox_with_failed_kafka_event.sql`

- [ ] **Step 1: SQL 파일 작성**

```sql
-- V10__replace_outbox_with_failed_kafka_event.sql
-- cancel_event_outbox 제거 → failed_kafka_event 추가

DROP TABLE IF EXISTS cancel_event_outbox;

CREATE TABLE failed_kafka_event
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    cancel_request_id BIGINT       NOT NULL,
    topic             VARCHAR(100) NOT NULL,
    payload           JSON         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(500) NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_failed_kafka_cancel_request_id (cancel_request_id),
    INDEX idx_failed_kafka_status (status),
    INDEX idx_failed_kafka_status_created (status, created_at)
);
```

- [ ] **Step 2: 컴파일 확인 (빌드 오류 없으면 OK)**

```bash
./gradlew :payment-service:compileJava
```

Expected: BUILD SUCCESSFUL

---

## Task 2: CancelCompletedEvent 정의

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/event/CancelCompletedEvent.java`

- [ ] **Step 1: 이벤트 레코드 작성**

```java
package com.example.payment.application.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * TX3 커밋 후 Kafka 발행 트리거.
 * CancelTxWriter.saveTx3()에서 ApplicationEventPublisher로 발행.
 * CancelEventPublisher가 AFTER_COMMIT으로 수신.
 */
public record CancelCompletedEvent(
    long cancelRequestId,
    String paymentKey,
    long merchantId,
    Instant cancelledAt,
    List<CancelledItemData> cancelledItems
) {
    public record CancelledItemData(
        long paymentItemId,
        long orderItemId,
        BigDecimal itemAmount
    ) {}
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :payment-service:compileJava
```

Expected: BUILD SUCCESSFUL

---

## Task 3: FailedKafkaEventRepository 인터페이스 + JPA 구현체

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/FailedKafkaEventRepository.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventJpaEntity.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventJpaRepository.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventRepositoryImpl.java`

- [ ] **Step 1: 인터페이스 작성**

```java
package com.example.payment.application.interfaces;

import java.util.List;

public interface FailedKafkaEventRepository {

    /** AFTER_COMMIT 리스너 실패 시 신규 기록 (UK 중복 방어). */
    void saveIfAbsent(long cancelRequestId, String topic, String payload);

    boolean existsByCancelRequestId(long cancelRequestId);

    /** 스케줄러용: PENDING 건 오래된 순, 최대 limit개. */
    List<PendingFailedEvent> findPendingBatch(int limit);

    void markPublished(long cancelRequestId);
    void incrementRetry(long cancelRequestId, String error);
    void markExhausted(long cancelRequestId, String error);

    record PendingFailedEvent(long cancelRequestId, String topic, String payload, int retryCount) {}
}
```

- [ ] **Step 2: JPA 엔티티 작성**

```java
package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "failed_kafka_event",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_failed_kafka_cancel_request_id",
        columnNames = "cancel_request_id"
    )
)
public class FailedKafkaEventJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false)
    private Long cancelRequestId;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FailedKafkaEventJpaEntity() {}

    public static FailedKafkaEventJpaEntity pending(long cancelRequestId, String topic, String payload) {
        var e = new FailedKafkaEventJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.topic = topic;
        e.payload = payload;
        e.status = "PENDING";
        e.retryCount = 0;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    public Long getCancelRequestId() { return cancelRequestId; }
    public String getTopic()          { return topic; }
    public String getPayload()        { return payload; }
    public String getStatus()         { return status; }
    public int    getRetryCount()     { return retryCount; }
}
```

- [ ] **Step 3: JPA 레포지토리 작성**

```java
package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface FailedKafkaEventJpaRepository
    extends JpaRepository<FailedKafkaEventJpaEntity, Long> {

    boolean existsByCancelRequestId(long cancelRequestId);

    @Query(value = "SELECT e FROM FailedKafkaEventJpaEntity e " +
                   "WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC LIMIT :limit")
    List<FailedKafkaEventJpaEntity> findPendingBatch(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE FailedKafkaEventJpaEntity e " +
           "SET e.status = 'PUBLISHED', e.updatedAt = :now WHERE e.cancelRequestId = :id")
    void markPublished(@Param("id") long cancelRequestId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FailedKafkaEventJpaEntity e " +
           "SET e.retryCount = e.retryCount + 1, e.lastError = :error, e.updatedAt = :now " +
           "WHERE e.cancelRequestId = :id")
    void incrementRetry(@Param("id") long cancelRequestId,
                        @Param("error") String error,
                        @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FailedKafkaEventJpaEntity e " +
           "SET e.status = 'EXHAUSTED', e.lastError = :error, e.updatedAt = :now " +
           "WHERE e.cancelRequestId = :id")
    void markExhausted(@Param("id") long cancelRequestId,
                       @Param("error") String error,
                       @Param("now") Instant now);
}
```

- [ ] **Step 4: 구현체 작성**

```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class FailedKafkaEventRepositoryImpl implements FailedKafkaEventRepository {

    private final FailedKafkaEventJpaRepository jpa;

    @Override
    @Transactional
    public void saveIfAbsent(long cancelRequestId, String topic, String payload) {
        if (jpa.existsByCancelRequestId(cancelRequestId)) return;
        jpa.save(FailedKafkaEventJpaEntity.pending(cancelRequestId, topic, payload));
    }

    @Override
    public boolean existsByCancelRequestId(long cancelRequestId) {
        return jpa.existsByCancelRequestId(cancelRequestId);
    }

    @Override
    public List<PendingFailedEvent> findPendingBatch(int limit) {
        return jpa.findPendingBatch(limit).stream()
            .map(e -> new PendingFailedEvent(
                e.getCancelRequestId(), e.getTopic(), e.getPayload(), e.getRetryCount()))
            .toList();
    }

    @Override
    @Transactional
    public void markPublished(long cancelRequestId) {
        jpa.markPublished(cancelRequestId, Instant.now());
    }

    @Override
    @Transactional
    public void incrementRetry(long cancelRequestId, String error) {
        jpa.incrementRetry(cancelRequestId, error, Instant.now());
    }

    @Override
    @Transactional
    public void markExhausted(long cancelRequestId, String error) {
        jpa.markExhausted(cancelRequestId, error, Instant.now());
    }
}
```

- [ ] **Step 5: 컴파일 확인**

```bash
./gradlew :payment-service:compileJava
```

Expected: BUILD SUCCESSFUL

---

## Task 4: CancelEventPublisher (AFTER_COMMIT 리스너) — TDD

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/CancelEventPublisher.java`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/messaging/CancelEventPublisherTest.java`

- [ ] **Step 1: 테스트 작성**

```java
package com.example.payment.infrastructure.messaging;

import com.example.payment.application.event.CancelCompletedEvent;
import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelEventPublisher")
class CancelEventPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock FailedKafkaEventRepository failedKafkaEventRepository;

    CancelEventPublisher publisher;

    private static final String TOPIC = "payment.cancelled";

    @BeforeEach
    void setUp() {
        publisher = new CancelEventPublisher(kafkaTemplate, failedKafkaEventRepository, TOPIC);
    }

    private CancelCompletedEvent event() {
        return new CancelCompletedEvent(
            1L, "pay_abc", 100L, Instant.parse("2026-04-28T10:00:00Z"),
            List.of(new CancelCompletedEvent.CancelledItemData(10L, 20L, BigDecimal.valueOf(30000)))
        );
    }

    @Test
    @DisplayName("Kafka 발행 성공 시 failed_kafka_event 저장 없음")
    void publish_success_no_failed_event() throws Exception {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.onCancelCompleted(event());

        verify(kafkaTemplate).send(eq(TOPIC), eq("1"), anyString());
        verifyNoInteractions(failedKafkaEventRepository);
    }

    @Test
    @DisplayName("Kafka 발행 실패 시 failed_kafka_event INSERT")
    void publish_failure_saves_failed_event() throws Exception {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));
        when(failedKafkaEventRepository.existsByCancelRequestId(1L)).thenReturn(false);

        publisher.onCancelCompleted(event());

        verify(failedKafkaEventRepository).saveIfAbsent(eq(1L), eq(TOPIC), anyString());
    }

    @Test
    @DisplayName("Kafka 발행 실패 + 이미 failed_event 존재 시 중복 저장 없음")
    void publish_failure_already_exists_no_duplicate() throws Exception {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));
        when(failedKafkaEventRepository.existsByCancelRequestId(1L)).thenReturn(true);

        publisher.onCancelCompleted(event());

        verify(failedKafkaEventRepository, never()).saveIfAbsent(anyLong(), anyString(), anyString());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :payment-service:test --tests "*CancelEventPublisherTest*"
```

Expected: FAILED (CancelEventPublisher 미존재)

- [ ] **Step 3: CancelEventPublisher 구현**

```java
package com.example.payment.infrastructure.messaging;

import com.example.payment.application.event.CancelCompletedEvent;
import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CancelEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FailedKafkaEventRepository failedKafkaEventRepository;
    private final String topic;

    public CancelEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        FailedKafkaEventRepository failedKafkaEventRepository,
        @Value("${kafka.topic.payment-cancelled}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.failedKafkaEventRepository = failedKafkaEventRepository;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelCompleted(CancelCompletedEvent event) {
        String payload = buildPayload(event);
        try {
            kafkaTemplate.send(topic, String.valueOf(event.cancelRequestId()), payload)
                .get(5, TimeUnit.SECONDS);
            log.debug("[kafka] 발행 완료. cancelRequestId={}", event.cancelRequestId());
        } catch (Exception e) {
            log.error("[kafka] 발행 실패 → failed_kafka_event INSERT. cancelRequestId={}",
                event.cancelRequestId(), e);
            if (!failedKafkaEventRepository.existsByCancelRequestId(event.cancelRequestId())) {
                failedKafkaEventRepository.saveIfAbsent(event.cancelRequestId(), topic, payload);
            }
        }
    }

    private String buildPayload(CancelCompletedEvent event) {
        String itemsJson = event.cancelledItems().stream()
            .map(i -> String.format(
                "{\"paymentItemId\":%d,\"orderItemId\":%d,\"itemAmount\":%s}",
                i.paymentItemId(), i.orderItemId(), i.itemAmount().toPlainString()
            ))
            .collect(Collectors.joining(",", "[", "]"));

        return String.format(
            "{\"cancelRequestId\":%d,\"paymentKey\":\"%s\",\"merchantId\":%d," +
            "\"cancelledItems\":%s,\"cancelledAt\":\"%s\"}",
            event.cancelRequestId(),
            event.paymentKey(),
            event.merchantId(),
            itemsJson,
            event.cancelledAt()
        );
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :payment-service:test --tests "*CancelEventPublisherTest*"
```

Expected: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 5: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/event/CancelCompletedEvent.java \
        payment-service/src/main/java/com/example/payment/application/interfaces/FailedKafkaEventRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventJpaEntity.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventJpaRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/FailedKafkaEventRepositoryImpl.java \
        payment-service/src/main/java/com/example/payment/infrastructure/messaging/CancelEventPublisher.java \
        payment-service/src/test/java/com/example/payment/infrastructure/messaging/CancelEventPublisherTest.java \
        payment-service/src/main/resources/db/migration/V10__replace_outbox_with_failed_kafka_event.sql
git commit -m "feat(payment): AFTER_COMMIT 기반 CancelEventPublisher + failed_kafka_event 저장소 추가"
```

---

## Task 5: FailedKafkaPublisherService — TDD

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/service/FailedKafkaPublisherService.java`
- Create: `payment-service/src/test/java/com/example/payment/application/service/FailedKafkaPublisherServiceTest.java`

- [ ] **Step 1: 테스트 작성**

```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import com.example.payment.application.interfaces.FailedKafkaEventRepository.PendingFailedEvent;
import com.example.payment.application.interfaces.OperationAlertPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FailedKafkaPublisherService")
class FailedKafkaPublisherServiceTest {

    @Mock FailedKafkaEventRepository repo;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock OperationAlertPort alertPort;

    FailedKafkaPublisherService service;

    @BeforeEach
    void setUp() {
        service = new FailedKafkaPublisherService(repo, kafkaTemplate, alertPort);
    }

    private PendingFailedEvent pendingEvent(long id, int retryCount) {
        return new PendingFailedEvent(id, "payment.cancelled", "{\"cancelRequestId\":" + id + "}", retryCount);
    }

    @Test
    @DisplayName("PENDING 건 Kafka 재발행 성공 → markPublished 호출")
    void retry_success_marks_published() throws Exception {
        when(repo.findPendingBatch(100)).thenReturn(List.of(pendingEvent(1L, 1)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        service.publish();

        verify(repo).markPublished(1L);
        verify(repo, never()).incrementRetry(anyLong(), anyString());
    }

    @Test
    @DisplayName("Kafka 재발행 실패, retryCount < 5 → incrementRetry 호출")
    void retry_failure_increments_retry() throws Exception {
        when(repo.findPendingBatch(100)).thenReturn(List.of(pendingEvent(1L, 3)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        service.publish();

        verify(repo).incrementRetry(eq(1L), anyString());
        verify(repo, never()).markExhausted(anyLong(), anyString());
    }

    @Test
    @DisplayName("retryCount 4 (5번째 실패) → EXHAUSTED + 운영 알림")
    void retry_failure_at_max_marks_exhausted_and_alerts() throws Exception {
        when(repo.findPendingBatch(100)).thenReturn(List.of(pendingEvent(1L, 4)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        service.publish();

        verify(repo).markExhausted(eq(1L), anyString());
        verify(alertPort).alert(anyString());
        verify(repo, never()).incrementRetry(anyLong(), anyString());
    }

    @Test
    @DisplayName("PENDING 없으면 아무 동작 없음")
    void no_pending_no_op() {
        when(repo.findPendingBatch(100)).thenReturn(List.of());

        service.publish();

        verifyNoMoreInteractions(kafkaTemplate, alertPort);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :payment-service:test --tests "*FailedKafkaPublisherServiceTest*"
```

Expected: FAILED

- [ ] **Step 3: 서비스 구현**

```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import com.example.payment.application.interfaces.FailedKafkaEventRepository.PendingFailedEvent;
import com.example.payment.application.interfaces.OperationAlertPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FailedKafkaPublisherService {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 5;

    private final FailedKafkaEventRepository repo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OperationAlertPort alertPort;

    public void publish() {
        List<PendingFailedEvent> pending = repo.findPendingBatch(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.info("[failed-kafka-publisher] 재시도 대상 {}건", pending.size());

        for (PendingFailedEvent event : pending) {
            try {
                kafkaTemplate.send(event.topic(), String.valueOf(event.cancelRequestId()), event.payload())
                    .get(5, TimeUnit.SECONDS);
                repo.markPublished(event.cancelRequestId());
                log.debug("[failed-kafka-publisher] 재발행 성공. cancelRequestId={}", event.cancelRequestId());
            } catch (Exception e) {
                int nextRetry = event.retryCount() + 1;
                String error = e.getMessage();
                if (nextRetry >= MAX_RETRIES) {
                    repo.markExhausted(event.cancelRequestId(), error);
                    alertPort.alert(String.format(
                        "[failed-kafka-publisher] EXHAUSTED. cancelRequestId=%d, error=%s",
                        event.cancelRequestId(), error));
                    log.error("[failed-kafka-publisher] EXHAUSTED. cancelRequestId={}", event.cancelRequestId(), e);
                } else {
                    repo.incrementRetry(event.cancelRequestId(), error);
                    log.warn("[failed-kafka-publisher] 재발행 실패({}/{}). cancelRequestId={}",
                        nextRetry, MAX_RETRIES, event.cancelRequestId());
                }
            }
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :payment-service:test --tests "*FailedKafkaPublisherServiceTest*"
```

Expected: 4 tests passed

---

## Task 6: FailedKafkaPublisherScheduler — TDD

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/FailedKafkaPublisherScheduler.java`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/FailedKafkaPublisherSchedulerTest.java`

- [ ] **Step 1: 테스트 작성**

```java
package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.FailedKafkaPublisherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FailedKafkaPublisherScheduler")
class FailedKafkaPublisherSchedulerTest {

    @Mock RedissonClient redissonClient;
    @Mock FailedKafkaPublisherService service;
    @Mock RLock lock;

    @InjectMocks
    FailedKafkaPublisherScheduler scheduler;

    @Test
    @DisplayName("락 획득 성공 시 service.publish() 호출")
    void run_acquires_lock_and_publishes() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 25, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        scheduler.setLockKey("lock:scheduler:failed-kafka-publisher");

        scheduler.run();

        verify(service).publish();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("락 획득 실패 시 service.publish() 미호출")
    void run_skips_when_lock_unavailable() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 25, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);
        scheduler.setLockKey("lock:scheduler:failed-kafka-publisher");

        scheduler.run();

        verifyNoInteractions(service);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :payment-service:test --tests "*FailedKafkaPublisherSchedulerTest*"
```

Expected: FAILED

- [ ] **Step 3: 스케줄러 구현**

```java
package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.FailedKafkaPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class FailedKafkaPublisherScheduler {

    private final RedissonClient redissonClient;
    private final FailedKafkaPublisherService failedKafkaPublisherService;

    @Setter
    @Value("${scheduler.lock.failed-kafka-publisher}")
    private String lockKey;

    @Scheduled(fixedDelay = 30_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 25, TimeUnit.SECONDS)) {
                log.debug("[failed-kafka-publisher] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            failedKafkaPublisherService.publish();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :payment-service:test --tests "*FailedKafkaPublisherSchedulerTest*"
```

Expected: 2 tests passed

---

## Task 7: CancelTxWriter 수정 + 기존 Outbox 삭제

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelTxWriter.java`
- Modify: `payment-service/src/test/java/com/example/payment/application/service/CancelTxWriterTest.java`
- Delete 10 Outbox 관련 파일

- [ ] **Step 1: CancelTxWriterTest 수정 (outboxRepository → applicationEventPublisher)**

```java
// 변경 전: @Mock CancelEventOutboxRepository outboxRepository;
// 변경 후:
@Mock ApplicationEventPublisher applicationEventPublisher;

// setUp() 변경:
writer = new CancelTxWriter(
    cancelRequestRepository, paymentItemRepository, paymentRepository,
    applicationEventPublisher, domainService
);

// saveTx3 테스트 — verify 변경:
// 변경 전: verify(outboxRepository).insertIfAbsent(any(), any(), anyList());
// 변경 후:
verify(applicationEventPublisher).publishEvent(any(CancelCompletedEvent.class));
```

전체 수정된 `CancelTxWriterTest.java`:

```java
package com.example.payment.application.service;

import com.example.payment.application.event.CancelCompletedEvent;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelTxWriter")
class CancelTxWriterTest {

    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock PaymentItemRepository paymentItemRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock ApplicationEventPublisher applicationEventPublisher;

    private CancelTxWriter writer;
    private Payment payment;
    private PaymentItem itemA;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        CancelDomainService domainService = new CancelDomainService(new CancelPeriodPolicy(clock));
        writer = new CancelTxWriter(
            cancelRequestRepository, paymentItemRepository, paymentRepository,
            applicationEventPublisher, domainService
        );
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
    @DisplayName("saveTx3: FOR UPDATE 재조회 후 COMPLETED 저장 + CancelCompletedEvent 발행")
    void saveTx3_reloadsItemsForUpdateAndPublishesEvent() {
        CancelRequest req = CancelRequest.reconstruct(1L, payment.getId(), "hash-001",
            BigDecimal.valueOf(30000), "고객 변심", List.of(1L), CancelStatus.PROCESSING,
            0, null, null, Instant.now(), Instant.now());

        when(paymentItemRepository.findAllByPaymentIdForUpdate(payment.getId()))
            .thenReturn(List.of(itemA));
        when(paymentItemRepository.saveAll(anyList())).thenReturn(List.of(itemA));
        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelRequest result = writer.saveTx3(req, payment, List.of(1L));

        assertEquals(CancelStatus.COMPLETED, result.getStatus());
        verify(paymentItemRepository).findAllByPaymentIdForUpdate(payment.getId());
        verify(applicationEventPublisher).publishEvent(any(CancelCompletedEvent.class));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :payment-service:test --tests "*CancelTxWriterTest*"
```

Expected: FAILED (CancelTxWriter 시그니처 불일치)

- [ ] **Step 3: CancelTxWriter 수정**

```java
package com.example.payment.application.service;

import com.example.payment.application.event.CancelCompletedEvent;
import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.domain.service.CancelItemCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * TX 경계 전담 클래스.
 *
 * TX1: CancelRequest PENDING INSERT
 * TX2: CancelRequest PROCESSING UPDATE
 * TX3: PaymentItem + Payment + CancelRequest(COMPLETED) + ApplicationEvent 발행
 *      → AFTER_COMMIT 리스너(CancelEventPublisher)가 Kafka 발행
 */
@Service
@RequiredArgsConstructor
public class CancelTxWriter {

    private final CancelRequestRepository cancelRequestRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final CancelDomainService cancelDomainService;

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

        // Outbox 대신 ApplicationEvent 발행 → AFTER_COMMIT 리스너가 Kafka 발행
        List<CancelCompletedEvent.CancelledItemData> eventItems = freshItems.stream()
            .filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED
                && targetItemIds.contains(i.getId()))
            .map(i -> new CancelCompletedEvent.CancelledItemData(
                i.getId(), i.getOrderItemId(), i.getItemAmount()))
            .toList();

        applicationEventPublisher.publishEvent(new CancelCompletedEvent(
            cancelRequest.getId(),
            payment.getPaymentKey(),
            payment.getMerchantId(),
            cancelRequest.getCompletedAt() != null ? cancelRequest.getCompletedAt() : Instant.now(),
            eventItems
        ));

        return cancelRequest;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :payment-service:test --tests "*CancelTxWriterTest*"
```

Expected: 3 tests passed

- [ ] **Step 5: Outbox 관련 파일 삭제**

```bash
# interfaces
rm payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxRepository.java
rm payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxManager.java
rm payment-service/src/main/java/com/example/payment/application/interfaces/PendingOutbox.java
rm payment-service/src/main/java/com/example/payment/application/interfaces/OutboxEventPublisher.java
# services
rm payment-service/src/main/java/com/example/payment/application/service/OutboxPublisherService.java
# infrastructure
rm payment-service/src/main/java/com/example/payment/infrastructure/messaging/KafkaOutboxPublisher.java
rm payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaEntity.java
rm payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaRepository.java
rm payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java
rm payment-service/src/main/java/com/example/payment/infrastructure/scheduler/OutboxPublisherScheduler.java
# tests
rm payment-service/src/test/java/com/example/payment/application/service/OutboxPublisherServiceTest.java
rm payment-service/src/test/java/com/example/payment/infrastructure/messaging/KafkaOutboxPublisherTest.java
rm payment-service/src/test/java/com/example/payment/infrastructure/scheduler/OutboxPublisherSchedulerTest.java
```

- [ ] **Step 6: payment-service 전체 테스트 통과 확인**

```bash
./gradlew :payment-service:test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: application.yml 수정**

```yaml
# 변경 전:
scheduler:
  lock:
    outbox-publisher: lock:scheduler:outbox-publisher
    pending-recovery: lock:scheduler:pending-recovery
    processing-recovery: lock:scheduler:processing-recovery
    compensation-retry: lock:scheduler:compensation-retry

# 변경 후:
scheduler:
  lock:
    failed-kafka-publisher: lock:scheduler:failed-kafka-publisher
    pending-recovery: lock:scheduler:pending-recovery
    processing-recovery: lock:scheduler:processing-recovery
    compensation-retry: lock:scheduler:compensation-retry
```

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat(payment): CancelTxWriter AFTER_COMMIT 전환 + Outbox 관련 파일 전체 삭제"
```

---

## Task 8: risk-management-service — 페이로드 단순화 + Consumer 수정

**Files:**
- Modify: `risk-management-service/src/main/java/com/example/riskmanagement/infrastructure/messaging/MerchantLimitUpdatedPayload.java`
- Modify: `risk-management-service/src/main/java/com/example/riskmanagement/infrastructure/messaging/MerchantLimitUpdatedConsumer.java`
- Modify: `risk-management-service/src/test/java/com/example/riskmanagement/infrastructure/messaging/MerchantLimitUpdatedConsumerTest.java`

- [ ] **Step 1: 테스트 수정 (MerchantLimitClient Mock 추가)**

`MerchantLimitUpdatedConsumerTest.java` 전체 교체:

```java
package com.example.riskmanagement.infrastructure.messaging;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.example.riskmanagement.application.interfaces.MerchantLimitClient;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MerchantLimitUpdatedConsumer")
class MerchantLimitUpdatedConsumerTest {

    @Mock DailyLimitCache dailyLimitCache;
    @Mock MerchantCancelUsageRepository usageRepository;
    @Mock MerchantLimitClient merchantLimitClient;
    @Mock TransactionTemplate transactionTemplate;
    @Mock Acknowledgment ack;
    @Mock MerchantCancelUsage usage;

    MerchantLimitUpdatedConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final long MERCHANT_ID = 1L;
    private static final BigDecimal FETCHED_LIMIT = new BigDecimal("5000000");

    @BeforeEach
    void setUp() {
        consumer = new MerchantLimitUpdatedConsumer(
            dailyLimitCache, usageRepository, merchantLimitClient, objectMapper, transactionTemplate);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("merchant.limit.updated", 0, 0L,
            String.valueOf(MERCHANT_ID), value);
    }

    @Test
    @DisplayName("정상 처리 — API 조회 후 Redis 갱신 + DB usage 존재 시 update + ack")
    void consume_success_with_existing_usage() {
        when(merchantLimitClient.fetchDailyLimit(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenReturn(FETCHED_LIMIT);
        when(usageRepository.findByMerchantIdAndKstDate(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenReturn(Optional.of(usage));
        when(usageRepository.save(usage)).thenReturn(usage);

        consumer.consume(record("{\"merchantId\":1}"), ack);

        verify(merchantLimitClient).fetchDailyLimit(eq(MERCHANT_ID), any(LocalDate.class));
        verify(dailyLimitCache).set(eq(MERCHANT_ID), any(LocalDate.class), eq(FETCHED_LIMIT));
        verify(usage).updateDailyLimit(FETCHED_LIMIT);
        verify(usageRepository).save(usage);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("정상 처리 — usage 없으면 Redis만 갱신 + ack")
    void consume_success_without_existing_usage() {
        when(merchantLimitClient.fetchDailyLimit(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenReturn(FETCHED_LIMIT);
        when(usageRepository.findByMerchantIdAndKstDate(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenReturn(Optional.empty());

        consumer.consume(record("{\"merchantId\":1}"), ack);

        verify(dailyLimitCache).set(eq(MERCHANT_ID), any(LocalDate.class), eq(FETCHED_LIMIT));
        verify(usageRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("JSON 파싱 실패 — ack (멱등)")
    void consume_invalid_json_acks() {
        consumer.consume(record("NOT_JSON"), ack);

        verifyNoInteractions(merchantLimitClient, dailyLimitCache);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("API 조회 실패 — ack (3순위 HTTP fallback 보장)")
    void consume_api_failure_acks() {
        when(merchantLimitClient.fetchDailyLimit(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenThrow(new RuntimeException("API error"));

        consumer.consume(record("{\"merchantId\":1}"), ack);

        verifyNoInteractions(dailyLimitCache);
        verify(ack).acknowledge();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :risk-management-service:test --tests "*MerchantLimitUpdatedConsumerTest*"
```

Expected: FAILED

- [ ] **Step 3: 페이로드 단순화**

```java
// MerchantLimitUpdatedPayload.java 전체 교체
package com.example.riskmanagement.infrastructure.messaging;

public record MerchantLimitUpdatedPayload(long merchantId) {}
```

- [ ] **Step 4: Consumer 수정**

```java
package com.example.riskmanagement.infrastructure.messaging;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.example.riskmanagement.application.interfaces.MerchantLimitClient;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantLimitUpdatedConsumer {

    private final DailyLimitCache dailyLimitCache;
    private final MerchantCancelUsageRepository usageRepository;
    private final MerchantLimitClient merchantLimitClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(
        topics = "${kafka.topic.merchant-limit-updated}",
        groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            MerchantLimitUpdatedPayload payload =
                objectMapper.readValue(record.value(), MerchantLimitUpdatedPayload.class);

            LocalDate kstToday = LocalDate.now(ZoneId.of("Asia/Seoul"));

            // { merchantId }만 수신 → API로 최신 한도 조회
            BigDecimal newLimit = merchantLimitClient.fetchDailyLimit(payload.merchantId(), kstToday);

            // 1. Redis 갱신 (자연 멱등)
            dailyLimitCache.set(payload.merchantId(), kstToday, newLimit);

            // 2. DB 스냅샷 갱신 (행 있을 때만)
            transactionTemplate.execute(status ->
                usageRepository.findByMerchantIdAndKstDate(payload.merchantId(), kstToday)
                    .map(usage -> {
                        usage.updateDailyLimit(newLimit);
                        return usageRepository.save(usage);
                    })
                    .orElse(null));

            ack.acknowledge();
            log.debug("merchant.limit.updated 처리 완료. merchantId={}, kstDate={}",
                payload.merchantId(), kstToday);

        } catch (Exception e) {
            log.error("merchant.limit.updated 처리 실패. offset={}, value={}",
                record.offset(), record.value(), e);
            ack.acknowledge();
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :risk-management-service:test --tests "*MerchantLimitUpdatedConsumerTest*"
```

Expected: 4 tests passed

- [ ] **Step 6: risk-management-service 전체 테스트**

```bash
./gradlew :risk-management-service:test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add risk-management-service/
git commit -m "feat(risk): merchant.limit.updated 페이로드 { merchantId } 단순화 + API 조회로 전환"
```

---

## Task 9: 최종 검증

- [ ] **Step 1: payment-service 전체 빌드**

```bash
./gradlew :payment-service:build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: risk-management-service 전체 빌드**

```bash
./gradlew :risk-management-service:build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 전체 빌드**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 최종 커밋 (변경사항 있으면)**

```bash
git status
# 변경사항 있으면:
git add -A
git commit -m "chore(simplified-messaging): 전체 빌드 검증 완료"
```
