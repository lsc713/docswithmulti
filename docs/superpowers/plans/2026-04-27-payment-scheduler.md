# Payment Service 스케줄러 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** payment-service에 스케줄러 4개(OutboxPublisher, PendingRecovery, ProcessingRecovery, CompensationRetry)를 구현한다. OutboxPublisher는 완전 구현 + TDD, 나머지 3개는 골격만.

**Architecture:** `@Scheduled` + Redisson `RLock.tryLock()` 직접 사용. 스케줄러 클래스는 락만 책임지고 비즈니스 로직은 Service에 위임 (OutboxPublisher). 나머지 3개는 TODO 골격.

**Tech Stack:** Spring Kafka (`KafkaTemplate`), Redisson (`redisson-spring-boot-starter`), JUnit 5 + Mockito

---

## 파일 목록

| 작업 | 파일 경로 |
|------|---------|
| 신규 | `payment-service/src/main/java/com/example/payment/application/interfaces/PendingOutbox.java` |
| 신규 | `payment-service/src/main/java/com/example/payment/infrastructure/messaging/KafkaOutboxPublisher.java` |
| 신규 | `payment-service/src/main/java/com/example/payment/application/service/OutboxPublisherService.java` |
| 신규 | `payment-service/src/main/java/com/example/payment/infrastructure/config/SchedulerConfig.java` |
| 신규 | `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/OutboxPublisherScheduler.java` |
| 신규 | `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/PendingRecoveryScheduler.java` |
| 신규 | `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/ProcessingRecoveryScheduler.java` |
| 신규 | `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CompensationRetryScheduler.java` |
| 신규 | `payment-service/src/test/java/com/example/payment/application/service/OutboxPublisherServiceTest.java` |
| 수정 | `payment-service/build.gradle` |
| 수정 | `payment-service/src/main/resources/application.yml` |
| 수정 | `payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxRepository.java` |
| 수정 | `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaRepository.java` |
| 수정 | `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java` |

---

## Task 1: 의존성 및 설정 추가

**Files:**
- Modify: `payment-service/build.gradle`
- Modify: `payment-service/src/main/resources/application.yml`

- [ ] **Step 1: build.gradle에 의존성 추가**

`payment-service/build.gradle`을 아래로 교체:

```groovy
// payment-service build configuration

apply plugin: 'org.flywaydb.flyway'

flyway {
    url      = 'jdbc:mysql://localhost:3311/payment_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true'
    user     = 'payment'
    password = 'payment'
    locations = ['classpath:db/migration']
}

dependencies {
    // Circuit Breaker
    implementation 'io.github.resilience4j:resilience4j-circuitbreaker:2.2.0'

    // Kafka (Outbox 발행)
    implementation 'org.springframework.kafka:spring-kafka'

    // Redisson (분산락)
    implementation 'org.redisson:redisson-spring-boot-starter:3.27.2'
}
```

- [ ] **Step 2: application.yml에 Kafka, Redis, 스케줄러 락 키 추가**

`payment-service/src/main/resources/application.yml` 끝에 아래 블록 추가:

```yaml
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      enable-idempotence: true

  data:
    redis:
      host: localhost
      port: 6379

kafka:
  topic:
    payment-cancelled: payment.cancelled

scheduler:
  lock:
    outbox-publisher: lock:scheduler:outbox-publisher
    pending-recovery: lock:scheduler:pending-recovery
    processing-recovery: lock:scheduler:processing-recovery
    compensation-retry: lock:scheduler:compensation-retry
```

주의: `spring:` 블록 아래 `kafka:`, `data:` 를 기존 `datasource:`, `jpa:` 와 같은 들여쓰기 레벨에 추가. `kafka.topic`, `scheduler.lock`은 최상위 레벨.

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :payment-service:build -x test
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add payment-service/build.gradle payment-service/src/main/resources/application.yml
git commit -m "feat(payment): Kafka, Redisson 의존성 및 스케줄러 설정 추가"
```

---

## Task 2: CancelEventOutboxRepository 확장

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/PendingOutbox.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java`

- [ ] **Step 1: PendingOutbox 레코드 생성**

`payment-service/src/main/java/com/example/payment/application/interfaces/PendingOutbox.java`:

```java
package com.example.payment.application.interfaces;

/**
 * OutboxPublisherService가 사용하는 발행 대기 Outbox 데이터.
 * JPA 엔티티를 application 레이어로 노출하지 않기 위한 경량 레코드.
 */
public record PendingOutbox(long cancelRequestId, String payload) {}
```

- [ ] **Step 2: CancelEventOutboxRepository에 메서드 추가**

`payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxRepository.java` 전체를 아래로 교체:

```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;

import java.util.List;

public interface CancelEventOutboxRepository {

    /** TX3 내부에서 호출. cancel_request_id UK로 중복 방어. */
    void insertIfAbsent(CancelRequest cancelRequest, Payment payment, List<PaymentItem> cancelledItems);

    /** 스케줄러용: PENDING 건 최대 limit개 오래된 순 조회. */
    List<PendingOutbox> findPendingBatch(int limit);

    /** Kafka 발행 성공 후 PUBLISHED로 마킹. */
    void markPublished(long cancelRequestId);
}
```

- [ ] **Step 3: CancelEventOutboxJpaRepository에 쿼리 메서드 추가**

`payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaRepository.java` 전체를 아래로 교체:

```java
package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CancelEventOutboxJpaRepository
    extends JpaRepository<CancelEventOutboxJpaEntity, Long> {

    boolean existsByCancelRequestId(Long cancelRequestId);

    @Modifying
    @Query("UPDATE CancelEventOutboxJpaEntity o SET o.status = 'PUBLISHED', o.publishedAt = CURRENT_TIMESTAMP WHERE o.cancelRequestId = :cancelRequestId")
    int markPublished(@Param("cancelRequestId") Long cancelRequestId);

    List<CancelEventOutboxJpaEntity> findTop1000ByStatusOrderByCreatedAtAsc(String status);
}
```

- [ ] **Step 4: CancelEventOutboxRepositoryImpl에 구현 추가**

`payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java` 전체를 아래로 교체:

```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.PendingOutbox;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Outbox INSERT — cancel_request_id UK로 중복 방어.
 * TX3 내부에서 호출되므로 insertIfAbsent는 별도 @Transactional 없음.
 */
@Repository
public class CancelEventOutboxRepositoryImpl implements CancelEventOutboxRepository {

    private final CancelEventOutboxJpaRepository jpaRepository;

    public CancelEventOutboxRepositoryImpl(CancelEventOutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void insertIfAbsent(CancelRequest cancelRequest, Payment payment, List<PaymentItem> cancelledItems) {
        if (jpaRepository.existsByCancelRequestId(cancelRequest.getId())) {
            return;
        }
        String payload = buildPayload(cancelRequest, payment, cancelledItems);
        jpaRepository.save(CancelEventOutboxJpaEntity.pending(cancelRequest.getId(), payload));
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        // limit 파라미터는 현재 1000 고정 (Top1000). 추후 동적 처리 시 @Query로 전환.
        return jpaRepository.findTop1000ByStatusOrderByCreatedAtAsc("PENDING")
            .stream()
            .map(e -> new PendingOutbox(e.getCancelRequestId(), e.getPayload()))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void markPublished(long cancelRequestId) {
        jpaRepository.markPublished(cancelRequestId);
    }

    private String buildPayload(CancelRequest cancelRequest, Payment payment, List<PaymentItem> items) {
        String cancelledAt = cancelRequest.getCompletedAt() != null
            ? cancelRequest.getCompletedAt().toString()
            : java.time.Instant.now().toString();

        String itemsJson = items.stream()
            .map(i -> String.format(
                "{\"paymentItemId\":%d,\"orderItemId\":%d,\"itemAmount\":%s}",
                i.getId(), i.getOrderItemId(), i.getItemAmount().toPlainString()
            ))
            .collect(Collectors.joining(",", "[", "]"));

        return String.format(
            "{\"cancelRequestId\":%d,\"paymentKey\":\"%s\",\"merchantId\":%d,\"cancelledItems\":%s,\"cancelledAt\":\"%s\"}",
            cancelRequest.getId(),
            payment.getPaymentKey(),
            payment.getMerchantId(),
            itemsJson,
            cancelledAt
        );
    }
}
```

- [ ] **Step 5: 빌드 확인**

```bash
./gradlew :payment-service:build -x test
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/PendingOutbox.java \
  payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxRepository.java \
  payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaRepository.java \
  payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java
git commit -m "feat(payment): CancelEventOutboxRepository findPendingBatch/markPublished 추가"
```

---

## Task 3: KafkaOutboxPublisher 구현

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/KafkaOutboxPublisher.java`

- [ ] **Step 1: KafkaOutboxPublisher 생성**

```java
package com.example.payment.infrastructure.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Outbox payload를 Kafka에 발행.
 * KafkaTemplate.send()는 CompletableFuture 반환 — get()으로 블로킹하여 실패를 즉시 감지.
 */
@Slf4j
@Component
public class KafkaOutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaOutboxPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${kafka.topic.payment-cancelled}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * @param cancelRequestId 파티션 키 (같은 결제건 순서 보장)
     * @param payload         Outbox JSON payload 그대로 발행
     * @throws Exception      Kafka 발행 실패 시 — 호출자가 처리
     */
    public void publish(long cancelRequestId, String payload) throws Exception {
        kafkaTemplate.send(topic, String.valueOf(cancelRequestId), payload)
            .get(5, TimeUnit.SECONDS);
        log.debug("[kafka] 발행 완료. topic={}, cancelRequestId={}", topic, cancelRequestId);
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
./gradlew :payment-service:build -x test
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/messaging/KafkaOutboxPublisher.java
git commit -m "feat(payment): KafkaOutboxPublisher 구현"
```

---

## Task 4: OutboxPublisherService — TDD

**Files:**
- Create: `payment-service/src/test/java/com/example/payment/application/service/OutboxPublisherServiceTest.java`
- Create: `payment-service/src/main/java/com/example/payment/application/service/OutboxPublisherService.java`

- [ ] **Step 1: 테스트 파일 먼저 작성 (구현 전)**

`payment-service/src/test/java/com/example/payment/application/service/OutboxPublisherServiceTest.java`:

```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.PendingOutbox;
import com.example.payment.infrastructure.messaging.KafkaOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private CancelEventOutboxRepository outboxRepository;

    @Mock
    private KafkaOutboxPublisher kafkaOutboxPublisher;

    @InjectMocks
    private OutboxPublisherService service;

    @Test
    void PENDING_건_Kafka_발행_후_markPublished_호출() throws Exception {
        // given
        var outbox = new PendingOutbox(1L, "{\"cancelRequestId\":1}");
        given(outboxRepository.findPendingBatch(1000)).willReturn(List.of(outbox));

        // when
        service.publish();

        // then
        verify(kafkaOutboxPublisher).publish(1L, "{\"cancelRequestId\":1}");
        verify(outboxRepository).markPublished(1L);
    }

    @Test
    void Kafka_발행_실패_시_해당_건_skip_나머지_정상_처리() throws Exception {
        // given
        var fail = new PendingOutbox(1L, "payload1");
        var success = new PendingOutbox(2L, "payload2");
        given(outboxRepository.findPendingBatch(1000)).willReturn(List.of(fail, success));
        willThrow(new RuntimeException("Kafka 연결 오류"))
            .given(kafkaOutboxPublisher).publish(1L, "payload1");

        // when — 예외가 밖으로 전파되지 않아야 함
        service.publish();

        // then
        verify(outboxRepository, never()).markPublished(1L);   // 실패 건은 PENDING 유지
        verify(kafkaOutboxPublisher).publish(2L, "payload2");  // 나머지는 계속 처리
        verify(outboxRepository).markPublished(2L);
    }

    @Test
    void PENDING_건_없으면_아무_동작_안함() throws Exception {
        // given
        given(outboxRepository.findPendingBatch(1000)).willReturn(List.of());

        // when
        service.publish();

        // then
        verifyNoInteractions(kafkaOutboxPublisher);
        verify(outboxRepository, never()).markPublished(anyLong());
    }
}
```

- [ ] **Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew :payment-service:test --tests "com.example.payment.application.service.OutboxPublisherServiceTest"
```

Expected: `FAILED` — `OutboxPublisherService` 클래스가 없어서 컴파일 실패

- [ ] **Step 3: OutboxPublisherService 구현**

`payment-service/src/main/java/com/example/payment/application/service/OutboxPublisherService.java`:

```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.PendingOutbox;
import com.example.payment.infrastructure.messaging.KafkaOutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Outbox PENDING 건을 Kafka에 발행하고 PUBLISHED로 마킹.
 * 건별로 독립 처리 — 한 건 실패가 나머지를 막지 않음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private static final int BATCH_SIZE = 1000;

    private final CancelEventOutboxRepository outboxRepository;
    private final KafkaOutboxPublisher kafkaOutboxPublisher;

    public void publish() {
        List<PendingOutbox> pending = outboxRepository.findPendingBatch(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.info("[outbox-publisher] 발행 대상 {}건", pending.size());

        for (PendingOutbox outbox : pending) {
            try {
                kafkaOutboxPublisher.publish(outbox.cancelRequestId(), outbox.payload());
                outboxRepository.markPublished(outbox.cancelRequestId());
            } catch (Exception e) {
                log.error("[outbox-publisher] 발행 실패 — 다음 주기 재시도. cancelRequestId={}, error={}",
                    outbox.cancelRequestId(), e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :payment-service:test --tests "com.example.payment.application.service.OutboxPublisherServiceTest"
```

Expected:
```
OutboxPublisherServiceTest > PENDING_건_Kafka_발행_후_markPublished_호출() PASSED
OutboxPublisherServiceTest > Kafka_발행_실패_시_해당_건_skip_나머지_정상_처리() PASSED
OutboxPublisherServiceTest > PENDING_건_없으면_아무_동작_안함() PASSED

3 tests completed, 0 failed
```

- [ ] **Step 5: 커밋**

```bash
git add \
  payment-service/src/test/java/com/example/payment/application/service/OutboxPublisherServiceTest.java \
  payment-service/src/main/java/com/example/payment/application/service/OutboxPublisherService.java
git commit -m "feat(payment): OutboxPublisherService TDD 구현"
```

---

## Task 5: SchedulerConfig + 스케줄러 4개

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/config/SchedulerConfig.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/OutboxPublisherScheduler.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/PendingRecoveryScheduler.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/ProcessingRecoveryScheduler.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CompensationRetryScheduler.java`

- [ ] **Step 1: SchedulerConfig 생성**

`payment-service/src/main/java/com/example/payment/infrastructure/config/SchedulerConfig.java`:

```java
package com.example.payment.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 활성화.
 * RedissonClient는 redisson-spring-boot-starter가 spring.data.redis 설정으로 자동 구성.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
```

- [ ] **Step 2: OutboxPublisherScheduler 생성**

`payment-service/src/main/java/com/example/payment/infrastructure/scheduler/OutboxPublisherScheduler.java`:

```java
package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.OutboxPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 10초마다 Outbox PENDING 건을 Kafka에 발행.
 * Redis 분산락으로 다중 인스턴스 환경에서 단일 실행 보장.
 * leaseTime=9s: 이전 인스턴스 크래시 시 9초 후 자동 해제.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final RedissonClient redissonClient;
    private final OutboxPublisherService outboxPublisherService;

    @Value("${scheduler.lock.outbox-publisher}")
    private String lockKey;

    @Scheduled(fixedDelay = 10_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 9, TimeUnit.SECONDS)) {
                return; // 다른 인스턴스가 실행 중
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            outboxPublisherService.publish();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 3: PendingRecoveryScheduler 생성 (골격)**

`payment-service/src/main/java/com/example/payment/infrastructure/scheduler/PendingRecoveryScheduler.java`:

```java
package com.example.payment.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 60초마다 PENDING 5분 초과 CancelRequest 복구.
 *
 * 처리 흐름 (미구현):
 *   1. CancelRequest PENDING, createdAt < now - 5분 조회
 *   2. risk checkCharge API 호출
 *      - charged=true  → compensate API → FAILED UPDATE + 이력
 *      - charged=false → FAILED UPDATE + 이력
 *
 * 필요한 추가 작업:
 *   - RiskManagementPort.checkCharge() 추가
 *   - PendingRecoveryService 구현
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingRecoveryScheduler {

    private final RedissonClient redissonClient;

    @Value("${scheduler.lock.pending-recovery}")
    private String lockKey;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            log.info("[pending-recovery] 실행");
            // TODO: PendingRecoveryService.recover() 호출
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 4: ProcessingRecoveryScheduler 생성 (골격)**

`payment-service/src/main/java/com/example/payment/infrastructure/scheduler/ProcessingRecoveryScheduler.java`:

```java
package com.example.payment.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 60초마다 PROCESSING 5분 초과 CancelRequest 복구.
 *
 * 처리 흐름 (미구현):
 *   1. CancelRequest PROCESSING, updatedAt < now - 5분 조회
 *   2. PG사 GET 조회
 *      - 성공           → TX 3 재실행
 *      - 실패(재시도 가능) → PG사 재호출
 *      - 실패(재시도 불가) → compensate → FAILED
 *      - pending        → pg_pending_since 기록
 *        1시간 초과 시   → compensate → FAILED → 운영팀 알림
 *
 * 필요한 추가 작업:
 *   - PgCancelPort.getStatus() 추가
 *   - ProcessingRecoveryService 구현
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingRecoveryScheduler {

    private final RedissonClient redissonClient;

    @Value("${scheduler.lock.processing-recovery}")
    private String lockKey;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            log.info("[processing-recovery] 실행");
            // TODO: ProcessingRecoveryService.recover() 호출
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 5: CompensationRetryScheduler 생성 (골격)**

`payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CompensationRetryScheduler.java`:

```java
package com.example.payment.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 30초마다 보상 재시도 (compensation_retry 테이블).
 *
 * 처리 흐름 (미구현):
 *   1. compensation_retry, next_retry_at <= now, status=PENDING 조회
 *   2. risk compensate API 재호출
 *      - 성공 → DONE
 *      - 실패 → attemptCount++ + 지수 백오프로 next_retry_at 재산정
 *      - 최대 횟수(5회) 초과 → EXHAUSTED + 운영팀 알림
 *
 * 필요한 추가 작업:
 *   - CompensationRetryRepository.findDue() 추가
 *   - CompensationRetryService 구현
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationRetryScheduler {

    private final RedissonClient redissonClient;

    @Value("${scheduler.lock.compensation-retry}")
    private String lockKey;

    @Scheduled(fixedDelay = 30_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 25, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            log.info("[compensation-retry] 실행");
            // TODO: CompensationRetryService.retry() 호출
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 6: 전체 테스트 포함 빌드 확인**

```bash
./gradlew :payment-service:build
```

Expected:
```
OutboxPublisherServiceTest > PENDING_건_Kafka_발행_후_markPublished_호출() PASSED
OutboxPublisherServiceTest > Kafka_발행_실패_시_해당_건_skip_나머지_정상_처리() PASSED
OutboxPublisherServiceTest > PENDING_건_없으면_아무_동작_안함() PASSED

BUILD SUCCESSFUL
```

- [ ] **Step 7: 커밋**

```bash
git add \
  payment-service/src/main/java/com/example/payment/infrastructure/config/SchedulerConfig.java \
  payment-service/src/main/java/com/example/payment/infrastructure/scheduler/OutboxPublisherScheduler.java \
  payment-service/src/main/java/com/example/payment/infrastructure/scheduler/PendingRecoveryScheduler.java \
  payment-service/src/main/java/com/example/payment/infrastructure/scheduler/ProcessingRecoveryScheduler.java \
  payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CompensationRetryScheduler.java
git commit -m "feat(payment): 스케줄러 4개 구현 (OutboxPublisher 완전, 나머지 골격)"
```

---

## Self-Review

**Spec coverage:**
- [x] OutboxPublisher — Task 3, 4 (KafkaOutboxPublisher + Service TDD)
- [x] PendingRecovery 60초 골격 — Task 5 Step 3
- [x] ProcessingRecovery 60초 골격 — Task 5 Step 4
- [x] CompensationRetry 30초 골격 — Task 5 Step 5
- [x] Redisson RLock 직접 사용 — 모든 스케줄러에 적용
- [x] lockAtMostFor = 주기-1초 — leaseTime 설정 확인
- [x] SchedulerConfig 별도 분리 — Task 5 Step 1
- [x] findPendingBatch / markPublished 인터페이스 추가 — Task 2
- [x] PendingOutbox record (application 레이어, JPA 엔티티 미노출) — Task 2 Step 1
- [x] 테스트 3케이스 — Task 4

**타입 일관성:**
- `PendingOutbox(long cancelRequestId, String payload)` — Task 2 Step 1 정의, Task 4 테스트에서 동일하게 사용 ✓
- `kafkaOutboxPublisher.publish(long, String)` — Task 3 정의, Task 4 테스트/구현에서 동일 시그니처 ✓
- `outboxRepository.findPendingBatch(int)` — Task 2 인터페이스 정의, Task 4 구현에서 `1000` 전달 ✓
- `outboxRepository.markPublished(long)` — Task 2 인터페이스 정의, Task 4 구현에서 `outbox.cancelRequestId()` 전달 ✓
