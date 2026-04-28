# Scheduler Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** pending-recovery와 processing-recovery 스케줄러에 실제 복구 로직을 구현한다.

**Architecture:** CompensationRetryService 패턴과 동일하게 Application Service 레이어에 복구 로직을 캡슐화하고, 스케줄러는 락 획득 후 서비스 호출만 담당한다. V9 migration으로 cancel_item_ids(JSON)와 pg_retry_count 컬럼을 추가한다. TDD 순서로 진행한다.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Data JPA, JUnit 5, Mockito, Flyway, MySQL 8.0, Gradle

**Spec:** `docs/superpowers/specs/2026-04-28-scheduler-enhancement-design.md`

---

## File Map

| 파일 | 유형 | 역할 |
|------|------|------|
| `payment-service/src/main/resources/db/migration/V9__add_cancel_item_ids_and_pg_retry_count.sql` | 신규 | 스키마 변경 |
| `payment-service/src/main/java/com/example/payment/domain/entity/CancelRequest.java` | 수정 | 필드/메서드 변경 |
| `payment-service/src/test/java/com/example/payment/fixture/CancelRequestFixture.java` | 수정 | 시그니처 대응 |
| `payment-service/src/main/java/com/example/payment/application/interfaces/CancelRequestRepository.java` | 수정 | 전용 조회 메서드로 교체 |
| `payment-service/src/main/java/com/example/payment/application/interfaces/PaymentRepository.java` | 수정 | findById 추가 |
| `payment-service/src/main/java/com/example/payment/application/interfaces/RiskManagementPort.java` | 수정 | isCharged 추가 |
| `payment-service/src/main/java/com/example/payment/application/interfaces/PgCancelPort.java` | 수정 | getStatus 추가 |
| `payment-service/src/main/java/com/example/payment/application/dto/PgCancelResult.java` | 수정 | retryable 필드 + 팩토리 메서드 |
| `payment-service/src/main/java/com/example/payment/application/interfaces/OperationAlertPort.java` | 신규 | 운영팀 알림 계약 |
| `payment-service/src/main/java/com/example/payment/infrastructure/persistence/converter/LongListConverter.java` | 신규 | JSON ↔ List\<Long\> |
| `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaEntity.java` | 수정 | JSON 컬럼/pg_retry_count 추가 |
| `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaRepository.java` | 수정 | updatedAt 기준 조회 추가 |
| `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImpl.java` | 수정 | 구현 갱신 |
| `payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentRepositoryImpl.java` | 수정 | findById 추가 |
| `payment-service/src/main/java/com/example/payment/infrastructure/adapter/LogOperationAlertAdapter.java` | 신규 | log.error 알림 구현체 |
| `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java` | 수정 | create() 시그니처 대응, toFailed() 수정 |
| `payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java` | 수정 | PgCancelResult 팩토리 메서드 대응 |
| `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImplTest.java` | 수정 | 새 조회 메서드 테스트 갱신 |
| `payment-service/src/main/java/com/example/payment/application/service/PendingRecoveryService.java` | 신규 | PENDING 복구 로직 |
| `payment-service/src/test/java/com/example/payment/application/service/PendingRecoveryServiceTest.java` | 신규 | 단위 테스트 |
| `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java` | 신규 | PROCESSING 복구 로직 |
| `payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java` | 신규 | 단위 테스트 |
| `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/PendingRecoveryScheduler.java` | 수정 | 스텁 채움 |
| `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/ProcessingRecoveryScheduler.java` | 수정 | 스텁 채움 |

---

### Task 1: V9 마이그레이션 SQL

**Files:**
- Create: `payment-service/src/main/resources/db/migration/V9__add_cancel_item_ids_and_pg_retry_count.sql`

- [ ] **Step 1: SQL 파일 생성**

```sql
-- V9__add_cancel_item_ids_and_pg_retry_count.sql
-- cancel_request: cancel_item_ids (JSON 취소 대상 아이템 목록) + pg_retry_count (PG 재시도 횟수) 추가

ALTER TABLE cancel_request
    ADD COLUMN cancel_item_ids JSON NOT NULL
        COMMENT '취소 대상 payment_item_id 목록 (e.g. [1,2,3])'
        AFTER cancel_amount,
    ADD COLUMN pg_retry_count INT NOT NULL DEFAULT 0
        COMMENT 'processing-recovery PG 취소 재시도 횟수'
        AFTER pg_pending_since;
```

- [ ] **Step 2: 마이그레이션 상태 확인**

```bash
./gradlew :payment-service:flywayInfo
```

Expected: V9가 Pending 상태로 표시됨.

- [ ] **Step 3: 커밋**

```bash
git add payment-service/src/main/resources/db/migration/V9__add_cancel_item_ids_and_pg_retry_count.sql
git commit -m "feat(payment): V9 migration — cancel_item_ids JSON + pg_retry_count"
```

---

### Task 2: CancelRequest 도메인 엔티티 수정

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/domain/entity/CancelRequest.java`
- Modify: `payment-service/src/test/java/com/example/payment/fixture/CancelRequestFixture.java`

변경 요약:
- 추가: `cancelItemIds`, `pgRetryCount`, `markPgPending()`, `incrementPgRetryCount()`
- 삭제: `processingStartedAt`(V8에서 DB 컬럼 삭제됨), `failedReason`(동일)
- 변경: `create()` / `reconstruct()` 시그니처, `toFailed(String)` → `toFailed()`, `toProcessing()`에서 processingStartedAt 할당 제거

- [ ] **Step 1: CancelRequest 전체 교체**

```java
package com.example.payment.domain.entity;

import com.example.payment.domain.exception.InvalidCancelAmountException;
import com.example.payment.domain.exception.InvalidCancelStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class CancelRequest {

    private Long id;
    private Long paymentId;
    private String requestHash;
    private BigDecimal cancelAmount;
    private String cancelReason;
    private List<Long> cancelItemIds;
    private CancelStatus status;
    private int pgRetryCount;
    private Instant completedAt;
    private Instant pgPendingSince;
    private Instant createdAt;
    private Instant updatedAt;

    private CancelRequest(Long paymentId, String requestHash,
                          BigDecimal cancelAmount, String cancelReason,
                          List<Long> cancelItemIds) {
        validateCancelAmount(cancelAmount);
        this.paymentId = paymentId;
        this.requestHash = requestHash;
        this.cancelAmount = cancelAmount;
        this.cancelReason = cancelReason;
        this.cancelItemIds = cancelItemIds;
        this.status = CancelStatus.PENDING;
        this.pgRetryCount = 0;
        this.createdAt = Instant.now();
    }

    public static CancelRequest create(Long paymentId, String requestHash,
                                       BigDecimal cancelAmount, String cancelReason,
                                       List<Long> cancelItemIds) {
        return new CancelRequest(paymentId, requestHash, cancelAmount, cancelReason, cancelItemIds);
    }

    public static CancelRequest reconstruct(
        Long id, Long paymentId, String requestHash,
        BigDecimal cancelAmount, String cancelReason,
        List<Long> cancelItemIds, CancelStatus status,
        int pgRetryCount, Instant completedAt,
        Instant pgPendingSince, Instant createdAt, Instant updatedAt
    ) {
        CancelRequest r = new CancelRequest(paymentId, requestHash, cancelAmount, cancelReason, cancelItemIds);
        r.id = id;
        r.status = status;
        r.pgRetryCount = pgRetryCount;
        r.completedAt = completedAt;
        r.pgPendingSince = pgPendingSince;
        r.createdAt = createdAt;
        r.updatedAt = updatedAt;
        return r;
    }

    /** PENDING → PROCESSING */
    public void toProcessing() {
        if (status != CancelStatus.PENDING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PROCESSING);
        }
        this.status = CancelStatus.PROCESSING;
    }

    /** PROCESSING → COMPLETED */
    public void toCompleted() {
        if (status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.COMPLETED);
        }
        this.status = CancelStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** PENDING or PROCESSING → FAILED */
    public void toFailed() {
        if (status != CancelStatus.PENDING && status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.FAILED);
        }
        this.status = CancelStatus.FAILED;
    }

    /** FAILED → PENDING (재시도: UK 유지, 새 INSERT 없음) */
    public void raiseToPending() {
        if (status != CancelStatus.FAILED) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PENDING);
        }
        this.status = CancelStatus.PENDING;
        this.pgRetryCount = 0;
    }

    /** pgPendingSince == null일 때만 설정 (멱등) */
    public void markPgPending() {
        if (this.pgPendingSince == null) {
            this.pgPendingSince = Instant.now();
        }
    }

    /** PG 재시도 횟수 1 증가 */
    public void incrementPgRetryCount() {
        this.pgRetryCount++;
    }

    private void validateCancelAmount(BigDecimal cancelAmount) {
        if (cancelAmount == null || cancelAmount.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidCancelAmountException(cancelAmount);
        }
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getRequestHash() { return requestHash; }
    public BigDecimal getCancelAmount() { return cancelAmount; }
    public String getCancelReason() { return cancelReason; }
    public List<Long> getCancelItemIds() { return cancelItemIds; }
    public CancelStatus getStatus() { return status; }
    public int getPgRetryCount() { return pgRetryCount; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getPgPendingSince() { return pgPendingSince; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 2: CancelRequestFixture 수정**

```java
package com.example.payment.fixture;

import com.example.payment.domain.entity.CancelRequest;
import java.math.BigDecimal;
import java.util.List;

public class CancelRequestFixture {

    public static CancelRequest pending(Long paymentId, BigDecimal cancelAmount) {
        return CancelRequest.create(paymentId, "hash_" + paymentId, cancelAmount, "고객 변심",
            List.of(paymentId * 10, paymentId * 10 + 1));
    }

    public static CancelRequest completed(Long paymentId, BigDecimal cancelAmount) {
        CancelRequest r = pending(paymentId, cancelAmount);
        r.toProcessing();
        r.toCompleted();
        return r;
    }

    public static CancelRequest failed(Long paymentId, BigDecimal cancelAmount) {
        CancelRequest r = pending(paymentId, cancelAmount);
        r.toFailed();
        return r;
    }

    private CancelRequestFixture() {}
}
```

- [ ] **Step 3: 컴파일 오류 목록 확인 (수정은 Task 4~7에서 진행)**

```bash
./gradlew :payment-service:compileTestJava 2>&1 | grep "error:"
```

Expected: `CancelRequestJpaEntity.toDomain()`, `CancelPaymentService.create()` 등 오류 표시. 정상. 다음 Task에서 수정.

- [ ] **Step 4: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/domain/entity/CancelRequest.java \
        payment-service/src/test/java/com/example/payment/fixture/CancelRequestFixture.java
git commit -m "feat(payment): CancelRequest — cancelItemIds/pgRetryCount 추가, processingStartedAt/failedReason 제거"
```

---

### Task 3: 인터페이스 및 DTO 변경

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelRequestRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/PaymentRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/RiskManagementPort.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/PgCancelPort.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/dto/PgCancelResult.java`
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/OperationAlertPort.java`

- [ ] **Step 1: CancelRequestRepository 교체**

기존 `findByStatusAndCreatedAtBefore(status, before)` 범용 메서드 삭제 — 상태에 따라 기준 시간(createdAt vs updatedAt)이 달라지므로 범용 메서드는 혼동을 유발함.

```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CancelRequestRepository {

    Optional<CancelRequest> findByPaymentIdAndRequestHash(long paymentId, String requestHash);

    CancelRequest save(CancelRequest cancelRequest);

    /**
     * pending-recovery용: PENDING + createdAt < before
     * TX1 이후 상태 변경 없는 건 → createdAt 기준
     */
    List<CancelRequest> findPendingCreatedBefore(Instant before);

    /**
     * processing-recovery용: PROCESSING + updatedAt < before
     * TX2(PROCESSING UPDATE)가 updatedAt 기준점 → updatedAt 기준
     */
    List<CancelRequest> findProcessingUpdatedBefore(Instant before);
}
```

- [ ] **Step 2: PaymentRepository — findById 추가**

```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.Payment;
import java.util.Optional;

public interface PaymentRepository {

    Optional<Payment> findByPaymentKey(String paymentKey);

    /** 복구 스케줄러에서 cancelRequest.getPaymentId()로 Payment 로드 */
    Optional<Payment> findById(Long paymentId);

    Payment save(Payment payment);
}
```

- [ ] **Step 3: RiskManagementPort — isCharged 추가**

```java
package com.example.payment.application.interfaces;

import com.example.payment.application.dto.RiskReserveResult;
import java.math.BigDecimal;
import java.time.LocalDate;

public interface RiskManagementPort {

    RiskReserveResult validateAndReserve(long merchantId, long cancelRequestId,
                                          BigDecimal cancelAmount, LocalDate kstDate);

    void compensate(long cancelRequestId, long merchantId, BigDecimal restoreAmount);

    /**
     * 차감 여부 확인. pending-recovery에서 보상 필요 여부 판단에 사용.
     * @return true: risk의 used_amount가 이미 차감된 상태
     */
    boolean isCharged(long cancelRequestId);
}
```

- [ ] **Step 4: PgCancelPort — getStatus 추가**

```java
package com.example.payment.application.interfaces;

import com.example.payment.application.dto.PgCancelResult;
import java.math.BigDecimal;

public interface PgCancelPort {

    /** PG사 취소 실행 */
    PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason);

    /**
     * PG사 취소 건 상태 조회.
     * 조회 실패(네트워크 오류 등) 시 예외 throw → 스케줄러가 PROCESSING 유지.
     */
    PgCancelResult getStatus(String paymentKey);
}
```

- [ ] **Step 5: PgCancelResult — retryable 필드 + 팩토리 메서드 추가**

```java
package com.example.payment.application.dto;

public record PgCancelResult(
    String pgTransactionId,
    String status,
    boolean retryable
) {
    public boolean isApproved() { return "APPROVED".equals(status); }
    public boolean isFailed()   { return "FAILED".equals(status); }
    public boolean isPending()  { return "PENDING".equals(status); }
    public boolean isRetryable() { return retryable; }

    public static PgCancelResult approved(String pgTransactionId) {
        return new PgCancelResult(pgTransactionId, "APPROVED", false);
    }

    /** 재시도 불가 실패 (카드사 정책, 취소 기간 만료 등) */
    public static PgCancelResult failed(String pgTransactionId) {
        return new PgCancelResult(pgTransactionId, "FAILED", false);
    }

    /** 재시도 가능 실패 (네트워크 오류, 일시적 PG 오류 등) */
    public static PgCancelResult retryableFailed(String pgTransactionId) {
        return new PgCancelResult(pgTransactionId, "FAILED", true);
    }

    public static PgCancelResult pending(String pgTransactionId) {
        return new PgCancelResult(pgTransactionId, "PENDING", false);
    }
}
```

- [ ] **Step 6: OperationAlertPort 신규 생성**

```java
package com.example.payment.application.interfaces;

import java.time.Instant;

/**
 * 운영팀 알림 계약.
 * 현재 구현: LogOperationAlertAdapter (log.error).
 * 추후 Slack/PagerDuty 교체 가능.
 */
public interface OperationAlertPort {
    void alertPgPendingTimeout(long cancelRequestId, String paymentKey, Instant pgPendingSince);
}
```

- [ ] **Step 7: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/
git commit -m "feat(payment): 인터페이스 변경 — CancelRequestRepository/PaymentRepository/RiskManagementPort/PgCancelPort/PgCancelResult/OperationAlertPort"
```

---

### Task 4: LongListConverter + CancelRequestJpaEntity 수정

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/converter/LongListConverter.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaEntity.java`

- [ ] **Step 1: LongListConverter 생성**

```java
package com.example.payment.infrastructure.persistence.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class LongListConverter implements AttributeConverter<List<Long>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<Long> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("List<Long> → JSON 직렬화 실패", e);
        }
    }

    @Override
    public List<Long> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Long>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON → List<Long> 역직렬화 실패: " + json, e);
        }
    }
}
```

- [ ] **Step 2: CancelRequestJpaEntity 전체 교체**

V8에서 삭제된 `processingStartedAt`/`failedReason` 잔재 코드 제거, `cancelItemIds`/`pgRetryCount` 추가.

```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.infrastructure.persistence.converter.LongListConverter;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * DDL: V2 + V8 + V9 기준
 */
@Entity
@Table(name = "cancel_request",
    indexes = {
        @Index(name = "idx_cancel_request_payment_id", columnList = "payment_id"),
        @Index(name = "idx_cancel_request_status", columnList = "status"),
        @Index(name = "idx_cancel_request_status_created_at", columnList = "status,created_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_cancel_request_hash", columnNames = {"payment_id", "request_hash"})
    }
)
public class CancelRequestJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "cancel_amount", nullable = false, columnDefinition = "DECIMAL(19,2)")
    private BigDecimal cancelAmount;

    @Convert(converter = LongListConverter.class)
    @Column(name = "cancel_item_ids", nullable = false, columnDefinition = "JSON")
    private List<Long> cancelItemIds;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CancelStatus status;

    @Column(name = "pg_pending_since", columnDefinition = "DATETIME(3)")
    private LocalDateTime pgPendingSince;

    @Column(name = "pg_retry_count", nullable = false)
    private int pgRetryCount;

    @Column(name = "completed_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    protected CancelRequestJpaEntity() {}

    public static CancelRequestJpaEntity from(CancelRequest request) {
        CancelRequestJpaEntity e = new CancelRequestJpaEntity();
        e.id = request.getId();
        e.paymentId = request.getPaymentId();
        e.requestHash = request.getRequestHash();
        e.cancelAmount = request.getCancelAmount();
        e.cancelItemIds = request.getCancelItemIds();
        e.cancelReason = request.getCancelReason();
        e.status = request.getStatus();
        e.pgPendingSince = toLocalDateTime(request.getPgPendingSince());
        e.pgRetryCount = request.getPgRetryCount();
        e.completedAt = toLocalDateTime(request.getCompletedAt());
        e.createdAt = toLocalDateTime(request.getCreatedAt());
        e.updatedAt = request.getUpdatedAt() != null
            ? toLocalDateTime(request.getUpdatedAt())
            : LocalDateTime.now(ZoneOffset.UTC);
        return e;
    }

    public CancelRequest toDomain() {
        return CancelRequest.reconstruct(
            id, paymentId, requestHash,
            cancelAmount, cancelReason,
            cancelItemIds, status,
            pgRetryCount, toInstant(completedAt),
            toInstant(pgPendingSince),
            toInstant(createdAt),
            toInstant(updatedAt)
        );
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : null;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public String getRequestHash() { return requestHash; }
    public BigDecimal getCancelAmount() { return cancelAmount; }
    public List<Long> getCancelItemIds() { return cancelItemIds; }
    public String getCancelReason() { return cancelReason; }
    public CancelStatus getStatus() { return status; }
    public void setStatus(CancelStatus status) { this.status = status; }
    public LocalDateTime getPgPendingSince() { return pgPendingSince; }
    public int getPgRetryCount() { return pgRetryCount; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/persistence/converter/ \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaEntity.java
git commit -m "feat(payment): CancelRequestJpaEntity — cancel_item_ids JSON + pg_retry_count 매핑"
```

---

### Task 5: CancelRequestJpaRepository + CancelRequestRepositoryImpl 갱신

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImpl.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImplTest.java`

- [ ] **Step 1: CancelRequestJpaRepository 갱신**

Spring Data JPA 명명 규칙으로 두 메서드 모두 자동 생성됨.

```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CancelRequestJpaRepository extends JpaRepository<CancelRequestJpaEntity, Long> {

    Optional<CancelRequestJpaEntity> findByPaymentIdAndRequestHash(Long paymentId, String requestHash);

    // pending-recovery: PENDING + createdAt 기준
    List<CancelRequestJpaEntity> findByStatusAndCreatedAtBefore(CancelStatus status, LocalDateTime before);

    // processing-recovery: PROCESSING + updatedAt 기준
    List<CancelRequestJpaEntity> findByStatusAndUpdatedAtBefore(CancelStatus status, LocalDateTime before);
}
```

- [ ] **Step 2: CancelRequestRepositoryImpl 전체 교체**

```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class CancelRequestRepositoryImpl implements CancelRequestRepository {

    private final CancelRequestJpaRepository jpaRepository;

    public CancelRequestRepositoryImpl(CancelRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<CancelRequest> findByPaymentIdAndRequestHash(long paymentId, String requestHash) {
        return jpaRepository.findByPaymentIdAndRequestHash(paymentId, requestHash)
            .map(CancelRequestJpaEntity::toDomain);
    }

    @Override
    public CancelRequest save(CancelRequest cancelRequest) {
        return jpaRepository.save(CancelRequestJpaEntity.from(cancelRequest)).toDomain();
    }

    @Override
    public List<CancelRequest> findPendingCreatedBefore(Instant before) {
        LocalDateTime beforeLdt = LocalDateTime.ofInstant(before, ZoneOffset.UTC);
        return jpaRepository.findByStatusAndCreatedAtBefore(CancelStatus.PENDING, beforeLdt)
            .stream().map(CancelRequestJpaEntity::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<CancelRequest> findProcessingUpdatedBefore(Instant before) {
        LocalDateTime beforeLdt = LocalDateTime.ofInstant(before, ZoneOffset.UTC);
        return jpaRepository.findByStatusAndUpdatedAtBefore(CancelStatus.PROCESSING, beforeLdt)
            .stream().map(CancelRequestJpaEntity::toDomain).collect(Collectors.toList());
    }
}
```

- [ ] **Step 3: CancelRequestRepositoryImplTest 갱신**

기존 `should_find_by_status_and_created_at_before` 테스트를 아래 세 테스트로 교체한다.

```java
@Test
void should_find_pending_created_before_threshold() {
    CancelRequest request = CancelRequestFixture.pending(1L, BigDecimal.valueOf(50000));
    jpaRepository.save(CancelRequestJpaEntity.from(request));
    LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60);

    List<CancelRequestJpaEntity> found =
        jpaRepository.findByStatusAndCreatedAtBefore(CancelStatus.PENDING, threshold);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).getStatus()).isEqualTo(CancelStatus.PENDING);
}

@Test
void should_find_processing_updated_before_threshold() {
    CancelRequest request = CancelRequestFixture.pending(2L, BigDecimal.valueOf(30000));
    request.toProcessing();
    jpaRepository.save(CancelRequestJpaEntity.from(request));
    LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60);

    List<CancelRequestJpaEntity> found =
        jpaRepository.findByStatusAndUpdatedAtBefore(CancelStatus.PROCESSING, threshold);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).getStatus()).isEqualTo(CancelStatus.PROCESSING);
}

@Test
void should_not_find_completed_in_pending_query() {
    CancelRequest request = CancelRequestFixture.completed(3L, BigDecimal.valueOf(20000));
    jpaRepository.save(CancelRequestJpaEntity.from(request));
    LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60);

    List<CancelRequestJpaEntity> found =
        jpaRepository.findByStatusAndCreatedAtBefore(CancelStatus.PENDING, threshold);

    assertThat(found).isEmpty();
}
```

- [ ] **Step 4: 테스트 실행**

```bash
./gradlew :payment-service:test --tests "com.example.payment.infrastructure.persistence.CancelRequestRepositoryImplTest" -i
```

Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImpl.java \
        payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImplTest.java
git commit -m "feat(payment): CancelRequestRepository — findPendingCreatedBefore/findProcessingUpdatedBefore 구현"
```

---

### Task 6: PaymentRepositoryImpl findById + LogOperationAlertAdapter

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentRepositoryImpl.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/adapter/LogOperationAlertAdapter.java`

- [ ] **Step 1: PaymentRepositoryImpl — findById 추가**

`PaymentJpaRepository`는 `JpaRepository<PaymentJpaEntity, Long>`을 상속하므로 `findById(Long)`이 이미 기본 제공됨.

```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import java.util.Optional;

public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    public PaymentRepositoryImpl(PaymentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Payment> findByPaymentKey(String paymentKey) {
        return jpaRepository.findByPaymentKey(paymentKey).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Optional<Payment> findById(Long paymentId) {
        return jpaRepository.findById(paymentId).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Payment save(Payment payment) {
        return jpaRepository.save(PaymentJpaEntity.from(payment)).toDomain();
    }
}
```

- [ ] **Step 2: LogOperationAlertAdapter 신규 생성**

```java
package com.example.payment.infrastructure.adapter;

import com.example.payment.application.interfaces.OperationAlertPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Slf4j
@Component
public class LogOperationAlertAdapter implements OperationAlertPort {

    @Override
    public void alertPgPendingTimeout(long cancelRequestId, String paymentKey, Instant pgPendingSince) {
        log.error("[ALERT] PG pending 1시간 초과 — 수동 확인 필요. cancelRequestId={} paymentKey={} pgPendingSince={}",
            cancelRequestId, paymentKey, pgPendingSince);
    }
}
```

- [ ] **Step 3: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentRepositoryImpl.java \
        payment-service/src/main/java/com/example/payment/infrastructure/adapter/LogOperationAlertAdapter.java
git commit -m "feat(payment): PaymentRepository findById + LogOperationAlertAdapter"
```

---

### Task 7: CancelPaymentService 수정 + 기존 테스트 컴파일 오류 해소

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java`
- Modify: `payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java`

- [ ] **Step 1: CancelPaymentService 두 곳 수정**

**변경 1) CancelRequest.create() 호출에 cancelItemIds 추가:**

```java
// 기존
CancelRequest cancelRequest = CancelRequest.create(
    payment.getId(), requestHash, cancelAmount, command.cancelReason());

// 변경
CancelRequest cancelRequest = CancelRequest.create(
    payment.getId(), requestHash, cancelAmount, command.cancelReason(),
    command.cancelPaymentItemIds());
```

**변경 2) markFailed()에서 toFailed(reason) → toFailed()로 변경 (reason은 이력에만 전달):**

```java
private void markFailed(CancelRequest cancelRequest, String reason) {
    cancelRequest.toFailed();
    cancelRequestRepository.save(cancelRequest);
    recordHistory(cancelRequest.getId(), CancelStatus.FAILED, reason);
}
```

- [ ] **Step 2: CancelPaymentServiceTest — PgCancelResult 생성 패턴 교체**

`CancelPaymentServiceTest`에서 `new PgCancelResult(...)` 패턴을 팩토리 메서드로 교체한다.

```java
// 기존
when(pgCancelPort.cancel(anyString(), any(), anyString()))
    .thenReturn(new PgCancelResult("pg_tx", "APPROVED"));

// 변경
when(pgCancelPort.cancel(anyString(), any(), anyString()))
    .thenReturn(PgCancelResult.approved("pg_tx"));
```

```java
// 기존
when(pgCancelPort.cancel(anyString(), any(), anyString()))
    .thenReturn(new PgCancelResult("pg_tx", "FAILED"));

// 변경
when(pgCancelPort.cancel(anyString(), any(), anyString()))
    .thenReturn(PgCancelResult.failed("pg_tx"));
```

```java
// 기존
when(pgCancelPort.cancel(anyString(), any(), anyString()))
    .thenReturn(new PgCancelResult("pg_tx", "PENDING"));

// 변경
when(pgCancelPort.cancel(anyString(), any(), anyString()))
    .thenReturn(PgCancelResult.pending("pg_tx"));
```

- [ ] **Step 3: 전체 기존 테스트 PASS 확인**

```bash
./gradlew :payment-service:test -i
```

Expected: PendingRecoveryServiceTest / ProcessingRecoveryServiceTest 제외 전체 PASS.

- [ ] **Step 4: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java \
        payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java
git commit -m "fix(payment): CancelPaymentService — create() cancelItemIds 추가, toFailed() 시그니처 대응"
```

---

### Task 8: PendingRecoveryService (TDD)

**Files:**
- Create: `payment-service/src/test/java/com/example/payment/application/service/PendingRecoveryServiceTest.java`
- Create: `payment-service/src/main/java/com/example/payment/application/service/PendingRecoveryService.java`

- [ ] **Step 1: 실패 테스트 작성**

`PaymentFixture.completedPayment()`는 merchantId=1L, paymentKey="pay_test_001"인 Payment를 반환함.

```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import com.example.payment.fixture.CancelRequestFixture;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingRecoveryService")
class PendingRecoveryServiceTest {

    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock CancelRequestHistoryRepository historyRepository;
    @Mock RiskManagementPort riskManagementPort;
    @Mock CompensationRetryRepository compensationRetryRepository;
    @Mock PaymentRepository paymentRepository;

    PendingRecoveryService service;
    Payment payment;
    CancelRequest pendingRequest;

    @BeforeEach
    void setUp() {
        service = new PendingRecoveryService(
            cancelRequestRepository, historyRepository,
            riskManagementPort, compensationRetryRepository, paymentRepository
        );
        payment = PaymentFixture.completedPayment(); // merchantId=1L
        pendingRequest = CancelRequestFixture.pending(1L, BigDecimal.valueOf(50000));
    }

    @Test
    @DisplayName("charged=true: 보상 성공 → FAILED + 이력")
    void charged_true_compensate_success() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(riskManagementPort.isCharged(anyLong())).thenReturn(true);

        service.recoverAll();

        verify(riskManagementPort).compensate(anyLong(), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(cancelRequestRepository).save(argThat(r -> r.getStatus() == CancelStatus.FAILED));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("charged=true: 보상 실패 → compensationRetry 저장 + FAILED + 이력")
    void charged_true_compensate_fails() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(riskManagementPort.isCharged(anyLong())).thenReturn(true);
        doThrow(new RuntimeException("risk 장애")).when(riskManagementPort)
            .compensate(anyLong(), anyLong(), any());

        service.recoverAll();

        verify(compensationRetryRepository).save(anyLong(), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(cancelRequestRepository).save(argThat(r -> r.getStatus() == CancelStatus.FAILED));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("charged=false: 보상 없이 FAILED + 이력")
    void charged_false_direct_failed() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(riskManagementPort.isCharged(anyLong())).thenReturn(false);

        service.recoverAll();

        verify(riskManagementPort, never()).compensate(anyLong(), anyLong(), any());
        verify(cancelRequestRepository).save(argThat(r -> r.getStatus() == CancelStatus.FAILED));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("isCharged 예외 발생 시 해당 건 skip, 스케줄러 중단 없음")
    void exception_during_recover_skips_and_continues() {
        CancelRequest second = CancelRequestFixture.pending(2L, BigDecimal.valueOf(30000));
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest, second));
        when(paymentRepository.findById(anyLong())).thenReturn(Optional.of(payment));
        when(riskManagementPort.isCharged(anyLong()))
            .thenThrow(new RuntimeException("첫 번째 건 오류"))
            .thenReturn(false);

        service.recoverAll();

        // 두 번째 건은 정상 처리됨
        verify(cancelRequestRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("대상 없으면 아무 작업 없음")
    void no_stale_pending_does_nothing() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of());

        service.recoverAll();

        verifyNoInteractions(riskManagementPort, paymentRepository, compensationRetryRepository);
    }
}
```

- [ ] **Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew :payment-service:test --tests "com.example.payment.application.service.PendingRecoveryServiceTest" -i
```

Expected: FAIL — `PendingRecoveryService` 클래스 없음.

- [ ] **Step 3: PendingRecoveryService 구현**

```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PENDING 5분 초과 건 복구.
 *
 * risk.isCharged=true  → compensate → FAILED
 * risk.isCharged=false → FAILED (보상 불필요)
 * 각 건 예외 시 log.warn + 다음 건 계속 (스케줄러 중단 없음)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingRecoveryService {

    private static final Duration PENDING_THRESHOLD = Duration.ofMinutes(5);

    private final CancelRequestRepository cancelRequestRepository;
    private final CancelRequestHistoryRepository historyRepository;
    private final RiskManagementPort riskManagementPort;
    private final CompensationRetryRepository compensationRetryRepository;
    private final PaymentRepository paymentRepository;

    public void recoverAll() {
        Instant threshold = Instant.now().minus(PENDING_THRESHOLD);
        List<CancelRequest> stale = cancelRequestRepository.findPendingCreatedBefore(threshold);
        log.info("[pending-recovery] 대상={}건 threshold={}", stale.size(), threshold);
        stale.forEach(this::recoverOne);
    }

    private void recoverOne(CancelRequest cancelRequest) {
        try {
            Payment payment = paymentRepository.findById(cancelRequest.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + cancelRequest.getPaymentId()));

            boolean charged = riskManagementPort.isCharged(cancelRequest.getId());
            if (charged) {
                tryCompensate(cancelRequest, payment);
            }
            cancelRequest.toFailed();
            cancelRequestRepository.save(cancelRequest);
            recordHistory(cancelRequest.getId(), CancelStatus.FAILED, "pending-recovery");
        } catch (Exception e) {
            log.warn("[pending-recovery] 처리 실패 cancelRequestId={}: {}", cancelRequest.getId(), e.getMessage());
        }
    }

    private void tryCompensate(CancelRequest cancelRequest, Payment payment) {
        try {
            riskManagementPort.compensate(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        } catch (Exception ex) {
            log.warn("[pending-recovery] 보상 실패 cancelRequestId={}: {}", cancelRequest.getId(), ex.getMessage());
            compensationRetryRepository.save(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        }
    }

    private void recordHistory(long cancelRequestId, CancelStatus status, String reason) {
        try {
            historyRepository.record(cancelRequestId, status, reason);
        } catch (Exception e) {
            log.warn("[pending-recovery] 이력 기록 실패 (비즈니스 영향 없음) cancelRequestId={}", cancelRequestId, e);
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 — PASS 확인**

```bash
./gradlew :payment-service:test --tests "com.example.payment.application.service.PendingRecoveryServiceTest" -i
```

Expected: 5개 테스트 PASS.

- [ ] **Step 5: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/service/PendingRecoveryService.java \
        payment-service/src/test/java/com/example/payment/application/service/PendingRecoveryServiceTest.java
git commit -m "feat(payment): PendingRecoveryService — PENDING 5분 초과 복구 로직"
```

---

### Task 9: ProcessingRecoveryService (TDD)

**Files:**
- Create: `payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java`
- Create: `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessingRecoveryService")
class ProcessingRecoveryServiceTest {

    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock CancelRequestHistoryRepository historyRepository;
    @Mock RiskManagementPort riskManagementPort;
    @Mock CompensationRetryRepository compensationRetryRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PgCancelPort pgCancelPort;
    @Mock CancelTxWriter cancelTxWriter;
    @Mock OperationAlertPort operationAlertPort;

    ProcessingRecoveryService service;
    Payment payment;           // merchantId=1L, paymentKey="pay_test_001"
    CancelRequest processing;  // paymentId=1L, cancelAmount=50000, cancelItemIds=[10,11], pgRetryCount=0

    @BeforeEach
    void setUp() {
        service = new ProcessingRecoveryService(
            cancelRequestRepository, historyRepository,
            riskManagementPort, compensationRetryRepository,
            paymentRepository, pgCancelPort, cancelTxWriter, operationAlertPort
        );
        payment = PaymentFixture.completedPayment();
        processing = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 0,
            null, null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
            Instant.now().minus(10, ChronoUnit.MINUTES)
        );
    }

    @Test
    @DisplayName("PG 조회 실패 시 PROCESSING 유지 (skip)")
    void pg_get_status_exception_keeps_processing() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenThrow(new RuntimeException("PG 연결 실패"));

        service.recoverAll();

        verify(cancelRequestRepository, never()).save(any());
        verify(cancelTxWriter, never()).saveTx3(any(), any(), any());
    }

    @Test
    @DisplayName("PG APPROVED → TX3 재실행 + COMPLETED 이력")
    void pg_approved_runs_tx3() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.approved("pg_tx_001"));
        when(cancelTxWriter.saveTx3(any(), any(), any())).thenReturn(processing);

        service.recoverAll();

        verify(cancelTxWriter).saveTx3(eq(processing), eq(payment), eq(List.of(10L, 11L)));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.COMPLETED), anyString());
    }

    @Test
    @DisplayName("PG FAILED retryable=false → 보상 + FAILED + 이력")
    void pg_failed_non_retryable_compensates_and_fails() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.failed("pg_tx_001"));

        service.recoverAll();

        verify(riskManagementPort).compensate(eq(10L), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(cancelRequestRepository).save(any());
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("PG FAILED retryable=true, pgRetryCount=0 → PG 재호출 성공 시 TX3")
    void pg_failed_retryable_retries_pg_and_succeeds() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.retryableFailed("pg_tx_001"));
        when(pgCancelPort.cancel(anyString(), any(), anyString())).thenReturn(PgCancelResult.approved("pg_tx_002"));
        when(cancelTxWriter.saveTx3(any(), any(), any())).thenReturn(processing);

        service.recoverAll();

        verify(pgCancelPort).cancel(eq("pay_test_001"), eq(BigDecimal.valueOf(50000)), anyString());
        verify(cancelTxWriter).saveTx3(any(), eq(payment), eq(List.of(10L, 11L)));
    }

    @Test
    @DisplayName("PG FAILED retryable=true, pgRetryCount=5(최대) → 재호출 없이 보상 + FAILED")
    void pg_failed_retryable_max_retries_compensates() {
        CancelRequest maxRetry = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 5,
            null, null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
            Instant.now().minus(10, ChronoUnit.MINUTES)
        );
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(maxRetry));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.retryableFailed("pg_tx_001"));

        service.recoverAll();

        verify(pgCancelPort, never()).cancel(anyString(), any(), anyString());
        verify(riskManagementPort).compensate(anyLong(), anyLong(), any());
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("PG PENDING 최초 → markPgPending 저장, 보상/알림 없음")
    void pg_pending_first_time_marks_pg_pending() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.pending("pg_tx_001"));

        service.recoverAll();

        verify(cancelRequestRepository, times(1)).save(any());
        verify(riskManagementPort, never()).compensate(anyLong(), anyLong(), any());
        verifyNoInteractions(operationAlertPort);
    }

    @Test
    @DisplayName("PG PENDING 1시간 초과 → 보상 + FAILED + 운영팀 알림")
    void pg_pending_timeout_compensates_and_alerts() {
        CancelRequest timedOut = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 0,
            null,
            Instant.now().minus(2, ChronoUnit.HOURS),  // pgPendingSince 2시간 전
            Instant.now().minus(2, ChronoUnit.HOURS),
            Instant.now().minus(2, ChronoUnit.HOURS)
        );
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(timedOut));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.pending("pg_tx_001"));

        service.recoverAll();

        verify(riskManagementPort).compensate(anyLong(), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(operationAlertPort).alertPgPendingTimeout(eq(10L), eq("pay_test_001"), any(Instant.class));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("대상 없으면 아무 작업 없음")
    void no_stale_processing_does_nothing() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of());

        service.recoverAll();

        verifyNoInteractions(pgCancelPort, cancelTxWriter, riskManagementPort, operationAlertPort);
    }
}
```

- [ ] **Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew :payment-service:test --tests "com.example.payment.application.service.ProcessingRecoveryServiceTest" -i
```

Expected: FAIL — `ProcessingRecoveryService` 클래스 없음.

- [ ] **Step 3: ProcessingRecoveryService 구현**

```java
package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PROCESSING 5분 초과 건 복구.
 *
 * PG 조회 실패  → PROCESSING 유지
 * PG APPROVED  → TX3 재실행
 * PG FAILED    → retryable: 재호출(최대 5회) / 비retryable: 보상+FAILED
 * PG PENDING   → markPgPending, 1시간 초과 시 보상+FAILED+운영팀 알림
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingRecoveryService {

    private static final Duration PROCESSING_THRESHOLD = Duration.ofMinutes(5);
    private static final Duration PG_PENDING_TIMEOUT = Duration.ofHours(1);
    private static final int MAX_PG_RETRIES = 5;

    private final CancelRequestRepository cancelRequestRepository;
    private final CancelRequestHistoryRepository historyRepository;
    private final RiskManagementPort riskManagementPort;
    private final CompensationRetryRepository compensationRetryRepository;
    private final PaymentRepository paymentRepository;
    private final PgCancelPort pgCancelPort;
    private final CancelTxWriter cancelTxWriter;
    private final OperationAlertPort operationAlertPort;

    public void recoverAll() {
        Instant threshold = Instant.now().minus(PROCESSING_THRESHOLD);
        List<CancelRequest> stale = cancelRequestRepository.findProcessingUpdatedBefore(threshold);
        log.info("[processing-recovery] 대상={}건 threshold={}", stale.size(), threshold);
        stale.forEach(this::recoverOne);
    }

    private void recoverOne(CancelRequest cancelRequest) {
        try {
            Payment payment = paymentRepository.findById(cancelRequest.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + cancelRequest.getPaymentId()));

            PgCancelResult result;
            try {
                result = pgCancelPort.getStatus(payment.getPaymentKey());
            } catch (Exception e) {
                log.warn("[processing-recovery] PG 조회 실패, PROCESSING 유지 cancelRequestId={}: {}",
                    cancelRequest.getId(), e.getMessage());
                return;
            }

            if (result.isApproved()) {
                runTx3(cancelRequest, payment);
            } else if (result.isFailed()) {
                handleFailed(cancelRequest, payment, result);
            } else if (result.isPending()) {
                handlePgPending(cancelRequest, payment);
            }
        } catch (Exception e) {
            log.warn("[processing-recovery] 처리 실패 cancelRequestId={}: {}",
                cancelRequest.getId(), e.getMessage());
        }
    }

    private void runTx3(CancelRequest cancelRequest, Payment payment) {
        CancelRequest completed = cancelTxWriter.saveTx3(
            cancelRequest, payment, cancelRequest.getCancelItemIds());
        recordHistory(completed.getId(), CancelStatus.COMPLETED, "processing-recovery");
    }

    private void handleFailed(CancelRequest cancelRequest, Payment payment, PgCancelResult result) {
        if (result.isRetryable() && cancelRequest.getPgRetryCount() < MAX_PG_RETRIES) {
            retryPgCancel(cancelRequest, payment);
        } else {
            compensateAndFail(cancelRequest, payment);
        }
    }

    private void retryPgCancel(CancelRequest cancelRequest, Payment payment) {
        cancelRequest.incrementPgRetryCount();
        cancelRequestRepository.save(cancelRequest);

        PgCancelResult retryResult;
        try {
            retryResult = pgCancelPort.cancel(
                payment.getPaymentKey(), cancelRequest.getCancelAmount(), cancelRequest.getCancelReason());
        } catch (Exception e) {
            log.warn("[processing-recovery] PG 재호출 실패 #{} cancelRequestId={}: {}",
                cancelRequest.getPgRetryCount(), cancelRequest.getId(), e.getMessage());
            if (cancelRequest.getPgRetryCount() >= MAX_PG_RETRIES) {
                compensateAndFail(cancelRequest, payment);
            }
            return;
        }

        if (retryResult.isApproved()) {
            runTx3(cancelRequest, payment);
        } else if (cancelRequest.getPgRetryCount() >= MAX_PG_RETRIES) {
            compensateAndFail(cancelRequest, payment);
        }
    }

    private void handlePgPending(CancelRequest cancelRequest, Payment payment) {
        cancelRequest.markPgPending();
        cancelRequestRepository.save(cancelRequest);

        if (cancelRequest.getPgPendingSince() != null
                && cancelRequest.getPgPendingSince().plus(PG_PENDING_TIMEOUT).isBefore(Instant.now())) {
            compensateAndFail(cancelRequest, payment);
            operationAlertPort.alertPgPendingTimeout(
                cancelRequest.getId(), payment.getPaymentKey(), cancelRequest.getPgPendingSince());
        }
    }

    private void compensateAndFail(CancelRequest cancelRequest, Payment payment) {
        try {
            riskManagementPort.compensate(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        } catch (Exception ex) {
            log.warn("[processing-recovery] 보상 실패 cancelRequestId={}: {}",
                cancelRequest.getId(), ex.getMessage());
            compensationRetryRepository.save(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        }
        cancelRequest.toFailed();
        cancelRequestRepository.save(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.FAILED, "processing-recovery");
    }

    private void recordHistory(long cancelRequestId, CancelStatus status, String reason) {
        try {
            historyRepository.record(cancelRequestId, status, reason);
        } catch (Exception e) {
            log.warn("[processing-recovery] 이력 기록 실패 (비즈니스 영향 없음) cancelRequestId={}", cancelRequestId, e);
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 — PASS 확인**

```bash
./gradlew :payment-service:test --tests "com.example.payment.application.service.ProcessingRecoveryServiceTest" -i
```

Expected: 8개 테스트 PASS.

- [ ] **Step 5: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java \
        payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java
git commit -m "feat(payment): ProcessingRecoveryService — PROCESSING 5분 초과 복구 로직"
```

---

### Task 10: 스케줄러 스텁 채우기 + 최종 검증

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/PendingRecoveryScheduler.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/ProcessingRecoveryScheduler.java`

- [ ] **Step 1: PendingRecoveryScheduler 전체 교체**

```java
package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.PendingRecoveryService;
import lombok.RequiredArgsConstructor;
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
public class PendingRecoveryScheduler {

    private final RedissonClient redissonClient;
    private final PendingRecoveryService pendingRecoveryService;

    @Value("${scheduler.lock.pending-recovery}")
    private String lockKey;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[pending-recovery] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[pending-recovery] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            pendingRecoveryService.recoverAll();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 2: ProcessingRecoveryScheduler 전체 교체**

```java
package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.ProcessingRecoveryService;
import lombok.RequiredArgsConstructor;
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
public class ProcessingRecoveryScheduler {

    private final RedissonClient redissonClient;
    private final ProcessingRecoveryService processingRecoveryService;

    @Value("${scheduler.lock.processing-recovery}")
    private String lockKey;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[processing-recovery] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[processing-recovery] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            processingRecoveryService.recoverAll();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 3: 전체 테스트 실행**

```bash
./gradlew :payment-service:test -i
```

Expected: 전체 PASS.

- [ ] **Step 4: 최종 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/scheduler/PendingRecoveryScheduler.java \
        payment-service/src/main/java/com/example/payment/infrastructure/scheduler/ProcessingRecoveryScheduler.java
git commit -m "feat(payment): 스케줄러 구현 완료 — PendingRecoveryScheduler/ProcessingRecoveryScheduler"
```
