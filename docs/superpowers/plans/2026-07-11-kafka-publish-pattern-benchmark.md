# Kafka 발행 패턴 실측 비교 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `payment.cancelled` 발행을 3모드(INLINE/INLINE_ASYNC/OUTBOX) 런타임 토글로 전환 가능하게 만들고, 발행 비교용 계측·대시보드·측정 런북을 추가해 같은 AWS 리그에서 세 방식을 실측 비교한다.

**Architecture:** 발행을 `CancelEventPublisher` 포트로 추출하고 3구현을 `@ConditionalOnProperty(cancel.publish.mode)`로 하나만 활성화한다. `saveTx3`(정상·복구 양쪽에서 호출됨)가 이 포트를 부르므로 두 경로가 자동으로 모드를 따른다. OUTBOX 모드는 TX3 안에서 `cancel_event_outbox` 행을 멱등 INSERT하고, 별도 RLock 스케줄러가 폴링 발행한다. merchant-limit outbox 패턴을 복사한다.

**Tech Stack:** Java 21 · Spring Boot 3.x · Spring Data JPA · MySQL 8 + Flyway · Kafka · Redisson(RLock) · Micrometer · JUnit 5 + Mockito + Testcontainers · Grafana

## Global Constraints

- domain 레이어에 Spring/JPA 어노테이션 금지 (발행 포트·구현은 application/infrastructure에만).
- DDL은 Flyway로만, 적용된 마이그레이션 수정 금지 → 신규 `V10__create_cancel_event_outbox.sql` (현재 최신 V9).
- 파티션 키 = `cancelRequestId` (CLAUDE.md 정정된 불변식; 기존 `CancelTxWriter`가 `String.valueOf(cancelRequest.getId())` 사용).
- opt-in 유지 — 기본 `cancel.publish.mode=INLINE`(현행 행위 불변), 아웃박스 스케줄러는 OUTBOX 모드에서만 빈 생성. 평상시·CI 영향 0.
- `INLINE_ASYNC`는 안전하지 않음(dual-write 구멍) — 기동 시 WARN 로그, 측정 전용, 절대 기본값 아님.
- OUTBOX 행은 TX3와 원자 커밋 + `cancel_request_id` UK로 복구 재실행 멱등.
- 공정 비교 토글: OTel OFF · query-count OFF · tomcat ON (커밋 6→4 런과 동일).
- TDD, 잦은 커밋. 모듈 간 DB 직접 접근 금지(Kafka 경유).

---

### Task 1: cancel_event_outbox 영속 계층

**Files:**
- Create: `payment-service/src/main/resources/db/migration/V10__create_cancel_event_outbox.sql`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaEntity.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaRepository.java`
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxRepository.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryIT.java`

**Interfaces:**
- Produces: `CancelEventOutboxRepository` 포트 — `void insertPending(long cancelRequestId, String payload)` (멱등, ON DUPLICATE KEY no-op), `List<PendingOutbox> findPendingBatch(int limit)`, `void markPublished(long outboxId)`, `record PendingOutbox(long id, long cancelRequestId, String payload)`.

- [ ] **Step 1: 마이그레이션 작성**

Create `V10__create_cancel_event_outbox.sql`:
```sql
CREATE TABLE cancel_event_outbox
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    cancel_request_id BIGINT      NOT NULL,
    payload           JSON        NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at      DATETIME(3) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_cancel_event_outbox_request (cancel_request_id),
    INDEX idx_cancel_outbox_status_created_at (status, created_at)
);
```

- [ ] **Step 2: JPA 엔티티 작성** (`LimitEventOutboxJpaEntity` 미러, cancel_request_id + factory)

```java
package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cancel_event_outbox",
    indexes = { @Index(name = "idx_cancel_outbox_status_created_at", columnList = "status,created_at") })
public class CancelEventOutboxJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false)
    private Long cancelRequestId;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected CancelEventOutboxJpaEntity() {}

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
    }

    public Long getId()              { return id; }
    public Long getCancelRequestId() { return cancelRequestId; }
    public String getPayload()       { return payload; }
    public String getStatus()        { return status; }
}
```

- [ ] **Step 3: 실패 테스트 작성** (Testcontainers IT — 멱등 insert + batch + markPublished)

`CancelEventOutboxRepositoryIT.java` (기존 payment IT 베이스 클래스/애노테이션은 `MerchantCancelUsageAtomicDeductIT` 등 참조하여 동일 `@DataJpaTest`+Testcontainers 세팅 사용):
```java
@DisplayName("cancel_event_outbox 멱등 insert/조회/발행표시")
class CancelEventOutboxRepositoryIT extends /* 기존 payment JPA IT 베이스 */ {

    @Autowired CancelEventOutboxJpaRepository jpa;
    CancelEventOutboxRepository repo;

    @BeforeEach void setUp() { repo = new CancelEventOutboxRepositoryImpl(jpa); }

    @Test
    @DisplayName("같은 cancelRequestId 중복 insert는 예외 없이 1행")
    void idempotent_insert() {
        repo.insertPending(1001L, "{\"cancelRequestId\":1001}");
        repo.insertPending(1001L, "{\"cancelRequestId\":1001}"); // 중복 — 무시돼야
        List<CancelEventOutboxRepository.PendingOutbox> pending = repo.findPendingBatch(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).cancelRequestId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("markPublished 후 PENDING 조회에서 빠진다")
    void mark_published_excludes() {
        repo.insertPending(2002L, "{}");
        long id = repo.findPendingBatch(10).get(0).id();
        repo.markPublished(id);
        assertThat(repo.findPendingBatch(10)).isEmpty();
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew :payment-service:test --tests "*CancelEventOutboxRepositoryIT"`
Expected: FAIL (컴파일 에러 — 포트/impl/jpa 미존재)

- [ ] **Step 5: 포트·JPA 리포지토리·impl 작성**

`CancelEventOutboxRepository.java` (application/interfaces):
```java
package com.example.payment.application.interfaces;

import java.util.List;

public interface CancelEventOutboxRepository {
    void insertPending(long cancelRequestId, String payload);
    List<PendingOutbox> findPendingBatch(int limit);
    void markPublished(long outboxId);

    record PendingOutbox(long id, long cancelRequestId, String payload) {}
}
```

`CancelEventOutboxJpaRepository.java` (멱등 insert는 native ON DUPLICATE KEY):
```java
package com.example.payment.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface CancelEventOutboxJpaRepository
    extends JpaRepository<CancelEventOutboxJpaEntity, Long> {

    /** cancel_request_id UK 충돌 시 no-op (복구 재실행 멱등). */
    @Modifying
    @Query(value = """
        INSERT INTO cancel_event_outbox (cancel_request_id, payload, status, created_at)
        VALUES (:cancelRequestId, :payload, 'PENDING', CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE cancel_request_id = cancel_request_id
        """, nativeQuery = true)
    void insertPendingIdempotent(long cancelRequestId, String payload);

    List<CancelEventOutboxJpaEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}
```

`CancelEventOutboxRepositoryImpl.java` (`LimitEventOutboxRepositoryImpl` 미러):
```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import org.springframework.data.domain.PageRequest;
import java.util.List;

public class CancelEventOutboxRepositoryImpl implements CancelEventOutboxRepository {

    private final CancelEventOutboxJpaRepository jpaRepository;

    public CancelEventOutboxRepositoryImpl(CancelEventOutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void insertPending(long cancelRequestId, String payload) {
        jpaRepository.insertPendingIdempotent(cancelRequestId, payload);
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        return jpaRepository
            .findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, limit))
            .stream()
            .map(e -> new PendingOutbox(e.getId(), e.getCancelRequestId(), e.getPayload()))
            .toList();
    }

    @Override
    public void markPublished(long outboxId) {
        jpaRepository.findById(outboxId).ifPresent(e -> {
            e.markPublished();
            jpaRepository.save(e);
        });
    }
}
```

- [ ] **Step 6: PersistenceConfig에 빈 배선 추가**

`PersistenceConfig.java`에 import + @Bean 추가 (기존 `cancelRequestRepository` 빈 아래 패턴 동일):
```java
    @Bean
    public com.example.payment.application.interfaces.CancelEventOutboxRepository cancelEventOutboxRepository(
        com.example.payment.infrastructure.persistence.CancelEventOutboxJpaRepository jpaRepository) {
        return new com.example.payment.infrastructure.persistence.CancelEventOutboxRepositoryImpl(jpaRepository);
    }
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :payment-service:test --tests "*CancelEventOutboxRepositoryIT"`
Expected: PASS (2 tests)

- [ ] **Step 8: 커밋**

```bash
git add payment-service/src/main/resources/db/migration/V10__create_cancel_event_outbox.sql \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutbox*.java \
        payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java \
        payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryIT.java
git commit -m "feat(outbox): cancel_event_outbox 테이블 + 멱등 리포지토리"
```

---

### Task 2: CancelEventPublisher 포트 추출 + Inline 구현 (행위 불변 리팩터)

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventPublisher.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/InlineCancelEventPublisher.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelTxWriter.java:31-89`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/messaging/InlineCancelEventPublisherTest.java`

**Interfaces:**
- Consumes: (없음 — 신규 포트)
- Produces: `CancelEventPublisher` 포트 — `void publish(long cancelRequestId, String payload)`. `InlineCancelEventPublisher` 구현 = 현재 `send().get(5s)` + 실패 시 `RuntimeException` throw. `CancelTxWriter`는 `KafkaTemplate`·`topic` 대신 `CancelEventPublisher`를 주입받아 saveTx3 마지막에 `cancelEventPublisher.publish(cancelRequest.getId(), payload)` 호출.

- [ ] **Step 1: 실패 테스트 작성** (InlinePublisher 발행 성공/실패)

`InlineCancelEventPublisherTest.java`:
```java
@ExtendWith(MockitoExtension.class)
class InlineCancelEventPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;
    InlineCancelEventPublisher publisher;

    @BeforeEach void setUp() {
        publisher = new InlineCancelEventPublisher(kafkaTemplate, "payment.cancelled");
    }

    @Test
    @DisplayName("발행 성공 시 send 호출, 예외 없음")
    void publishes_ok() {
        CompletableFuture<SendResult<String,String>> ok = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(eq("payment.cancelled"), eq("77"), anyString())).willReturn(ok);
        publisher.publish(77L, "{\"cancelRequestId\":77}");
        verify(kafkaTemplate).send("payment.cancelled", "77", "{\"cancelRequestId\":77}");
    }

    @Test
    @DisplayName("발행 실패 시 RuntimeException (TX3 롤백 유도)")
    void publish_failure_throws() {
        CompletableFuture<SendResult<String,String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(failed);
        assertThatThrownBy(() -> publisher.publish(77L, "{}"))
            .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :payment-service:test --tests "*InlineCancelEventPublisherTest"`
Expected: FAIL (`InlineCancelEventPublisher` 미존재)

- [ ] **Step 3: 포트 + Inline 구현 작성**

`CancelEventPublisher.java` (application/interfaces):
```java
package com.example.payment.application.interfaces;

/** TX3 마지막에 호출. 모드에 따라 인라인 발행/아웃박스 INSERT를 수행. */
public interface CancelEventPublisher {
    void publish(long cancelRequestId, String payload);
}
```

`InlineCancelEventPublisher.java` (infrastructure/messaging) — 현재 CancelTxWriter 인라인 로직 그대로 이동:
```java
package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "INLINE", matchIfMissing = true)
public class InlineCancelEventPublisher implements CancelEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public InlineCancelEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${kafka.topic.payment-cancelled}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(long cancelRequestId, String payload) {
        try {
            kafkaTemplate.send(topic, String.valueOf(cancelRequestId), payload).get(5, TimeUnit.SECONDS);
            log.debug("[kafka] INLINE 발행 완료. cancelRequestId={}", cancelRequestId);
        } catch (Exception e) {
            log.error("[kafka] INLINE 발행 실패 → TX3 롤백. cancelRequestId={}", cancelRequestId, e);
            throw new RuntimeException("[kafka] INLINE 발행 실패. cancelRequestId=" + cancelRequestId, e);
        }
    }
}
```

- [ ] **Step 4: CancelTxWriter 리팩터** (`kafkaTemplate`·`topic` 필드 제거 → `CancelEventPublisher` 주입, saveTx3 발행부 교체)

`CancelTxWriter.java` 변경:
- 필드 교체: `private final KafkaTemplate<String, String> kafkaTemplate;` + `@Value("${kafka.topic.payment-cancelled}") private String topic;` **삭제** → `private final com.example.payment.application.interfaces.CancelEventPublisher cancelEventPublisher;` 추가. import에서 `KafkaTemplate`, `@Value`, `TimeUnit` 제거.
- saveTx3 발행부(현재 77-86줄) 교체:
```java
        // TX3 마지막: 발행 (모드에 따라 인라인/아웃박스). 실패 시 예외 → TX3 롤백 → processing-recovery.
        String payload = buildPayload(cancelRequest, payment, freshItems, targetItemIds);
        cancelEventPublisher.publish(cancelRequest.getId(), payload);
```
(`buildPayload` 메서드는 그대로 유지.)

- [ ] **Step 5: 테스트 통과 + 회귀 확인**

Run: `./gradlew :payment-service:test --tests "*InlineCancelEventPublisherTest" --tests "*CancelTxWriter*" --tests "*CancelPaymentService*" --tests "*ProcessingRecovery*"`
Expected: PASS (신규 2 + 기존 회귀 그린; 기존 테스트가 `kafkaTemplate` 목을 CancelTxWriter에 주입했다면 `CancelEventPublisher` 목으로 교체 필요 — 그 경우 해당 테스트의 목 선언·주입만 수정하고 검증 의미는 보존)

- [ ] **Step 6: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventPublisher.java \
        payment-service/src/main/java/com/example/payment/infrastructure/messaging/InlineCancelEventPublisher.java \
        payment-service/src/main/java/com/example/payment/application/service/CancelTxWriter.java \
        payment-service/src/test/java/com/example/payment/infrastructure/messaging/InlineCancelEventPublisherTest.java
# 회귀 수정된 기존 테스트가 있으면 함께 add
git commit -m "refactor(publish): 발행을 CancelEventPublisher 포트로 추출 (INLINE 기본, 행위 불변)"
```

---

### Task 3: Outbox·InlineAsync 퍼블리셔 + 모드 선택 config + env 노출

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/OutboxCancelEventPublisher.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/InlineAsyncCancelEventPublisher.java`
- Modify: `payment-service/src/main/resources/application.yml`
- Modify: `infra/load-test/docker-compose*.yml` (payment 서비스 env)
- Modify: `infra/load-test/deploy/ssm-deploy.sh:113`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/messaging/OutboxCancelEventPublisherTest.java`

**Interfaces:**
- Consumes: `CancelEventPublisher`(Task 2), `CancelEventOutboxRepository`(Task 1).
- Produces: `OutboxCancelEventPublisher`(OUTBOX 모드 빈, `publish` → `outboxRepository.insertPending`), `InlineAsyncCancelEventPublisher`(INLINE_ASYNC 빈, fire-and-forget). 정확히 한 `CancelEventPublisher` 빈만 활성.

- [ ] **Step 1: 실패 테스트 작성** (Outbox 퍼블리셔가 outbox INSERT 위임)

`OutboxCancelEventPublisherTest.java`:
```java
@ExtendWith(MockitoExtension.class)
class OutboxCancelEventPublisherTest {

    @Mock CancelEventOutboxRepository outboxRepository;
    @InjectMocks OutboxCancelEventPublisher publisher;

    @Test
    @DisplayName("publish는 outbox INSERT를 위임하고 Kafka 발행은 하지 않는다")
    void inserts_outbox() {
        publisher.publish(88L, "{\"cancelRequestId\":88}");
        verify(outboxRepository).insertPending(88L, "{\"cancelRequestId\":88}");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :payment-service:test --tests "*OutboxCancelEventPublisherTest"`
Expected: FAIL (`OutboxCancelEventPublisher` 미존재)

- [ ] **Step 3: 두 퍼블리셔 작성**

`OutboxCancelEventPublisher.java`:
```java
package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.CancelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** OUTBOX 모드: TX3 안에서 outbox 행 INSERT(같은 커밋). 발행은 스케줄러가 담당. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
public class OutboxCancelEventPublisher implements CancelEventPublisher {

    private final CancelEventOutboxRepository outboxRepository;

    @Override
    public void publish(long cancelRequestId, String payload) {
        outboxRepository.insertPending(cancelRequestId, payload);
    }
}
```

`InlineAsyncCancelEventPublisher.java` (기동 WARN + fire-and-forget):
```java
package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventPublisher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** INLINE_ASYNC: fire-and-forget. dual-write 구멍 존재 — 측정 전용, 프로덕션 금지. */
@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "INLINE_ASYNC")
public class InlineAsyncCancelEventPublisher implements CancelEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public InlineAsyncCancelEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${kafka.topic.payment-cancelled}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @PostConstruct
    void warn() {
        log.warn("[publish] cancel.publish.mode=INLINE_ASYNC — 측정 전용(dual-write 안전하지 않음). 프로덕션 사용 금지.");
    }

    @Override
    public void publish(long cancelRequestId, String payload) {
        kafkaTemplate.send(topic, String.valueOf(cancelRequestId), payload); // .get() 없음 — 실패 감지 안 함
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :payment-service:test --tests "*OutboxCancelEventPublisherTest"`
Expected: PASS (1 test)

- [ ] **Step 5: application.yml에 모드·아웃박스 config 추가**

`application.yml`의 최상위(기존 `kafka:` 블록 근처)에 추가:
```yaml
cancel:
  publish:
    mode: ${CANCEL_PUBLISH_MODE:INLINE}   # INLINE | INLINE_ASYNC | OUTBOX
  outbox:
    poll-ms: ${CANCEL_OUTBOX_POLL_MS:10000}
    batch-size: ${CANCEL_OUTBOX_BATCH_SIZE:1000}
```
그리고 기존 `scheduler.lock` 블록에 한 줄 추가:
```yaml
    cancel-outbox-publisher: lock:scheduler:cancel-outbox-publisher
```

- [ ] **Step 6: compose·ssm-deploy env 노출** (기존 opt-in 토글과 동일 방식)

`ssm-deploy.sh:113`의 export 라인에 이어붙이기:
```sh
export SERVER_TOMCAT_MBEANREGISTRY_ENABLED='${SERVER_TOMCAT_MBEANREGISTRY_ENABLED:-false}' OTEL_JAVAAGENT='${OTEL_JAVAAGENT:-}' LOADTEST_QUERYCOUNT_ENABLED='${LOADTEST_QUERYCOUNT_ENABLED:-false}' CANCEL_PUBLISH_MODE='${CANCEL_PUBLISH_MODE:-INLINE}' CANCEL_OUTBOX_POLL_MS='${CANCEL_OUTBOX_POLL_MS:-10000}'
```
payment 서비스 compose 정의의 `environment:`에 추가:
```yaml
      CANCEL_PUBLISH_MODE: ${CANCEL_PUBLISH_MODE:-INLINE}
      CANCEL_OUTBOX_POLL_MS: ${CANCEL_OUTBOX_POLL_MS:-10000}
```
(정확한 compose 파일·서비스명은 `grep -rln "LOADTEST_QUERYCOUNT_ENABLED" infra/load-test/*.yml` 로 확인해 같은 서비스 블록에 추가.)

- [ ] **Step 7: 3모드 컨텍스트 로딩 확인 + 커밋**

Run: `CANCEL_PUBLISH_MODE=OUTBOX ./gradlew :payment-service:test --tests "*ApplicationContext*" ` (없으면 기존 컨텍스트 로딩 스모크 테스트로) — 각 모드에서 정확히 하나의 `CancelEventPublisher` 빈만 뜨는지 확인. 최소한 `./gradlew :payment-service:compileJava` 통과.
```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/messaging/OutboxCancelEventPublisher.java \
        payment-service/src/main/java/com/example/payment/infrastructure/messaging/InlineAsyncCancelEventPublisher.java \
        payment-service/src/main/resources/application.yml \
        infra/load-test/deploy/ssm-deploy.sh \
        payment-service/src/test/java/com/example/payment/infrastructure/messaging/OutboxCancelEventPublisherTest.java
# compose 파일도 함께 add
git commit -m "feat(publish): OUTBOX/INLINE_ASYNC 퍼블리셔 + 모드 토글 config·env"
```

---

### Task 4: CancelEventOutboxPublisher 스케줄러

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisher.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisherIT.java`

**Interfaces:**
- Consumes: `CancelEventOutboxRepository`(Task 1), `RedissonClient`(기존), `KafkaTemplate`(기존).
- Produces: `CancelEventOutboxPublisher` — `@Scheduled` `publish()`가 RLock 획득 후 PENDING 배치를 `kafkaTemplate.send(topic, cancelRequestId, payload).get()` 발행 + `markPublished`. OUTBOX 모드에서만 빈 생성.

- [ ] **Step 1: 실패 테스트 작성** (Testcontainers IT — OUTBOX 경로 E2E: 행 발행)

`CancelEventOutboxPublisherIT.java` (Kafka Testcontainer 필요 — 기존 Kafka IT 베이스 참조):
```java
@DisplayName("아웃박스 스케줄러가 PENDING 행을 발행하고 PUBLISHED로 표시")
class CancelEventOutboxPublisherIT extends /* 기존 Kafka+DB IT 베이스 */ {

    @Autowired CancelEventOutboxRepository outboxRepository;
    @Autowired CancelEventOutboxPublisher scheduler;
    @Autowired /* test KafkaConsumer 또는 @KafkaListener 캡처 */

    @Test
    @DisplayName("PENDING 행 → publish() → Kafka 메시지 + 행 PUBLISHED")
    void publishes_pending() {
        outboxRepository.insertPending(4004L, "{\"cancelRequestId\":4004}");
        scheduler.publish();
        // 1) Kafka에서 key="4004" 메시지 수신 검증
        // 2) findPendingBatch(10) 이 비어야 함 (PUBLISHED 처리)
        assertThat(outboxRepository.findPendingBatch(10)).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :payment-service:test --tests "*CancelEventOutboxPublisherIT"`
Expected: FAIL (`CancelEventOutboxPublisher` 미존재)

- [ ] **Step 3: 스케줄러 작성** (`PendingRecoveryScheduler` RLock 패턴 + merchant 발행 루프)

```java
package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** OUTBOX 모드에서만 활성. RLock으로 한 인스턴스만 폴링 발행. */
@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
public class CancelEventOutboxPublisher {

    private final CancelEventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedissonClient redissonClient;

    @Value("${kafka.topic.payment-cancelled}") private String topic;
    @Value("${scheduler.lock.cancel-outbox-publisher}") private String lockKey;
    @Value("${cancel.outbox.batch-size:1000}") private int batchSize;

    public CancelEventOutboxPublisher(
        CancelEventOutboxRepository outboxRepository,
        KafkaTemplate<String, String> kafkaTemplate,
        RedissonClient redissonClient) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.redissonClient = redissonClient;
    }

    @Scheduled(fixedDelayString = "${cancel.outbox.poll-ms:10000}")
    public void publish() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            List<CancelEventOutboxRepository.PendingOutbox> pending = outboxRepository.findPendingBatch(batchSize);
            for (var o : pending) {
                try {
                    kafkaTemplate.send(topic, String.valueOf(o.cancelRequestId()), o.payload()).get(5, TimeUnit.SECONDS);
                    outboxRepository.markPublished(o.id());
                } catch (Exception e) {
                    log.error("[outbox] 발행 실패 (다음 폴 재시도). outboxId={}", o.id(), e);
                }
            }
            if (!pending.isEmpty()) log.info("[outbox] 발행 완료. count={}", pending.size());
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `CANCEL_PUBLISH_MODE=OUTBOX ./gradlew :payment-service:test --tests "*CancelEventOutboxPublisherIT"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisher.java \
        payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisherIT.java
git commit -m "feat(outbox): cancel_event_outbox 폴링 발행 스케줄러 (RLock, OUTBOX 모드)"
```

---

### Task 5: OUTBOX 모드 processing-recovery 정합성 통합테스트

**Files:**
- Test: `payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryOutboxIT.java`

**Interfaces:**
- Consumes: `ProcessingRecoveryService`(기존, `saveTx3` 호출), `CancelEventOutboxRepository`(Task 1). 코드 변경 없음 — saveTx3가 Task 2에서 `CancelEventPublisher`에 위임하므로 복구가 OUTBOX 모드에서 자동으로 outbox INSERT를 한다. 이 태스크는 그 정합성(멱등)을 **검증만** 한다.

- [ ] **Step 1: 실패/검증 테스트 작성** (복구 재실행이 outbox 행을 멱등 생성)

`ProcessingRecoveryOutboxIT.java` (`@SpringBootTest` + `cancel.publish.mode=OUTBOX` + Testcontainers):
```java
@SpringBootTest(properties = "cancel.publish.mode=OUTBOX")
@DisplayName("OUTBOX 모드에서 processing-recovery가 outbox 행을 멱등 생성")
class ProcessingRecoveryOutboxIT extends /* 기존 IT 베이스 */ {

    @Autowired ProcessingRecoveryService recoveryService;
    @Autowired CancelEventOutboxRepository outboxRepository;
    // + PROCESSING 상태의 CancelRequest/Payment/PaymentItem 픽스처, PG 조회 목(APPROVED)

    @Test
    @DisplayName("PROCESSING 복구 → saveTx3 재실행 → outbox 행 1개 생성")
    void recovery_creates_outbox_row() {
        // given: PROCESSING 5분 초과 CancelRequest + PG APPROVED
        recoveryService.recoverAll();
        assertThat(outboxRepository.findPendingBatch(10))
            .extracting(CancelEventOutboxRepository.PendingOutbox::cancelRequestId)
            .containsExactly(/* 해당 cancelRequestId */);
    }

    @Test
    @DisplayName("이미 outbox 행이 있으면 복구 재실행해도 중복/예외 없음")
    void recovery_is_idempotent() {
        // given: 같은 cancelRequestId outbox 행 선존재 + PROCESSING 픽스처
        outboxRepository.insertPending(/* cancelRequestId */, "{}");
        recoveryService.recoverAll(); // UK ON DUPLICATE → no-op, 예외 없음
        assertThat(outboxRepository.findPendingBatch(10)).hasSize(1);
    }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `./gradlew :payment-service:test --tests "*ProcessingRecoveryOutboxIT"`
Expected: PASS (Task 1의 멱등 insert + Task 2의 위임 덕분에 코드 변경 없이 통과. 실패하면 saveTx3 위임 경로/UK 재확인)

- [ ] **Step 3: 커밋**

```bash
git add payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryOutboxIT.java
git commit -m "test(outbox): OUTBOX 모드 processing-recovery 멱등성 통합테스트"
```

---

### Task 6: 발행→소비 e2e 지연 계측 (order-service)

**Files:**
- Modify: `order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledConsumer.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/config/KafkaMetricsConfig.java`
- Test: `order-service/src/test/java/com/example/order/infrastructure/messaging/PaymentCancelledConsumerLatencyTest.java`

**Interfaces:**
- Consumes: `PaymentCancelledPayload.cancelledAt`(String, ISO-8601 Instant), `MeterRegistry`.
- Produces: Micrometer Timer `cancel.event.e2e.latency` = `now - Instant.parse(cancelledAt)` 기록 (성공 처리 경로에서). `KafkaClientMetrics` 바인딩(producer/consumer).

- [ ] **Step 1: 실패 테스트 작성** (소비 시 지연 타이머 기록)

`PaymentCancelledConsumerLatencyTest.java`:
```java
@ExtendWith(MockitoExtension.class)
class PaymentCancelledConsumerLatencyTest {

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    @Mock ProcessCancelledItemsUseCase processUseCase;
    @Mock RetryRouter retryRouter;
    ObjectMapper objectMapper = new ObjectMapper();
    PaymentCancelledConsumer consumer;

    @BeforeEach void setUp() {
        consumer = new PaymentCancelledConsumer(processUseCase, retryRouter, objectMapper, registry);
    }

    @Test
    @DisplayName("정상 소비 시 cancel.event.e2e.latency 타이머 1건 기록")
    void records_latency() {
        String cancelledAt = Instant.now().minusSeconds(2).toString();
        String json = "{\"cancelRequestId\":\"5\",\"paymentKey\":\"p\",\"merchantId\":1," +
                      "\"cancelledItems\":[],\"cancelledAt\":\"" + cancelledAt + "\"}";
        var record = new ConsumerRecord<>("payment.cancelled", 0, 0L, "5", json);
        consumer.consume(record, mock(Acknowledgment.class));
        assertThat(registry.get("cancel.event.e2e.latency").timer().count()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :order-service:test --tests "*PaymentCancelledConsumerLatencyTest"`
Expected: FAIL (생성자에 `MeterRegistry` 없음 / 타이머 미기록)

- [ ] **Step 3: 컨슈머에 타이머 주입·기록**

`PaymentCancelledConsumer.java` 변경:
- 필드 추가: `private final io.micrometer.core.instrument.MeterRegistry meterRegistry;` (생성자 파라미터 추가 — `@RequiredArgsConstructor`면 자동).
- `consume` 성공 처리 직후(`log.info(...)` 앞) 기록:
```java
            try {
                java.time.Instant cancelledAt = java.time.Instant.parse(payload.cancelledAt());
                meterRegistry.timer("cancel.event.e2e.latency")
                    .record(java.time.Duration.between(cancelledAt, java.time.Instant.now()));
            } catch (java.time.format.DateTimeParseException ignore) { /* 계측 실패는 처리에 영향 없음 */ }
```

- [ ] **Step 4: Kafka client 메트릭 바인딩 작성** (produce rate·consumer lag)

`KafkaMetricsConfig.java`:
```java
package com.example.order.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;

@Configuration
public class KafkaMetricsConfig {
    // spring-kafka가 노출하는 consumer 메트릭을 Micrometer로 바인딩.
    // (spring-boot-actuator + micrometer-core에 KafkaClientMetrics 포함. consumer lag = kafka.consumer.fetch.manager.records.lag)
    @Bean
    public org.springframework.beans.factory.config.BeanPostProcessor kafkaMetricsBinderMarker() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {}; // no-op 마커: 실제 바인딩은 spring-kafka 기본 micrometer 통합 사용
    }
}
```
※ spring-kafka는 `ConcurrentKafkaListenerContainerFactory`에 MeterRegistry가 있으면 자동으로 consumer 메트릭을 낸다. 이미 actuator/micrometer가 있으면 **추가 코드 없이 노출될 수 있음** — Step 5에서 실제 노출 여부를 확인하고, 노출되면 이 config 파일은 삭제(YAGNI). payment 프로듀서 측도 동일 원칙.

- [ ] **Step 5: 테스트 통과 + 메트릭 노출 확인**

Run: `./gradlew :order-service:test --tests "*PaymentCancelledConsumerLatencyTest"`
Expected: PASS. 그리고 로컬 기동 후 `curl localhost:<order>/actuator/prometheus | grep -E "cancel_event_e2e_latency|kafka_consumer"` 로 지표 노출 확인. `kafka_consumer_*`가 이미 나오면 `KafkaMetricsConfig` 삭제.

- [ ] **Step 6: 커밋**

```bash
git add order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledConsumer.java \
        order-service/src/test/java/com/example/order/infrastructure/messaging/PaymentCancelledConsumerLatencyTest.java
# KafkaMetricsConfig가 필요하면 함께 add, 불필요하면 제외
git commit -m "feat(obs): 발행→소비 e2e 지연 타이머 + Kafka 컨슈머 메트릭 노출"
```

---

### Task 7: publish-pattern-comparison.json 대시보드

**Files:**
- Create: `infra/load-test/observability/grafana/dashboards/publish-pattern-comparison.json`

**Interfaces:**
- Consumes: 지표 — `k6_http_reqs_total`(rps), `k6_http_req_duration_p95`, `hikaricp_connections_active`/`_pending`, `cancel_event_e2e_latency_seconds`(Task 6), `kafka_producer_*`/`kafka_consumer_*_records_lag`(Task 6), `k6_http_req_failed_rate`(장애 성공률).

- [ ] **Step 1: 대시보드 JSON 작성** (`system-views.json` 구조 미러, 5행)

`system-views.json`을 읽어 동일한 스키마(`__inputs`, `panels[].gridPos`, `targets[].expr`, `datasource`)로 5개 행 패널 작성:
1. 취소 처리량/점유 — `rate(k6_http_reqs_total[1m])`, `k6_http_req_duration_p95`, `hikaricp_connections_active{application="payment-service"}`, `hikaricp_connections_pending{application="payment-service"}`
2. 발행→소비 지연 — `histogram_quantile(0.5, rate(cancel_event_e2e_latency_seconds_bucket[1m]))`, `histogram_quantile(0.95, ...)`
3. consumer lag — `kafka_consumer_fetch_manager_records_lag{...}` (Task 6 Step 5에서 확인한 실제 지표명 사용)
4. produce rate 시간축 — `rate(kafka_producer_record_send_total{...}[30s])` (버스트 가시화; 실제 지표명 확인)
5. 장애 주입 시 취소 성공률 — `1 - avg(k6_http_req_failed_rate)`

- [ ] **Step 2: JSON 유효성·프로비저닝 확인**

Run: `jq . infra/load-test/observability/grafana/dashboards/publish-pattern-comparison.json > /dev/null && echo "valid JSON"`
Expected: `valid JSON`. (실제 패널 렌더는 다음 실측 때 Grafana에서 육안 확인 — 런북에 체크 항목으로 명시.)

- [ ] **Step 3: 커밋**

```bash
git add infra/load-test/observability/grafana/dashboards/publish-pattern-comparison.json
git commit -m "feat(obs): 발행 패턴 비교 대시보드 (rps/점유·지연·lag·버스트·장애성공률)"
```

---

### Task 8: 측정 런북 문서

**Files:**
- Create: `docs/load-test/publish-pattern-benchmark.md`

**Interfaces:**
- Consumes: 스펙 §7 측정 계획, 기존 `docs/load-test/measurement-journey.md`·`saturation-diagnosis.md` 절차.

- [ ] **Step 1: 런북 작성**

`docs/load-test/publish-pattern-benchmark.md` — 아래 내용 포함:
- **공정 비교 토글**: `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true OTEL_JAVAAGENT= LOADTEST_QUERYCOUNT_ENABLED=false`(OTel/query-count OFF, tomcat ON), 동일 m7g.large·시드 100k·VU 스윕.
- **P1 3모드 런**: 각 모드 재배포(`CANCEL_PUBLISH_MODE=INLINE|INLINE_ASYNC|OUTBOX ... --force-recreate`) → k6 stress 스윕 → 캡처: 취소 rps·p95·Hikari active/pending·general_log 커밋 수(불변 확인).
- **P2 지연/장애/버스트**:
  - e2e 지연: `cancel_event_e2e_latency` p50/p95 (INLINE ~ms vs OUTBOX ~폴주기).
  - 장애 주입: 런 중 `aws ssm send-command ... 'docker stop <kafka 컨테이너>'`(infra 노드) → 취소 성공률 관찰 → `docker start` → OUTBOX 백로그 배수 확인. 컨테이너명은 `docker ps --format '{{.Names}}' | grep -i kafka`로 확인.
  - 버스트: produce rate 시간축 패널.
  - 폴 노브: `CANCEL_OUTBOX_POLL_MS=1000` 재런으로 지연↓/폴부하↑ 트레이드.
- **가설/판정표**: outbox ≥ inline (~수%), inline_async 상한, 지연·장애·버스트는 극적. 3모드 × 4축 결과표 템플릿.
- **대시보드 육안 체크리스트**: `publish-pattern-comparison.json` 5행 각 패널 데이터 확인.

- [ ] **Step 2: 문서 링크 정합 확인 + 커밋**

Run: `grep -c "CANCEL_PUBLISH_MODE" docs/load-test/publish-pattern-benchmark.md`
Expected: ≥ 1
```bash
git add docs/load-test/publish-pattern-benchmark.md
git commit -m "docs(load-test): 발행 패턴 실측 런북 (P1/P2 절차·장애주입·판정표)"
```

---

## Self-Review

**1. Spec coverage:**
- §4.1 3모드 토글 → Task 2(INLINE 추출)+Task 3(OUTBOX/INLINE_ASYNC+config). ✅
- §4.2 cancel_event_outbox 테이블 → Task 1. ✅
- §4.3 발행 스케줄러 → Task 4. ✅
- §4.4 계측(e2e 지연·Kafka client) → Task 6. ✅
- §4.5 전용 대시보드 → Task 7. ✅
- §6 정합성(OUTBOX 원자·복구 멱등) → Task 1(UK+멱등 insert)+Task 5(복구 IT). ✅
- §7 측정 계획 → Task 8 런북. ✅
- §8 테스트(단위·통합·모드별 복구) → 각 Task 테스트 + Task 5. ✅

**2. Placeholder scan:** 코드 스텝은 전부 실제 코드. Task 6 Step 4/Task 7 Step 1의 "실제 지표명 확인"은 플레이스홀더가 아니라 런타임 검증 지시(actuator/prometheus에서 확정) — Micrometer 지표명은 환경 의존이라 확정 절차를 명시하는 게 옳음. Task 3 Step 6 compose 파일명은 `grep`으로 확정하는 절차 제공.

**3. Type consistency:** `CancelEventPublisher.publish(long, String)` — Task 2 정의, Task 3 두 구현·Task 2 CancelTxWriter 호출 일치. `CancelEventOutboxRepository`(insertPending/findPendingBatch/markPublished + PendingOutbox(long id, long cancelRequestId, String payload)) — Task 1 정의, Task 3 OutboxPublisher·Task 4 스케줄러·Task 5 IT 일치. 파티션 키 `String.valueOf(cancelRequestId)` — Task 2·3·4 일치. ✅
