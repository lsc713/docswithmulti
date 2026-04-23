# payment-service CancelPaymentService 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** payment-service의 CancelPaymentService.cancel() 전체 플로우 구현 (TX1 → Risk → TX2 → PG → TX3 → Outbox)

**Architecture:** 기존 도메인을 CLAUDE.md 설계(request_hash 멱등성, 아이템 전액 취소)로 정렬(V8 DDL + 엔티티 수정) 후 Application → Infrastructure → Presentation 순 구현. 외부 의존(Risk, PG)은 Port 인터페이스로 격리.

**Tech Stack:** Java 21, Spring Boot 4.x, Spring Data JPA, Resilience4j, Feign, Flyway, JUnit5 + Mockito + Testcontainers

---

## 파일 구조

```
payment-service/
├── db/migration/
│   └── V8__align_cancel_schema.sql                          [CREATE]
├── src/main/java/com/example/payment/
│   ├── domain/entity/
│   │   ├── CancelRequest.java                               [MODIFY] idempotencyKey→requestHash, raiseToPending()
│   │   ├── PaymentItem.java                                 [MODIFY] cancelPartially()→cancel(), PARTIAL_CANCELLED 제거
│   │   └── PaymentItemStatus.java                          [MODIFY] PARTIAL_CANCELLED 제거
│   ├── domain/service/
│   │   └── CancelDomainService.java                        [MODIFY] paymentItemId 기반 전액 취소
│   ├── application/
│   │   ├── usecase/CancelPaymentUseCase.java               [CREATE]
│   │   ├── service/
│   │   │   ├── CancelPaymentService.java                   [CREATE] 핵심 플로우
│   │   │   └── CancelPaymentCommand.java                   [CREATE]
│   │   ├── interfaces/
│   │   │   ├── PaymentRepository.java                      [CREATE]
│   │   │   ├── PaymentItemRepository.java                  [CREATE]
│   │   │   ├── CancelRequestRepository.java                [CREATE]
│   │   │   ├── CancelRequestHistoryRepository.java         [CREATE]
│   │   │   ├── CancelEventOutboxRepository.java            [CREATE]
│   │   │   ├── CompensationRetryRepository.java            [CREATE]
│   │   │   ├── RiskManagementPort.java                     [CREATE]
│   │   │   └── PgCancelPort.java                           [CREATE]
│   │   ├── dto/
│   │   │   ├── RiskReserveResult.java                      [CREATE]
│   │   │   └── PgCancelResult.java                         [CREATE]
│   │   └── exception/
│   │       ├── PaymentNotFoundException.java               [CREATE]
│   │       └── CancelRequestNotFoundException.java         [CREATE]
│   ├── infrastructure/
│   │   ├── persistence/
│   │   │   ├── entity/ (JPA 엔티티 6개)                    [CREATE]
│   │   │   └── adapter/ (Repository 구현체 6개)            [CREATE]
│   │   ├── http/
│   │   │   ├── RiskManagementHttpClient.java               [CREATE]
│   │   │   └── PgCancelHttpClient.java                     [CREATE]
│   │   └── config/
│   │       ├── Resilience4jConfig.java                     [CREATE]
│   │       └── FeignConfig.java                            [CREATE]
│   └── presentation/
│       ├── controller/CancelController.java                [CREATE]
│       ├── dto/
│       │   ├── CancelPaymentRequest.java                   [CREATE]
│       │   └── CancelPaymentResponse.java                  [CREATE]
│       └── advice/GlobalExceptionHandler.java              [CREATE]
└── src/test/java/com/example/payment/
    ├── domain/entity/ (기존 테스트 수정)
    ├── application/service/CancelPaymentServiceTest.java   [CREATE]
    └── fixture/ (PaymentItemFixture 수정)
```

---

## Task 1: V8 DDL — 스키마 정렬

**Files:**
- Create: `payment-service/src/main/resources/db/migration/V8__align_cancel_schema.sql`

- [ ] **Step 1: V8 마이그레이션 파일 작성**

```sql
-- V8__align_cancel_schema.sql
-- cancel_request: idempotency_key → request_hash (payment_id, request_hash) UK
-- cancel_request_history: 상태 이력 테이블 신규
-- cancel_request_item: 제거 (아이템 전액 취소로 단순화)
-- payment_item: cancelled_amount 제거, PARTIAL_CANCELLED 상태 제거

-- 1. cancel_request 스키마 변경
ALTER TABLE cancel_request
    DROP KEY uk_cancel_request_idempotency_key,
    DROP COLUMN idempotency_key,
    DROP COLUMN canceller_type,
    DROP COLUMN cancelled_by,
    DROP COLUMN processing_started_at,
    DROP COLUMN failed_reason,
    ADD COLUMN request_hash     VARCHAR(64)   NOT NULL AFTER payment_id,
    ADD COLUMN pg_pending_since DATETIME(3)   NULL     AFTER status,
    ADD UNIQUE KEY uk_cancel_request_hash (payment_id, request_hash);

-- 2. cancel_request_history 신규 생성
CREATE TABLE cancel_request_history
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    cancel_request_id BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    reason            VARCHAR(500) NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    INDEX idx_cancel_request_history_cancel_request_id (cancel_request_id)
);

-- 3. cancel_request_item 제거 (아이템 전액 취소, 별도 테이블 불필요)
DROP TABLE IF EXISTS cancel_request_item;

-- 4. payment_item: cancelled_amount 제거
ALTER TABLE payment_item
    DROP COLUMN cancelled_amount;

-- 5. idempotency_key 테이블 제거 (request_hash UK로 대체)
DROP TABLE IF EXISTS idempotency_key;

-- 6. shedlock 테이블 제거 (Redis 분산락으로 대체)
DROP TABLE IF EXISTS shedlock;
```

- [ ] **Step 2: 컴파일 확인 (마이그레이션만, 실행은 Task 6 이후)**

```bash
./gradlew :payment-service:compileJava
```

Expected: 컴파일 에러 발생 (cancelledAmount, idempotencyKey 참조 — 다음 Task에서 수정)

---

## Task 2: PaymentItem 도메인 — 전액 취소로 단순화

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/domain/entity/PaymentItem.java`
- Modify: `payment-service/src/main/java/com/example/payment/domain/entity/PaymentItemStatus.java`
- Modify: `payment-service/src/test/java/com/example/payment/domain/entity/PaymentItemTest.java`
- Modify: `payment-service/src/test/java/com/example/payment/fixture/PaymentItemFixture.java`

- [ ] **Step 1: 실패하는 테스트 먼저 작성 — cancel() 전액 취소**

`payment-service/src/test/java/com/example/payment/domain/entity/PaymentItemTest.java` 전체 교체:

```java
package com.example.payment.domain.entity;

import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentItem 도메인 엔티티")
class PaymentItemTest {

    private PaymentItem item;

    @BeforeEach
    void setUp() {
        item = PaymentItem.of(1L, 10L, 100L, 200L, "상품A", BigDecimal.valueOf(30000));
    }

    @Nested
    @DisplayName("ACTIVE 상태일 때")
    class WhenActive {

        @Test
        @DisplayName("should_cancel_item_and_transition_to_cancelled")
        void shouldCancelItemAndTransitionToCancelled() {
            item.cancel();
            assertEquals(PaymentItemStatus.CANCELLED, item.getStatus());
        }

        @Test
        @DisplayName("should_return_true_for_cancellable")
        void shouldReturnTrueForCancellable() {
            assertTrue(item.isCancellable());
        }
    }

    @Nested
    @DisplayName("CANCELLED 상태일 때")
    class WhenCancelled {

        @BeforeEach
        void cancel() {
            item.cancel();
        }

        @Test
        @DisplayName("should_throw_when_cancel_called_again")
        void shouldThrowWhenCancelCalledAgain() {
            assertThrows(InvalidPaymentItemStatusException.class, item::cancel);
        }

        @Test
        @DisplayName("should_return_false_for_cancellable")
        void shouldReturnFalseForCancellable() {
            assertFalse(item.isCancellable());
        }
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :payment-service:test --tests "*.PaymentItemTest" 2>&1 | tail -20
```

Expected: FAIL — `cancel()` 메서드 없음

- [ ] **Step 3: PaymentItemStatus에서 PARTIAL_CANCELLED 제거**

`PaymentItemStatus.java` 전체 교체:

```java
package com.example.payment.domain.entity;

public enum PaymentItemStatus {
    ACTIVE("활성"),
    CANCELLED("전액 취소");

    private final String description;

    PaymentItemStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }

    public boolean isCancellable() { return this == ACTIVE; }

    public boolean isFinal() { return this == CANCELLED; }
}
```

- [ ] **Step 4: PaymentItem에 cancel() 추가, cancelPartially() 제거**

`PaymentItem.java` 전체 교체:

```java
package com.example.payment.domain.entity;

import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import java.math.BigDecimal;
import java.util.Objects;

public class PaymentItem {

    private final long id;
    private final long paymentId;
    private final long orderItemId;
    private final long productId;
    private final long productAutoId;
    private final String itemName;
    private final BigDecimal itemAmount;
    private PaymentItemStatus status;

    private PaymentItem(
        long id, long paymentId, long orderItemId,
        long productId, long productAutoId,
        String itemName, BigDecimal itemAmount,
        PaymentItemStatus status
    ) {
        this.id = id;
        this.paymentId = paymentId;
        this.orderItemId = orderItemId;
        this.productId = productId;
        this.productAutoId = productAutoId;
        this.itemName = itemName;
        this.itemAmount = itemAmount;
        this.status = status;
    }

    public static PaymentItem of(
        long paymentId, long orderItemId,
        long productId, long productAutoId,
        String itemName, BigDecimal itemAmount
    ) {
        return new PaymentItem(0, paymentId, orderItemId, productId, productAutoId,
            itemName, itemAmount, PaymentItemStatus.ACTIVE);
    }

    /** 아이템 전액 취소. ACTIVE 상태에서만 가능. */
    public void cancel() {
        if (!isCancellable()) {
            throw new InvalidPaymentItemStatusException(id, status);
        }
        this.status = PaymentItemStatus.CANCELLED;
    }

    public boolean isCancellable() { return status.isCancellable(); }

    public long getId() { return id; }
    public long getPaymentId() { return paymentId; }
    public long getOrderItemId() { return orderItemId; }
    public long getProductId() { return productId; }
    public long getProductAutoId() { return productAutoId; }
    public String getItemName() { return itemName; }
    public BigDecimal getItemAmount() { return itemAmount; }
    public PaymentItemStatus getStatus() { return status; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentItem that = (PaymentItem) o;
        return id == that.id && paymentId == that.paymentId;
    }

    @Override
    public int hashCode() { return Objects.hash(id, paymentId); }
}
```

- [ ] **Step 5: InvalidPaymentItemStatusException 수정 (id, status 파라미터)**

`payment-service/src/main/java/com/example/payment/domain/exception/InvalidPaymentItemStatusException.java` 전체 교체:

```java
package com.example.payment.domain.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.PaymentItemStatus;

public class InvalidPaymentItemStatusException extends BusinessException {

    public InvalidPaymentItemStatusException(long paymentItemId, PaymentItemStatus currentStatus) {
        super(ErrorCode.INVALID_PAYMENT_ITEM_STATUS,
            String.format("이미 취소된 항목입니다. paymentItemId=%d, status=%s", paymentItemId, currentStatus));
    }
}
```

- [ ] **Step 6: PaymentItemFixture 수정**

`payment-service/src/test/java/com/example/payment/fixture/PaymentItemFixture.java` 전체 교체:

```java
package com.example.payment.fixture;

import com.example.payment.domain.entity.PaymentItem;
import java.math.BigDecimal;

public class PaymentItemFixture {

    public static PaymentItem active(long paymentId, long orderItemId, BigDecimal amount) {
        return PaymentItem.of(paymentId, orderItemId, 100L, 200L, "상품", amount);
    }

    public static PaymentItem cancelled(long paymentId, long orderItemId, BigDecimal amount) {
        PaymentItem item = active(paymentId, orderItemId, amount);
        item.cancel();
        return item;
    }

    private PaymentItemFixture() {}
}
```

- [ ] **Step 7: 테스트 실행 — 통과 확인**

```bash
./gradlew :payment-service:test --tests "*.PaymentItemTest" 2>&1 | tail -20
```

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/domain/entity/PaymentItem.java \
        payment-service/src/main/java/com/example/payment/domain/entity/PaymentItemStatus.java \
        payment-service/src/main/java/com/example/payment/domain/exception/InvalidPaymentItemStatusException.java \
        payment-service/src/test/java/com/example/payment/domain/entity/PaymentItemTest.java \
        payment-service/src/test/java/com/example/payment/fixture/PaymentItemFixture.java
git commit -m "refactor: PaymentItem 아이템 전액 취소로 단순화, PARTIAL_CANCELLED 제거"
```

---

## Task 3: CancelRequest 도메인 — request_hash 멱등성

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/domain/entity/CancelRequest.java`
- Modify: `payment-service/src/test/java/com/example/payment/domain/entity/CancelRequestTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`CancelRequestTest.java` 전체 교체:

```java
package com.example.payment.domain.entity;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.exception.InvalidCancelAmountException;
import com.example.payment.domain.exception.InvalidCancelStateTransitionException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelRequest 도메인 엔티티")
class CancelRequestTest {

    private CancelRequest cancelRequest;

    @BeforeEach
    void setUp() {
        cancelRequest = CancelRequest.create(1L, "hash-abc123", new BigDecimal("100000"), "고객 변심");
    }

    @Test
    @DisplayName("should_create_with_pending_status_and_request_hash")
    void shouldCreateWithPendingStatusAndRequestHash() {
        assertEquals(1L, cancelRequest.getPaymentId());
        assertEquals("hash-abc123", cancelRequest.getRequestHash());
        assertEquals(new BigDecimal("100000"), cancelRequest.getCancelAmount());
        assertEquals(CancelStatus.PENDING, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getCreatedAt());
    }

    @Test
    @DisplayName("should_reject_zero_cancel_amount")
    void shouldRejectZeroCancelAmount() {
        InvalidCancelAmountException ex = assertThrows(InvalidCancelAmountException.class,
            () -> CancelRequest.create(1L, "hash-xyz", BigDecimal.ZERO, "변심"));
        assertEquals(ErrorCode.INVALID_CANCEL_AMOUNT, ex.getErrorCode());
    }

    @Test
    @DisplayName("should_transition_pending_to_processing")
    void shouldTransitionPendingToProcessing() {
        cancelRequest.toProcessing();
        assertEquals(CancelStatus.PROCESSING, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getProcessingStartedAt());
    }

    @Test
    @DisplayName("should_transition_processing_to_completed")
    void shouldTransitionProcessingToCompleted() {
        cancelRequest.toProcessing();
        cancelRequest.toCompleted();
        assertEquals(CancelStatus.COMPLETED, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getCompletedAt());
    }

    @Test
    @DisplayName("should_transition_processing_to_failed")
    void shouldTransitionProcessingToFailed() {
        cancelRequest.toProcessing();
        cancelRequest.toFailed("DB 타임아웃");
        assertEquals(CancelStatus.FAILED, cancelRequest.getStatus());
        assertEquals("DB 타임아웃", cancelRequest.getFailedReason());
    }

    @Test
    @DisplayName("should_allow_failed_to_raise_to_pending_for_retry")
    void shouldAllowFailedToRaiseToPendingForRetry() {
        cancelRequest.toProcessing();
        cancelRequest.toFailed("일시 오류");
        cancelRequest.raiseToPending();
        assertEquals(CancelStatus.PENDING, cancelRequest.getStatus());
        assertNull(cancelRequest.getFailedReason());
    }

    @Test
    @DisplayName("should_reject_raise_to_pending_from_non_failed_status")
    void shouldRejectRaiseToPendingFromNonFailedStatus() {
        assertThrows(InvalidCancelStateTransitionException.class,
            cancelRequest::raiseToPending);
    }

    @Test
    @DisplayName("should_reject_transition_from_completed")
    void shouldRejectTransitionFromCompleted() {
        cancelRequest.toProcessing();
        cancelRequest.toCompleted();
        assertThrows(InvalidCancelStateTransitionException.class, cancelRequest::toProcessing);
        assertThrows(InvalidCancelStateTransitionException.class, () -> cancelRequest.toFailed("x"));
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :payment-service:test --tests "*.CancelRequestTest" 2>&1 | tail -20
```

Expected: FAIL — `requestHash`, `raiseToPending()` 없음

- [ ] **Step 3: CancelRequest 엔티티 교체**

`CancelRequest.java` 전체 교체:

```java
package com.example.payment.domain.entity;

import com.example.payment.domain.exception.InvalidCancelAmountException;
import com.example.payment.domain.exception.InvalidCancelStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;

public class CancelRequest {

    private Long id;
    private Long paymentId;
    private String requestHash;
    private BigDecimal cancelAmount;
    private String cancelReason;
    private CancelStatus status;
    private Instant processingStartedAt;
    private Instant completedAt;
    private String failedReason;
    private Instant pgPendingSince;
    private Instant createdAt;
    private Instant updatedAt;

    private CancelRequest(Long paymentId, String requestHash,
                          BigDecimal cancelAmount, String cancelReason) {
        validateCancelAmount(cancelAmount);
        this.paymentId = paymentId;
        this.requestHash = requestHash;
        this.cancelAmount = cancelAmount;
        this.cancelReason = cancelReason;
        this.status = CancelStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public static CancelRequest create(Long paymentId, String requestHash,
                                        BigDecimal cancelAmount, String cancelReason) {
        return new CancelRequest(paymentId, requestHash, cancelAmount, cancelReason);
    }

    /** PENDING → PROCESSING */
    public void toProcessing() {
        if (status != CancelStatus.PENDING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PROCESSING);
        }
        this.status = CancelStatus.PROCESSING;
        this.processingStartedAt = Instant.now();
    }

    /** PROCESSING → COMPLETED */
    public void toCompleted() {
        if (status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.COMPLETED);
        }
        this.status = CancelStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** PROCESSING → FAILED */
    public void toFailed(String reason) {
        if (status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.FAILED);
        }
        this.status = CancelStatus.FAILED;
        this.failedReason = reason;
    }

    /** FAILED → PENDING (재시도 허용) */
    public void raiseToPending() {
        if (status != CancelStatus.FAILED) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PENDING);
        }
        this.status = CancelStatus.PENDING;
        this.failedReason = null;
        this.processingStartedAt = null;
    }

    private void validateCancelAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidCancelAmountException(amount);
        }
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getRequestHash() { return requestHash; }
    public BigDecimal getCancelAmount() { return cancelAmount; }
    public String getCancelReason() { return cancelReason; }
    public CancelStatus getStatus() { return status; }
    public Instant getProcessingStartedAt() { return processingStartedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getFailedReason() { return failedReason; }
    public Instant getPgPendingSince() { return pgPendingSince; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
./gradlew :payment-service:test --tests "*.CancelRequestTest" 2>&1 | tail -20
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/domain/entity/CancelRequest.java \
        payment-service/src/test/java/com/example/payment/domain/entity/CancelRequestTest.java
git commit -m "refactor: CancelRequest request_hash 멱등성, raiseToPending() 추가"
```

---

## Task 4: CancelDomainService — paymentItemId 기반 전액 취소

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/domain/service/CancelDomainService.java`
- Modify: `payment-service/src/main/java/com/example/payment/domain/service/CancelItemCommand.java`
- Modify: `payment-service/src/test/java/com/example/payment/domain/service/CancelDomainServiceTest.java`

- [ ] **Step 1: CancelItemCommand — paymentItemId만 보유하도록 단순화**

`CancelItemCommand.java` 전체 교체:

```java
package com.example.payment.domain.service;

public class CancelItemCommand {

    private final long paymentItemId;

    private CancelItemCommand(long paymentItemId) {
        this.paymentItemId = paymentItemId;
    }

    public static CancelItemCommand of(long paymentItemId) {
        return new CancelItemCommand(paymentItemId);
    }

    public long getPaymentItemId() { return paymentItemId; }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`CancelDomainServiceTest.java` 전체 교체:

```java
package com.example.payment.domain.service;

import com.example.payment.domain.entity.*;
import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import com.example.payment.domain.exception.InvalidPaymentStatusException;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.fixture.PaymentFixture;
import com.example.payment.fixture.PaymentItemFixture;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelDomainService")
class CancelDomainServiceTest {

    private CancelDomainService service;

    @BeforeEach
    void setUp() {
        // 2026-03-01: PaymentFixture.completedPayment() 결제일(2026-01-01) 기준 90일 이내
        Clock clock = Clock.fixed(
            Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        service = new CancelDomainService(new CancelPeriodPolicy(clock));
    }

    @Test
    @DisplayName("should_cancel_target_items_and_set_payment_partial_cancelled")
    void shouldCancelTargetItemsAndSetPaymentPartialCancelled() {
        Payment payment = PaymentFixture.completedPayment(); // totalAmount=100000
        PaymentItem itemA = PaymentItemFixture.active(payment.getId(), 10L, BigDecimal.valueOf(30000));
        PaymentItem itemB = PaymentItemFixture.active(payment.getId(), 11L, BigDecimal.valueOf(70000));

        List<CancelItemCommand> commands = List.of(CancelItemCommand.of(itemA.getId()));

        PaymentStatus newStatus = service.apply(payment, commands, List.of(itemA, itemB));

        assertEquals(PaymentItemStatus.CANCELLED, itemA.getStatus());
        assertEquals(PaymentItemStatus.ACTIVE, itemB.getStatus());
        assertEquals(PaymentStatus.PARTIAL_CANCELLED, newStatus);
    }

    @Test
    @DisplayName("should_set_payment_cancelled_when_all_items_cancelled")
    void shouldSetPaymentCancelledWhenAllItemsCancelled() {
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem itemA = PaymentItemFixture.active(payment.getId(), 10L, BigDecimal.valueOf(30000));
        PaymentItem itemB = PaymentItemFixture.active(payment.getId(), 11L, BigDecimal.valueOf(70000));

        List<CancelItemCommand> commands = List.of(
            CancelItemCommand.of(itemA.getId()),
            CancelItemCommand.of(itemB.getId())
        );

        PaymentStatus newStatus = service.apply(payment, commands, List.of(itemA, itemB));

        assertEquals(PaymentStatus.CANCELLED, newStatus);
    }

    @Test
    @DisplayName("should_throw_when_payment_not_cancellable")
    void shouldThrowWhenPaymentNotCancellable() {
        Payment cancelled = PaymentFixture.cancelledPayment();
        PaymentItem item = PaymentItemFixture.active(cancelled.getId(), 10L, BigDecimal.valueOf(30000));

        assertThrows(InvalidPaymentStatusException.class,
            () -> service.apply(cancelled, List.of(CancelItemCommand.of(item.getId())), List.of(item)));
    }

    @Test
    @DisplayName("should_throw_when_target_item_already_cancelled")
    void shouldThrowWhenTargetItemAlreadyCancelled() {
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem cancelledItem = PaymentItemFixture.cancelled(payment.getId(), 10L, BigDecimal.valueOf(30000));

        assertThrows(InvalidPaymentItemStatusException.class,
            () -> service.apply(payment, List.of(CancelItemCommand.of(cancelledItem.getId())), List.of(cancelledItem)));
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

```bash
./gradlew :payment-service:test --tests "*.CancelDomainServiceTest" 2>&1 | tail -20
```

Expected: FAIL — `CancelItemCommand.of(paymentItemId)` 시그니처 불일치

- [ ] **Step 4: CancelDomainService 재작성**

`CancelDomainService.java` 전체 교체:

```java
package com.example.payment.domain.service;

import com.example.payment.domain.entity.*;
import com.example.payment.domain.exception.PaymentItemNotFoundException;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.policy.PaymentItemStatusPolicy;
import com.example.payment.domain.policy.PaymentStatusPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 취소 도메인 서비스
 *
 * 검증 → 대상 항목 전액 취소 → Payment 상태 재계산
 * CancelRequest 상태 전이는 포함하지 않음 (Application 레이어 책임)
 */
public class CancelDomainService {

    private final CancelPeriodPolicy cancelPeriodPolicy;

    public CancelDomainService(CancelPeriodPolicy cancelPeriodPolicy) {
        this.cancelPeriodPolicy = cancelPeriodPolicy;
    }

    /**
     * @param payment         대상 결제
     * @param cancelItems     취소할 paymentItemId 목록
     * @param allPaymentItems Payment에 속한 전체 PaymentItem (FOR UPDATE 재조회 결과)
     * @return 취소 후 Payment 신규 상태
     */
    public PaymentStatus apply(
        Payment payment,
        List<CancelItemCommand> cancelItems,
        List<PaymentItem> allPaymentItems
    ) {
        PaymentStatusPolicy.validateCancellableStatus(payment);
        cancelPeriodPolicy.validateCancelPeriod(payment);

        Set<Long> targetIds = cancelItems.stream()
            .map(CancelItemCommand::getPaymentItemId)
            .collect(Collectors.toSet());

        Map<Long, PaymentItem> itemMap = allPaymentItems.stream()
            .collect(Collectors.toMap(PaymentItem::getId, i -> i));

        for (Long targetId : targetIds) {
            PaymentItem item = itemMap.get(targetId);
            if (item == null) throw new PaymentItemNotFoundException(targetId);
            PaymentItemStatusPolicy.validateCancellableStatus(item);
            item.cancel();
        }

        return recalculatePaymentStatus(payment, allPaymentItems);
    }

    private PaymentStatus recalculatePaymentStatus(Payment payment, List<PaymentItem> allItems) {
        BigDecimal cancelledTotal = allItems.stream()
            .filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED)
            .map(PaymentItem::getItemAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentStatus newStatus = cancelledTotal.compareTo(payment.getTotalAmount()) >= 0
            ? PaymentStatus.CANCELLED
            : PaymentStatus.PARTIAL_CANCELLED;

        payment.updateStatus(newStatus);
        return newStatus;
    }
}
```

- [ ] **Step 5: PaymentItemNotFoundException — 파라미터를 long으로 수정**

`PaymentItemNotFoundException.java` 전체 교체:

```java
package com.example.payment.domain.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class PaymentItemNotFoundException extends BusinessException {

    public PaymentItemNotFoundException(long paymentItemId) {
        super(ErrorCode.PAYMENT_ITEM_NOT_FOUND,
            String.format("취소 항목을 찾을 수 없습니다. paymentItemId=%d", paymentItemId));
    }
}
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

```bash
./gradlew :payment-service:test --tests "*.CancelDomainServiceTest" 2>&1 | tail -20
```

Expected: PASS

- [ ] **Step 7: 전체 도메인 테스트 통과 확인**

```bash
./gradlew :payment-service:test --tests "com.example.payment.domain.*" 2>&1 | tail -30
```

Expected: 전체 PASS

- [ ] **Step 8: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/domain/ \
        payment-service/src/test/java/com/example/payment/domain/
git commit -m "refactor: CancelDomainService paymentItemId 기반 전액 취소로 재작성"
```

---

## Task 5: Application 포트 & 커맨드 인터페이스 정의

**Files:**
- Create: `application/usecase/CancelPaymentUseCase.java`
- Create: `application/service/CancelPaymentCommand.java`
- Create: `application/interfaces/PaymentRepository.java`
- Create: `application/interfaces/PaymentItemRepository.java`
- Create: `application/interfaces/CancelRequestRepository.java`
- Create: `application/interfaces/CancelRequestHistoryRepository.java`
- Create: `application/interfaces/CancelEventOutboxRepository.java`
- Create: `application/interfaces/CompensationRetryRepository.java`
- Create: `application/interfaces/RiskManagementPort.java`
- Create: `application/interfaces/PgCancelPort.java`
- Create: `application/dto/RiskReserveResult.java`
- Create: `application/dto/PgCancelResult.java`
- Create: `application/exception/PaymentNotFoundException.java`
- Create: `application/exception/CancelRequestNotFoundException.java`

이 Task는 인터페이스 정의만 — 테스트는 Task 6에서 Mock으로 사용.

- [ ] **Step 1: CancelPaymentCommand 작성**

`application/service/CancelPaymentCommand.java`:

```java
package com.example.payment.application.service;

import java.util.List;

public record CancelPaymentCommand(
    String paymentKey,
    String cancelReason,
    List<Long> cancelPaymentItemIds  // paymentItemId 목록 (오름차순 정렬로 request_hash 생성)
) {}
```

- [ ] **Step 2: CancelPaymentUseCase 인터페이스**

`application/usecase/CancelPaymentUseCase.java`:

```java
package com.example.payment.application.usecase;

import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.domain.entity.CancelRequest;

public interface CancelPaymentUseCase {
    CancelRequest cancel(CancelPaymentCommand command);
}
```

- [ ] **Step 3: Repository 인터페이스 4개 작성**

`application/interfaces/PaymentRepository.java`:

```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.Payment;
import java.util.Optional;

public interface PaymentRepository {
    Optional<Payment> findByPaymentKey(String paymentKey);
}
```

`application/interfaces/PaymentItemRepository.java`:

```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.PaymentItem;
import java.util.List;

public interface PaymentItemRepository {
    List<PaymentItem> findAllByPaymentIdOrderByIdAsc(long paymentId);
    /** TX 3 내부에서 최신 상태 재조회 + 비관적 락 */
    List<PaymentItem> findAllByPaymentIdForUpdate(long paymentId);
    void saveAll(List<PaymentItem> items);
}
```

`application/interfaces/CancelRequestRepository.java`:

```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CancelRequestRepository {
    Optional<CancelRequest> findByPaymentIdAndRequestHash(long paymentId, String requestHash);
    CancelRequest save(CancelRequest cancelRequest);
    /** 복구 스케줄러용: 특정 상태 + 기준 시각 이전 건 조회 */
    List<CancelRequest> findByStatusAndCreatedAtBefore(CancelStatus status, Instant before);
}
```

`application/interfaces/CancelRequestHistoryRepository.java`:

```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelStatus;

public interface CancelRequestHistoryRepository {
    /** TX 밖에서 별도 INSERT. 실패해도 비즈니스 로직에 영향 없음. */
    void record(long cancelRequestId, CancelStatus status, String reason);
}
```

- [ ] **Step 4: Outbox / CompensationRetry Repository 인터페이스**

`application/interfaces/CancelEventOutboxRepository.java`:

```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.PaymentItem;
import java.util.List;

public interface CancelEventOutboxRepository {
    /** TX 3 내부에서 호출. cancel_request_id UK로 중복 방어. */
    void insertIfAbsent(CancelRequest cancelRequest, List<PaymentItem> cancelledItems);
}
```

`application/interfaces/CompensationRetryRepository.java`:

```java
package com.example.payment.application.interfaces;

import java.math.BigDecimal;

public interface CompensationRetryRepository {
    void save(long cancelRequestId, long merchantId, BigDecimal restoreAmount);
}
```

- [ ] **Step 5: RiskManagementPort & 결과 DTO**

`application/dto/RiskReserveResult.java`:

```java
package com.example.payment.application.dto;

import java.math.BigDecimal;

public record RiskReserveResult(
    long merchantId,
    BigDecimal dailyLimit,
    BigDecimal usedAmount,
    BigDecimal remainingLimit
) {}
```

`application/interfaces/RiskManagementPort.java`:

```java
package com.example.payment.application.interfaces;

import com.example.payment.application.dto.RiskReserveResult;
import java.math.BigDecimal;
import java.time.LocalDate;

public interface RiskManagementPort {
    /** 한도 검증 + 선차감. 422 한도초과 시 MerchantCancelLimitExceededException throw. */
    RiskReserveResult validateAndReserve(long merchantId, long cancelRequestId,
                                          BigDecimal cancelAmount, LocalDate kstDate);
    /** 보상 트랜잭션. 멱등 (이미 보상됐으면 no-op). */
    void compensate(long cancelRequestId, long merchantId, BigDecimal restoreAmount);
}
```

- [ ] **Step 6: PgCancelPort & 결과 DTO**

`application/dto/PgCancelResult.java`:

```java
package com.example.payment.application.dto;

public record PgCancelResult(
    String pgTransactionId,
    String status   // "APPROVED" | "FAILED" | "PENDING"
) {
    public boolean isApproved() { return "APPROVED".equals(status); }
    public boolean isFailed()   { return "FAILED".equals(status); }
    public boolean isPending()  { return "PENDING".equals(status); }
}
```

`application/interfaces/PgCancelPort.java`:

```java
package com.example.payment.application.interfaces;

import com.example.payment.application.dto.PgCancelResult;
import java.math.BigDecimal;

public interface PgCancelPort {
    PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason);
}
```

- [ ] **Step 7: Application 예외 2개**

`application/exception/PaymentNotFoundException.java`:

```java
package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class PaymentNotFoundException extends BusinessException {

    public PaymentNotFoundException(String paymentKey) {
        super(ErrorCode.PAYMENT_NOT_FOUND,
            String.format("결제 정보를 찾을 수 없습니다. paymentKey=%s", paymentKey));
    }
}
```

`application/exception/CancelRequestNotFoundException.java`:

```java
package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class CancelRequestNotFoundException extends BusinessException {

    public CancelRequestNotFoundException(long cancelRequestId) {
        super(ErrorCode.PAYMENT_NOT_FOUND,
            String.format("취소 요청을 찾을 수 없습니다. cancelRequestId=%d", cancelRequestId));
    }
}
```

- [ ] **Step 8: 컴파일 확인**

```bash
./gradlew :payment-service:compileJava 2>&1 | tail -20
```

Expected: PASS (인터페이스만이므로 구현체 없어도 컴파일 성공)

- [ ] **Step 9: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/
git commit -m "feat: application 레이어 포트/커맨드/예외 인터페이스 정의"
```

---

## Task 6: CancelPaymentService — TX1 (PENDING INSERT + request_hash)

이 Task는 CancelPaymentService의 가장 중요한 부분: 멱등성 체크 + TX 1.

**Files:**
- Create: `application/service/CancelPaymentService.java`
- Create: `application/service/RequestHashGenerator.java`
- Create: `test/.../application/service/CancelPaymentServiceTest.java`

- [ ] **Step 1: RequestHashGenerator 작성**

`application/service/RequestHashGenerator.java`:

```java
package com.example.payment.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public class RequestHashGenerator {

    private RequestHashGenerator() {}

    /**
     * SHA-256(paymentKey + paymentItemIds 오름차순 정렬)
     * CLAUDE.md 멱등성 규칙
     */
    public static String generate(String paymentKey, List<Long> paymentItemIds) {
        List<Long> sorted = paymentItemIds.stream().sorted().toList();
        String raw = paymentKey + sorted.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성 — TX1 멱등성 케이스**

`test/java/com/example/payment/application/service/CancelPaymentServiceTest.java`:

```java
package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.fixture.PaymentFixture;
import com.example.payment.fixture.PaymentItemFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelPaymentService")
class CancelPaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentItemRepository paymentItemRepository;
    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock CancelRequestHistoryRepository historyRepository;
    @Mock CancelEventOutboxRepository outboxRepository;
    @Mock CompensationRetryRepository compensationRetryRepository;
    @Mock RiskManagementPort riskManagementPort;
    @Mock PgCancelPort pgCancelPort;

    private CancelPaymentService service;

    private Payment payment;
    private PaymentItem itemA;
    private PaymentItem itemB;
    private CancelPaymentCommand command;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        CancelDomainService domainService = new CancelDomainService(new CancelPeriodPolicy(clock));

        service = new CancelPaymentService(
            paymentRepository, paymentItemRepository, cancelRequestRepository,
            historyRepository, outboxRepository, compensationRetryRepository,
            riskManagementPort, pgCancelPort, domainService
        );

        payment = PaymentFixture.completedPayment(); // id=0, paymentKey="pay_test_001", merchantId=1
        itemA = PaymentItemFixture.active(payment.getId(), 10L, BigDecimal.valueOf(30000)); // id=0
        itemB = PaymentItemFixture.active(payment.getId(), 11L, BigDecimal.valueOf(70000));

        command = new CancelPaymentCommand("pay_test_001", "고객 변심", List.of(1L));
    }

    @Test
    @DisplayName("should_throw_payment_not_found_when_payment_missing")
    void shouldThrowPaymentNotFoundWhenPaymentMissing() {
        when(paymentRepository.findByPaymentKey("pay_test_001")).thenReturn(Optional.empty());

        assertThrows(PaymentNotFoundException.class, () -> service.cancel(command));
    }

    @Test
    @DisplayName("should_return_existing_result_when_cancel_request_completed")
    void shouldReturnExistingResultWhenCancelRequestCompleted() {
        when(paymentRepository.findByPaymentKey(any())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(itemA, itemB));

        CancelRequest existing = CancelRequest.create(
            payment.getId(), "any-hash", BigDecimal.valueOf(30000), "변심");
        existing.toProcessing();
        existing.toCompleted();

        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.of(existing));

        CancelRequest result = service.cancel(command);

        assertEquals(CancelStatus.COMPLETED, result.getStatus());
        verify(riskManagementPort, never()).validateAndReserve(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("should_raise_failed_to_pending_and_continue_when_existing_failed")
    void shouldRaiseFailedToPendingAndContinueWhenExistingFailed() {
        when(paymentRepository.findByPaymentKey(any())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(itemA, itemB));
        when(paymentItemRepository.findAllByPaymentIdForUpdate(anyLong()))
            .thenReturn(List.of(itemA, itemB));

        CancelRequest failed = CancelRequest.create(
            payment.getId(), "any-hash", BigDecimal.valueOf(30000), "변심");
        failed.toProcessing();
        failed.toFailed("이전 오류");

        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.of(failed));
        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L, BigDecimal.valueOf(5000000),
                BigDecimal.valueOf(30000), BigDecimal.valueOf(4970000)));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(new PgCancelResult("pg-tx-001", "APPROVED"));

        CancelRequest result = service.cancel(command);

        assertEquals(CancelStatus.COMPLETED, result.getStatus());
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

```bash
./gradlew :payment-service:test --tests "*.CancelPaymentServiceTest" 2>&1 | tail -20
```

Expected: FAIL — CancelPaymentService 없음

- [ ] **Step 4: CancelPaymentService 핵심 구현**

`application/service/CancelPaymentService.java`:

```java
package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.*;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.domain.service.CancelItemCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancelPaymentService implements CancelPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final CancelRequestRepository cancelRequestRepository;
    private final CancelRequestHistoryRepository historyRepository;
    private final CancelEventOutboxRepository outboxRepository;
    private final CompensationRetryRepository compensationRetryRepository;
    private final RiskManagementPort riskManagementPort;
    private final PgCancelPort pgCancelPort;
    private final CancelDomainService cancelDomainService;

    @Override
    public CancelRequest cancel(CancelPaymentCommand command) {
        // Step 1. Payment / PaymentItem 조회
        Payment payment = paymentRepository.findByPaymentKey(command.paymentKey())
            .orElseThrow(() -> new PaymentNotFoundException(command.paymentKey()));

        List<PaymentItem> items =
            paymentItemRepository.findAllByPaymentIdOrderByIdAsc(payment.getId());

        // Step 2. request_hash 생성 및 멱등성 체크
        String requestHash = RequestHashGenerator.generate(
            command.paymentKey(), command.cancelPaymentItemIds());

        var existing = cancelRequestRepository.findByPaymentIdAndRequestHash(
            payment.getId(), requestHash);

        if (existing.isPresent()) {
            return handleExistingRequest(existing.get(), command, payment, items);
        }

        return executeCancel(payment, items, requestHash, command);
    }

    /** 기존 cancel_request 상태별 처리 */
    private CancelRequest handleExistingRequest(
        CancelRequest cancelRequest, CancelPaymentCommand command,
        Payment payment, List<PaymentItem> items
    ) {
        return switch (cancelRequest.getStatus()) {
            case COMPLETED, PENDING, PROCESSING -> cancelRequest;  // 그대로 반환
            case FAILED -> {
                cancelRequest.raiseToPending();
                cancelRequestRepository.save(cancelRequest);
                recordHistory(cancelRequest.getId(), CancelStatus.PENDING, "FAILED 재시도");
                yield executeCancel(payment, items, cancelRequest.getRequestHash(), command);
            }
        };
    }

    /** TX1 → Risk → TX2 → PG → TX3 */
    private CancelRequest executeCancel(
        Payment payment, List<PaymentItem> items,
        String requestHash, CancelPaymentCommand command
    ) {
        // Step 3. Payment / PaymentItem 상태 검증 (취소 기간 포함)
        // cancelDomainService.apply()에서 검증하므로 이후 TX3에서 처리
        // 여기서는 사전 검증만 (risk 호출 전 차단)
        payment.validateCancellable();
        payment.validateCancelPeriod();
        validateTargetItemsActive(items, command.cancelPaymentItemIds());

        // Step 4. TX1: CancelRequest PENDING INSERT
        BigDecimal cancelAmount = calculateCancelAmount(items, command.cancelPaymentItemIds());
        CancelRequest cancelRequest = CancelRequest.create(
            payment.getId(), requestHash, cancelAmount, command.cancelReason());
        cancelRequest = saveTx1(cancelRequest);

        // TX1 커밋 후 별도 이력 기록
        recordHistory(cancelRequest.getId(), CancelStatus.PENDING, null);

        // Step 5. risk 호출
        callRiskAndHandleFailure(cancelRequest, payment, cancelAmount);

        // Step 6. TX2: PROCESSING UPDATE
        cancelRequest = saveTx2(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.PROCESSING, null);

        // Step 7. PG사 취소 API 호출
        PgCancelResult pgResult = pgCancelPort.cancel(
            payment.getPaymentKey(), cancelAmount, command.cancelReason());

        if (pgResult.isFailed()) {
            compensateAndFail(cancelRequest, payment.getMerchantId(), cancelAmount, "PG 취소 실패");
            return cancelRequest;
        }

        if (pgResult.isPending()) {
            // PROCESSING 유지 — 스케줄러(processing-recovery)가 처리
            log.warn("PG 취소 PENDING 상태. cancelRequestId={}", cancelRequest.getId());
            return cancelRequest;
        }

        // Step 8. TX3: PaymentItem + Payment + COMPLETED + Outbox
        return saveTx3(cancelRequest, payment, items, command.cancelPaymentItemIds());
    }

    @Transactional
    protected CancelRequest saveTx1(CancelRequest cancelRequest) {
        return cancelRequestRepository.save(cancelRequest);
    }

    @Transactional
    protected CancelRequest saveTx2(CancelRequest cancelRequest) {
        cancelRequest.toProcessing();
        return cancelRequestRepository.save(cancelRequest);
    }

    @Transactional
    protected CancelRequest saveTx3(
        CancelRequest cancelRequest, Payment payment,
        List<PaymentItem> allItems, List<Long> targetItemIds
    ) {
        // TX3: 최신 PaymentItem 재조회 (FOR UPDATE)
        List<PaymentItem> freshItems =
            paymentItemRepository.findAllByPaymentIdForUpdate(payment.getId());

        List<CancelItemCommand> commands = targetItemIds.stream()
            .map(CancelItemCommand::of)
            .toList();

        cancelDomainService.apply(payment, commands, freshItems);
        paymentItemRepository.saveAll(freshItems);

        cancelRequest.toCompleted();
        cancelRequest = cancelRequestRepository.save(cancelRequest);

        outboxRepository.insertIfAbsent(cancelRequest,
            freshItems.stream()
                .filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED &&
                    targetItemIds.contains(i.getId()))
                .toList());

        return cancelRequest;
    }

    private void callRiskAndHandleFailure(
        CancelRequest cancelRequest, Payment payment, BigDecimal cancelAmount
    ) {
        LocalDate kstDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
        try {
            riskManagementPort.validateAndReserve(
                payment.getMerchantId(), cancelRequest.getId(), cancelAmount, kstDate);
        } catch (Exception e) {
            // 보상 시도
            tryCompensate(cancelRequest, payment.getMerchantId(), cancelAmount);
            markFailed(cancelRequest, e.getMessage());
            throw e;
        }
    }

    private void tryCompensate(CancelRequest cancelRequest, long merchantId, BigDecimal amount) {
        try {
            riskManagementPort.compensate(cancelRequest.getId(), merchantId, amount);
        } catch (Exception ex) {
            log.error("보상 트랜잭션 실패. cancelRequestId={}", cancelRequest.getId(), ex);
            compensationRetryRepository.save(cancelRequest.getId(), merchantId, amount);
        }
    }

    private void compensateAndFail(
        CancelRequest cancelRequest, long merchantId, BigDecimal amount, String reason
    ) {
        tryCompensate(cancelRequest, merchantId, amount);
        markFailed(cancelRequest, reason);
    }

    private void markFailed(CancelRequest cancelRequest, String reason) {
        cancelRequest.toFailed(reason);
        cancelRequestRepository.save(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.FAILED, reason);
    }

    private void recordHistory(Long cancelRequestId, CancelStatus status, String reason) {
        try {
            historyRepository.record(cancelRequestId, status, reason);
        } catch (Exception e) {
            log.warn("이력 기록 실패 (비즈니스 영향 없음). cancelRequestId={}", cancelRequestId, e);
        }
    }

    private void validateTargetItemsActive(List<PaymentItem> items, List<Long> targetIds) {
        items.stream()
            .filter(i -> targetIds.contains(i.getId()))
            .filter(i -> !i.isCancellable())
            .findFirst()
            .ifPresent(i -> {
                throw new com.example.payment.domain.exception.InvalidPaymentItemStatusException(
                    i.getId(), i.getStatus());
            });
    }

    private BigDecimal calculateCancelAmount(List<PaymentItem> items, List<Long> targetIds) {
        return items.stream()
            .filter(i -> targetIds.contains(i.getId()))
            .map(PaymentItem::getItemAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

```bash
./gradlew :payment-service:test --tests "*.CancelPaymentServiceTest" 2>&1 | tail -30
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/
git commit -m "feat: CancelPaymentService.cancel() 전체 플로우 구현 (TX1→Risk→TX2→PG→TX3)"
```

---

## Task 7: Infrastructure — JPA 엔티티 & Repository 구현체

**Files (각각 Create):**
- `infrastructure/persistence/entity/PaymentJpaEntity.java`
- `infrastructure/persistence/entity/PaymentItemJpaEntity.java`
- `infrastructure/persistence/entity/CancelRequestJpaEntity.java`
- `infrastructure/persistence/entity/CancelRequestHistoryJpaEntity.java`
- `infrastructure/persistence/entity/CancelEventOutboxJpaEntity.java`
- `infrastructure/persistence/entity/CompensationRetryJpaEntity.java`
- `infrastructure/persistence/adapter/PaymentJpaAdapter.java`
- `infrastructure/persistence/adapter/PaymentItemJpaAdapter.java`
- `infrastructure/persistence/adapter/CancelRequestJpaAdapter.java`
- `infrastructure/persistence/adapter/CancelRequestHistoryJpaAdapter.java`
- `infrastructure/persistence/adapter/CancelEventOutboxJpaAdapter.java`
- `infrastructure/persistence/adapter/CompensationRetryJpaAdapter.java`
- `infrastructure/persistence/jpa/` (Spring Data JPA 인터페이스 6개)

- [ ] **Step 1: PaymentJpaEntity**

`infrastructure/persistence/entity/PaymentJpaEntity.java`:

```java
package com.example.payment.infrastructure.persistence.entity;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
public class PaymentJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", nullable = false, unique = true, length = 64)
    private String paymentKey;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "pg_type", nullable = false, length = 20)
    private String pgType;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "cancel_period_days", nullable = false)
    private Integer cancelPeriodDays;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PaymentJpaEntity() {}

    public Payment toDomain() {
        return Payment.reconstruct(id, paymentKey, merchantId, userId, pgType,
            totalAmount, currency, cancelPeriodDays, status, createdAt, updatedAt);
    }

    public static PaymentJpaEntity from(Payment domain) {
        PaymentJpaEntity e = new PaymentJpaEntity();
        e.id = domain.getId() == 0 ? null : domain.getId();
        e.paymentKey = domain.getPaymentKey();
        e.merchantId = domain.getMerchantId();
        e.userId = domain.getUserId();
        e.pgType = domain.getPgType();
        e.totalAmount = domain.getTotalAmount();
        e.currency = domain.getCurrency();
        e.cancelPeriodDays = domain.getCancelPeriodDays();
        e.status = domain.getStatus();
        e.createdAt = domain.getCreatedAt();
        e.updatedAt = domain.getUpdatedAt();
        return e;
    }
}
```

- [ ] **Step 2: Payment.reconstruct() 추가 (영속 계층 전용 팩토리)**

`Payment.java`에 패키지-프라이빗 정적 팩토리 추가:

```java
// Payment.java 하단에 추가
public static Payment reconstruct(
    long id, String paymentKey, long merchantId, long userId,
    String pgType, BigDecimal totalAmount, String currency,
    int cancelPeriodDays, PaymentStatus status,
    LocalDateTime createdAt, LocalDateTime updatedAt
) {
    return new Payment(id, paymentKey, merchantId, userId, pgType,
        totalAmount, currency, cancelPeriodDays, status, createdAt, updatedAt);
}
```

- [ ] **Step 3: PaymentItemJpaEntity**

`infrastructure/persistence/entity/PaymentItemJpaEntity.java`:

```java
package com.example.payment.infrastructure.persistence.entity;

import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_item")
public class PaymentItemJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_auto_id", nullable = false)
    private Long productAutoId;

    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Column(name = "item_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal itemAmount;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private PaymentItemStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected PaymentItemJpaEntity() {}

    public PaymentItem toDomain() {
        return PaymentItem.reconstruct(id, paymentId, orderItemId, productId,
            productAutoId, itemName, itemAmount, status);
    }

    public static PaymentItemJpaEntity from(PaymentItem domain) {
        PaymentItemJpaEntity e = new PaymentItemJpaEntity();
        e.id = domain.getId() == 0 ? null : domain.getId();
        e.paymentId = domain.getPaymentId();
        e.orderItemId = domain.getOrderItemId();
        e.productId = domain.getProductId();
        e.productAutoId = domain.getProductAutoId();
        e.itemName = domain.getItemName();
        e.itemAmount = domain.getItemAmount();
        e.status = domain.getStatus();
        return e;
    }
}
```

- [ ] **Step 4: PaymentItem.reconstruct() 추가**

`PaymentItem.java`에 추가:

```java
public static PaymentItem reconstruct(
    long id, long paymentId, long orderItemId,
    long productId, long productAutoId,
    String itemName, BigDecimal itemAmount, PaymentItemStatus status
) {
    return new PaymentItem(id, paymentId, orderItemId, productId, productAutoId,
        itemName, itemAmount, status);
}
```

- [ ] **Step 5: CancelRequestJpaEntity**

`infrastructure/persistence/entity/CancelRequestJpaEntity.java`:

```java
package com.example.payment.infrastructure.persistence.entity;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cancel_request")
public class CancelRequestJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "cancel_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cancelAmount;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CancelStatus status;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_reason", length = 500)
    private String failedReason;

    @Column(name = "pg_pending_since")
    private Instant pgPendingSince;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected CancelRequestJpaEntity() {}

    public CancelRequest toDomain() {
        return CancelRequest.reconstruct(id, paymentId, requestHash, cancelAmount,
            cancelReason, status, processingStartedAt, completedAt, failedReason,
            pgPendingSince, createdAt, updatedAt);
    }

    public static CancelRequestJpaEntity from(CancelRequest domain) {
        CancelRequestJpaEntity e = new CancelRequestJpaEntity();
        e.id = domain.getId();
        e.paymentId = domain.getPaymentId();
        e.requestHash = domain.getRequestHash();
        e.cancelAmount = domain.getCancelAmount();
        e.cancelReason = domain.getCancelReason();
        e.status = domain.getStatus();
        e.processingStartedAt = domain.getProcessingStartedAt();
        e.completedAt = domain.getCompletedAt();
        e.failedReason = domain.getFailedReason();
        e.pgPendingSince = domain.getPgPendingSince();
        e.createdAt = domain.getCreatedAt();
        e.updatedAt = domain.getUpdatedAt();
        return e;
    }
}
```

- [ ] **Step 6: CancelRequest.reconstruct() 추가**

`CancelRequest.java`에 추가:

```java
public static CancelRequest reconstruct(
    Long id, Long paymentId, String requestHash,
    BigDecimal cancelAmount, String cancelReason, CancelStatus status,
    Instant processingStartedAt, Instant completedAt, String failedReason,
    Instant pgPendingSince, Instant createdAt, Instant updatedAt
) {
    CancelRequest r = new CancelRequest(paymentId, requestHash, cancelAmount, cancelReason);
    r.id = id;
    r.status = status;
    r.processingStartedAt = processingStartedAt;
    r.completedAt = completedAt;
    r.failedReason = failedReason;
    r.pgPendingSince = pgPendingSince;
    r.createdAt = createdAt;
    r.updatedAt = updatedAt;
    return r;
}
```

- [ ] **Step 7: 나머지 JPA 엔티티 3개**

`infrastructure/persistence/entity/CancelRequestHistoryJpaEntity.java`:

```java
package com.example.payment.infrastructure.persistence.entity;

import com.example.payment.domain.entity.CancelStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cancel_request_history")
public class CancelRequestHistoryJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false)
    private Long cancelRequestId;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CancelStatus status;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CancelRequestHistoryJpaEntity() {}

    public static CancelRequestHistoryJpaEntity of(long cancelRequestId,
                                                    CancelStatus status, String reason) {
        CancelRequestHistoryJpaEntity e = new CancelRequestHistoryJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.status = status;
        e.reason = reason;
        e.createdAt = Instant.now();
        return e;
    }
}
```

`infrastructure/persistence/entity/CancelEventOutboxJpaEntity.java`:

```java
package com.example.payment.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "cancel_event_outbox")
public class CancelEventOutboxJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, unique = true)
    private Long cancelRequestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected CancelEventOutboxJpaEntity() {}

    public static CancelEventOutboxJpaEntity pending(long cancelRequestId, String payload) {
        CancelEventOutboxJpaEntity e = new CancelEventOutboxJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.payload = payload;
        e.status = "PENDING";
        e.createdAt = Instant.now();
        return e;
    }

    public Long getCancelRequestId() { return cancelRequestId; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public void markPublished() { this.status = "PUBLISHED"; this.publishedAt = Instant.now(); }
}
```

`infrastructure/persistence/entity/CompensationRetryJpaEntity.java`:

```java
package com.example.payment.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "compensation_retry")
public class CompensationRetryJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, unique = true, length = 64)
    private String cancelRequestId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "restore_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal restoreAmount;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected CompensationRetryJpaEntity() {}

    public static CompensationRetryJpaEntity newRetry(
        long cancelRequestId, long merchantId, BigDecimal restoreAmount
    ) {
        CompensationRetryJpaEntity e = new CompensationRetryJpaEntity();
        e.cancelRequestId = String.valueOf(cancelRequestId);
        e.merchantId = merchantId;
        e.restoreAmount = restoreAmount;
        e.attemptCount = 0;
        e.nextRetryAt = Instant.now().plusSeconds(60);
        e.status = "PENDING";
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }
}
```

- [ ] **Step 8: Spring Data JPA 인터페이스 6개**

`infrastructure/persistence/jpa/PaymentJpaRepository.java`:

```java
package com.example.payment.infrastructure.persistence.jpa;

import com.example.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, Long> {
    Optional<PaymentJpaEntity> findByPaymentKey(String paymentKey);
}
```

`infrastructure/persistence/jpa/PaymentItemJpaRepository.java`:

```java
package com.example.payment.infrastructure.persistence.jpa;

import com.example.payment.infrastructure.persistence.entity.PaymentItemJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface PaymentItemJpaRepository extends JpaRepository<PaymentItemJpaEntity, Long> {

    List<PaymentItemJpaEntity> findAllByPaymentIdOrderByIdAsc(Long paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentItemJpaEntity p WHERE p.paymentId = :paymentId ORDER BY p.id ASC")
    List<PaymentItemJpaEntity> findAllByPaymentIdForUpdate(Long paymentId);
}
```

`infrastructure/persistence/jpa/CancelRequestJpaRepository.java`:

```java
package com.example.payment.infrastructure.persistence.jpa;

import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.infrastructure.persistence.entity.CancelRequestJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CancelRequestJpaRepository extends JpaRepository<CancelRequestJpaEntity, Long> {
    Optional<CancelRequestJpaEntity> findByPaymentIdAndRequestHash(Long paymentId, String requestHash);
    List<CancelRequestJpaEntity> findByStatusAndCreatedAtBefore(CancelStatus status, Instant before);
}
```

`infrastructure/persistence/jpa/CancelRequestHistoryJpaRepository.java`:

```java
package com.example.payment.infrastructure.persistence.jpa;

import com.example.payment.infrastructure.persistence.entity.CancelRequestHistoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancelRequestHistoryJpaRepository
    extends JpaRepository<CancelRequestHistoryJpaEntity, Long> {}
```

`infrastructure/persistence/jpa/CancelEventOutboxJpaRepository.java`:

```java
package com.example.payment.infrastructure.persistence.jpa;

import com.example.payment.infrastructure.persistence.entity.CancelEventOutboxJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CancelEventOutboxJpaRepository
    extends JpaRepository<CancelEventOutboxJpaEntity, Long> {

    List<CancelEventOutboxJpaEntity> findByStatusOrderByCreatedAtAsc(String status);
    boolean existsByCancelRequestId(Long cancelRequestId);
}
```

`infrastructure/persistence/jpa/CompensationRetryJpaRepository.java`:

```java
package com.example.payment.infrastructure.persistence.jpa;

import com.example.payment.infrastructure.persistence.entity.CompensationRetryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface CompensationRetryJpaRepository
    extends JpaRepository<CompensationRetryJpaEntity, Long> {

    List<CompensationRetryJpaEntity> findByStatusAndNextRetryAtBefore(String status, Instant before);
}
```

- [ ] **Step 9: Adapter 구현체 6개**

`infrastructure/persistence/adapter/PaymentJpaAdapter.java`:

```java
package com.example.payment.infrastructure.persistence.adapter;

import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import com.example.payment.infrastructure.persistence.jpa.PaymentJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentJpaAdapter implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    @Override
    public Optional<Payment> findByPaymentKey(String paymentKey) {
        return jpaRepository.findByPaymentKey(paymentKey).map(e -> e.toDomain());
    }
}
```

`infrastructure/persistence/adapter/PaymentItemJpaAdapter.java`:

```java
package com.example.payment.infrastructure.persistence.adapter;

import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.infrastructure.persistence.entity.PaymentItemJpaEntity;
import com.example.payment.infrastructure.persistence.jpa.PaymentItemJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class PaymentItemJpaAdapter implements PaymentItemRepository {

    private final PaymentItemJpaRepository jpaRepository;

    @Override
    public List<PaymentItem> findAllByPaymentIdOrderByIdAsc(long paymentId) {
        return jpaRepository.findAllByPaymentIdOrderByIdAsc(paymentId)
            .stream().map(PaymentItemJpaEntity::toDomain).toList();
    }

    @Override
    public List<PaymentItem> findAllByPaymentIdForUpdate(long paymentId) {
        return jpaRepository.findAllByPaymentIdForUpdate(paymentId)
            .stream().map(PaymentItemJpaEntity::toDomain).toList();
    }

    @Override
    public void saveAll(List<PaymentItem> items) {
        jpaRepository.saveAll(items.stream().map(PaymentItemJpaEntity::from).toList());
    }
}
```

`infrastructure/persistence/adapter/CancelRequestJpaAdapter.java`:

```java
package com.example.payment.infrastructure.persistence.adapter;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.infrastructure.persistence.entity.CancelRequestJpaEntity;
import com.example.payment.infrastructure.persistence.jpa.CancelRequestJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CancelRequestJpaAdapter implements CancelRequestRepository {

    private final CancelRequestJpaRepository jpaRepository;

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
    public List<CancelRequest> findByStatusAndCreatedAtBefore(CancelStatus status, Instant before) {
        return jpaRepository.findByStatusAndCreatedAtBefore(status, before)
            .stream().map(CancelRequestJpaEntity::toDomain).toList();
    }
}
```

`infrastructure/persistence/adapter/CancelRequestHistoryJpaAdapter.java`:

```java
package com.example.payment.infrastructure.persistence.adapter;

import com.example.payment.application.interfaces.CancelRequestHistoryRepository;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.infrastructure.persistence.entity.CancelRequestHistoryJpaEntity;
import com.example.payment.infrastructure.persistence.jpa.CancelRequestHistoryJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CancelRequestHistoryJpaAdapter implements CancelRequestHistoryRepository {

    private final CancelRequestHistoryJpaRepository jpaRepository;

    @Override
    public void record(long cancelRequestId, CancelStatus status, String reason) {
        jpaRepository.save(CancelRequestHistoryJpaEntity.of(cancelRequestId, status, reason));
    }
}
```

`infrastructure/persistence/adapter/CancelEventOutboxJpaAdapter.java`:

```java
package com.example.payment.infrastructure.persistence.adapter;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.infrastructure.persistence.entity.CancelEventOutboxJpaEntity;
import com.example.payment.infrastructure.persistence.jpa.CancelEventOutboxJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class CancelEventOutboxJpaAdapter implements CancelEventOutboxRepository {

    private final CancelEventOutboxJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public void insertIfAbsent(CancelRequest cancelRequest, List<PaymentItem> cancelledItems) {
        if (jpaRepository.existsByCancelRequestId(cancelRequest.getId())) return;

        var payload = Map.of(
            "cancelRequestId", cancelRequest.getId(),
            "cancelledItems", cancelledItems.stream().map(i -> Map.of(
                "paymentItemId", i.getId(),
                "orderItemId", i.getOrderItemId(),
                "itemAmount", i.getItemAmount()
            )).toList(),
            "cancelledAt", Instant.now().toString()
        );

        jpaRepository.save(CancelEventOutboxJpaEntity.pending(
            cancelRequest.getId(), objectMapper.writeValueAsString(payload)));
    }
}
```

`infrastructure/persistence/adapter/CompensationRetryJpaAdapter.java`:

```java
package com.example.payment.infrastructure.persistence.adapter;

import com.example.payment.application.interfaces.CompensationRetryRepository;
import com.example.payment.infrastructure.persistence.entity.CompensationRetryJpaEntity;
import com.example.payment.infrastructure.persistence.jpa.CompensationRetryJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;

@Repository
@RequiredArgsConstructor
public class CompensationRetryJpaAdapter implements CompensationRetryRepository {

    private final CompensationRetryJpaRepository jpaRepository;

    @Override
    public void save(long cancelRequestId, long merchantId, BigDecimal restoreAmount) {
        jpaRepository.save(CompensationRetryJpaEntity.newRetry(cancelRequestId, merchantId, restoreAmount));
    }
}
```

- [ ] **Step 10: 컴파일 확인**

```bash
./gradlew :payment-service:compileJava 2>&1 | tail -20
```

Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/
git commit -m "feat: JPA 엔티티 및 Repository 어댑터 구현"
```

---

## Task 8: Infrastructure — HTTP 클라이언트 (Risk, PG) + build.gradle 의존성

**Files:**
- Modify: `build.gradle` (Resilience4j, OpenFeign 추가)
- Create: `infrastructure/http/RiskManagementHttpClient.java`
- Create: `infrastructure/http/PgCancelHttpClient.java`
- Create: `infrastructure/config/Resilience4jConfig.java`
- Create: `infrastructure/config/FeignConfig.java`

- [ ] **Step 1: build.gradle 의존성 추가**

루트 `build.gradle`의 `dependencies` 블록에 추가:

```groovy
// Resilience4j (Circuit Breaker)
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
implementation 'io.github.resilience4j:resilience4j-feign:2.2.0'
implementation 'org.springframework.boot:spring-boot-starter-aop'

// OpenFeign
implementation 'org.springframework.cloud:spring-cloud-starter-openfeign:4.1.4'

// Jackson (Outbox payload 직렬화)
implementation 'com.fasterxml.jackson.core:jackson-databind'
```

- [ ] **Step 2: RiskManagementHttpClient (Feign + Resilience4j)**

`infrastructure/http/RiskManagementHttpClient.java`:

```java
package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.interfaces.RiskManagementPort;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.infrastructure.http.dto.RiskCompensateRequest;
import com.example.payment.infrastructure.http.dto.RiskReserveRequest;
import com.example.payment.infrastructure.http.dto.RiskReserveResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskManagementHttpClient implements RiskManagementPort {

    private final RestClient riskRestClient;

    @Override
    @CircuitBreaker(name = "risk-management", fallbackMethod = "validateAndReserveFallback")
    public RiskReserveResult validateAndReserve(
        long merchantId, long cancelRequestId,
        BigDecimal cancelAmount, LocalDate kstDate
    ) {
        RiskReserveResponse response = riskRestClient.post()
            .uri("/internal/cancel-limit/validate-and-reserve")
            .body(new RiskReserveRequest(merchantId, cancelRequestId, cancelAmount, kstDate))
            .retrieve()
            .body(RiskReserveResponse.class);

        return new RiskReserveResult(response.merchantId(), response.dailyLimit(),
            response.usedAmount(), response.remainingLimit());
    }

    @Override
    @CircuitBreaker(name = "risk-management", fallbackMethod = "compensateFallback")
    public void compensate(long cancelRequestId, long merchantId, BigDecimal restoreAmount) {
        riskRestClient.post()
            .uri("/internal/cancel-limit/compensate")
            .body(new RiskCompensateRequest(cancelRequestId, merchantId, restoreAmount))
            .retrieve()
            .toBodilessEntity();
    }

    public RiskReserveResult validateAndReserveFallback(
        long merchantId, long cancelRequestId,
        BigDecimal cancelAmount, LocalDate kstDate, Exception e
    ) {
        log.error("risk-management CB OPEN. cancelRequestId={}", cancelRequestId, e);
        throw new com.example.payment.infrastructure.exception.RiskServiceException(
            ErrorCode.RISK_SERVICE_UNAVAILABLE);
    }

    public void compensateFallback(
        long cancelRequestId, long merchantId, BigDecimal restoreAmount, Exception e
    ) {
        log.error("risk compensate CB OPEN. cancelRequestId={}", cancelRequestId, e);
        throw new com.example.payment.infrastructure.exception.RiskServiceException(
            ErrorCode.RISK_SERVICE_UNAVAILABLE);
    }
}
```

- [ ] **Step 3: Risk HTTP DTO 3개**

`infrastructure/http/dto/RiskReserveRequest.java`:

```java
package com.example.payment.infrastructure.http.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RiskReserveRequest(
    long merchantId,
    long cancelRequestId,
    BigDecimal cancelAmount,
    LocalDate kstDate
) {}
```

`infrastructure/http/dto/RiskReserveResponse.java`:

```java
package com.example.payment.infrastructure.http.dto;

import java.math.BigDecimal;

public record RiskReserveResponse(
    long merchantId,
    BigDecimal dailyLimit,
    BigDecimal usedAmount,
    BigDecimal remainingLimit
) {}
```

`infrastructure/http/dto/RiskCompensateRequest.java`:

```java
package com.example.payment.infrastructure.http.dto;

import java.math.BigDecimal;

public record RiskCompensateRequest(
    long cancelRequestId,
    long merchantId,
    BigDecimal restoreAmount
) {}
```

- [ ] **Step 4: PgCancelHttpClient**

`infrastructure/http/PgCancelHttpClient.java`:

```java
package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.infrastructure.http.dto.PgCancelRequest;
import com.example.payment.infrastructure.http.dto.PgCancelResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class PgCancelHttpClient implements PgCancelPort {

    private final RestClient pgRestClient;

    @Override
    @CircuitBreaker(name = "pg-cancel", fallbackMethod = "cancelFallback")
    public PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason) {
        PgCancelResponse response = pgRestClient.post()
            .uri("/v1/payments/cancel")
            .body(new PgCancelRequest(paymentKey, cancelAmount, cancelReason))
            .retrieve()
            .body(PgCancelResponse.class);

        return new PgCancelResult(response.transactionId(), response.status());
    }

    public PgCancelResult cancelFallback(
        String paymentKey, BigDecimal cancelAmount, String cancelReason, Exception e
    ) {
        log.error("PG CB OPEN. paymentKey={}", paymentKey, e);
        throw new com.example.payment.infrastructure.exception.PgServiceException(
            ErrorCode.INTERNAL_ERROR);
    }
}
```

- [ ] **Step 5: PG HTTP DTO 2개**

`infrastructure/http/dto/PgCancelRequest.java`:

```java
package com.example.payment.infrastructure.http.dto;

import java.math.BigDecimal;

public record PgCancelRequest(
    String paymentKey,
    BigDecimal cancelAmount,
    String cancelReason
) {}
```

`infrastructure/http/dto/PgCancelResponse.java`:

```java
package com.example.payment.infrastructure.http.dto;

public record PgCancelResponse(
    String transactionId,
    String status
) {}
```

- [ ] **Step 6: Infrastructure 예외 클래스**

`infrastructure/exception/RiskServiceException.java`:

```java
package com.example.payment.infrastructure.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class RiskServiceException extends BusinessException {
    public RiskServiceException(ErrorCode errorCode) {
        super(errorCode);
    }
}
```

`infrastructure/exception/PgServiceException.java`:

```java
package com.example.payment.infrastructure.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class PgServiceException extends BusinessException {
    public PgServiceException(ErrorCode errorCode) {
        super(errorCode);
    }
}
```

- [ ] **Step 7: Resilience4j 설정**

`infrastructure/config/Resilience4jConfig.java`:

```java
package com.example.payment.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
public class Resilience4jConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig riskConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .slidingWindowSize(10)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .minimumNumberOfCalls(5)
            .ignoreExceptions(
                com.example.payment.domain.exception.InvalidPaymentStatusException.class,
                com.example.payment.application.exception.MerchantCancelLimitExceededException.class
            )
            .build();

        CircuitBreakerConfig pgConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .slidingWindowSize(10)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(2)
            .minimumNumberOfCalls(5)
            .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("risk-management", riskConfig);
        registry.circuitBreaker("pg-cancel", pgConfig);
        return registry;
    }
}
```

- [ ] **Step 8: RestClient 빈 설정**

`infrastructure/config/HttpClientConfig.java`:

```java
package com.example.payment.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient riskRestClient(
        @Value("${service.risk-management.base-url}") String baseUrl
    ) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient pgRestClient(
        @Value("${service.pg.base-url}") String baseUrl
    ) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
```

- [ ] **Step 9: MerchantCancelLimitExceededException 추가 (application/exception)**

`application/exception/MerchantCancelLimitExceededException.java`:

```java
package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import java.math.BigDecimal;

public class MerchantCancelLimitExceededException extends BusinessException {

    public MerchantCancelLimitExceededException(
        BigDecimal requestedAmount, BigDecimal remainingLimit, BigDecimal dailyLimit
    ) {
        super(ErrorCode.MERCHANT_CANCEL_LIMIT_EXCEEDED,
            String.format("가맹점 일일 취소한도를 초과했습니다. requested=%s, remaining=%s",
                requestedAmount, remainingLimit));
    }
}
```

- [ ] **Step 10: 컴파일 확인**

```bash
./gradlew :payment-service:compileJava 2>&1 | tail -20
```

Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/ \
        build.gradle
git commit -m "feat: HTTP 클라이언트(Risk, PG) + Resilience4j CB 설정"
```

---

## Task 9: Presentation — Controller, DTO, GlobalExceptionHandler

**Files:**
- Create: `presentation/controller/CancelController.java`
- Create: `presentation/dto/CancelPaymentRequest.java`
- Create: `presentation/dto/CancelPaymentResponse.java`
- Create: `presentation/advice/GlobalExceptionHandler.java`

- [ ] **Step 1: Request / Response DTO**

`presentation/dto/CancelPaymentRequest.java`:

```java
package com.example.payment.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CancelPaymentRequest(
    String cancelReason,

    @NotEmpty(message = "취소 항목이 비어있습니다.")
    List<Long> cancelItemIds
) {}
```

`presentation/dto/CancelPaymentResponse.java`:

```java
package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.PaymentItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CancelPaymentResponse(
    Long cancelRequestId,
    String paymentKey,
    BigDecimal cancelAmount,
    String status,
    List<CancelledItemResponse> cancelledItems,
    Instant completedAt
) {
    public record CancelledItemResponse(
        Long paymentItemId,
        BigDecimal itemAmount,
        String status
    ) {}

    public static CancelPaymentResponse of(
        String paymentKey, CancelRequest cancelRequest, List<PaymentItem> cancelledItems
    ) {
        return new CancelPaymentResponse(
            cancelRequest.getId(),
            paymentKey,
            cancelRequest.getCancelAmount(),
            cancelRequest.getStatus().name(),
            cancelledItems.stream()
                .map(i -> new CancelledItemResponse(i.getId(), i.getItemAmount(),
                    i.getStatus().name()))
                .toList(),
            cancelRequest.getCompletedAt()
        );
    }
}
```

- [ ] **Step 2: CancelController**

`presentation/controller/CancelController.java`:

```java
package com.example.payment.presentation.controller;

import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.presentation.dto.CancelPaymentRequest;
import com.example.payment.presentation.dto.CancelPaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/payments")
public class CancelController {

    private final CancelPaymentUseCase cancelPaymentUseCase;

    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancel(
        @PathVariable String paymentKey,
        @RequestBody @Valid CancelPaymentRequest request
    ) {
        CancelPaymentCommand command = new CancelPaymentCommand(
            paymentKey,
            request.cancelReason(),
            request.cancelItemIds()
        );

        CancelRequest cancelRequest = cancelPaymentUseCase.cancel(command);

        // 응답용 cancelledItems: COMPLETED 상태에서만 의미 있음
        // 간략화: cancelledItems는 빈 목록으로 반환 (조회 API에서 상세 제공)
        CancelPaymentResponse response = new CancelPaymentResponse(
            cancelRequest.getId(),
            paymentKey,
            cancelRequest.getCancelAmount(),
            cancelRequest.getStatus().name(),
            List.of(),
            cancelRequest.getCompletedAt()
        );

        return ResponseEntity.ok(response);
    }
}
```

- [ ] **Step 3: GlobalExceptionHandler**

`presentation/advice/GlobalExceptionHandler.java`:

```java
package com.example.payment.presentation.advice;

import com.example.payment.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        log.warn("BusinessException: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(Map.of(
                "code", e.getErrorCode().getCode(),
                "message", e.getMessage()
            ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest()
            .body(Map.of("code", "INVALID_REQUEST", "message", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
            .body(Map.of("code", "INTERNAL_ERROR", "message", "서버 오류가 발생했습니다."));
    }
}
```

- [ ] **Step 4: application.yml 작성**

`payment-service/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: payment-service
  datasource:
    url: jdbc:mysql://localhost:3306/payment_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: ${DB_USERNAME:payment}
    password: ${DB_PASSWORD:payment}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        format_sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080

service:
  risk-management:
    base-url: ${RISK_SERVICE_URL:http://localhost:8083}
  pg:
    base-url: ${PG_SERVICE_URL:http://localhost:9999}

resilience4j:
  circuitbreaker:
    instances:
      risk-management:
        failure-rate-threshold: 50
        sliding-window-size: 10
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        minimum-number-of-calls: 5
      pg-cancel:
        failure-rate-threshold: 50
        sliding-window-size: 10
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 2
        minimum-number-of-calls: 5
```

- [ ] **Step 5: Spring Boot Application 클래스 작성**

`src/main/java/com/example/payment/PaymentServiceApplication.java`:

```java
package com.example.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
```

- [ ] **Step 6: 컴파일 확인**

```bash
./gradlew :payment-service:compileJava 2>&1 | tail -20
```

Expected: PASS

- [ ] **Step 7: 단위 테스트 전체 실행**

```bash
./gradlew :payment-service:test 2>&1 | tail -30
```

Expected: 도메인 + 애플리케이션 단위 테스트 PASS (인프라 테스트는 Testcontainers 필요)

- [ ] **Step 8: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/presentation/ \
        payment-service/src/main/java/com/example/payment/PaymentServiceApplication.java \
        payment-service/src/main/resources/application.yml
git commit -m "feat: Presentation 레이어 (Controller, DTO, GlobalExceptionHandler)"
```

---

## Task 10: V8 DDL 검증 + Testcontainers 통합 테스트

**Files:**
- Create: `test/.../infrastructure/persistence/CancelRequestJpaAdapterTest.java`

- [ ] **Step 1: Testcontainers 통합 테스트**

`test/java/com/example/payment/infrastructure/persistence/CancelRequestJpaAdapterTest.java`:

```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.example.payment.infrastructure.persistence.adapter.CancelRequestJpaAdapter.class)
@DisplayName("CancelRequestJpaAdapter 통합 테스트")
class CancelRequestJpaAdapterTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_db")
        .withUsername("payment")
        .withPassword("payment");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    CancelRequestRepository repository;

    @Test
    @DisplayName("should_save_and_find_cancel_request_by_payment_id_and_request_hash")
    void shouldSaveAndFindCancelRequestByPaymentIdAndRequestHash() {
        CancelRequest cancelRequest = CancelRequest.create(
            1L, "hash-abc123", BigDecimal.valueOf(30000), "고객 변심");

        CancelRequest saved = repository.save(cancelRequest);
        assertNotNull(saved.getId());

        Optional<CancelRequest> found = repository.findByPaymentIdAndRequestHash(1L, "hash-abc123");
        assertTrue(found.isPresent());
        assertEquals(CancelStatus.PENDING, found.get().getStatus());
    }

    @Test
    @DisplayName("should_return_empty_when_request_hash_not_found")
    void shouldReturnEmptyWhenRequestHashNotFound() {
        Optional<CancelRequest> found = repository.findByPaymentIdAndRequestHash(99L, "no-such-hash");
        assertTrue(found.isEmpty());
    }
}
```

- [ ] **Step 2: Testcontainers 통합 테스트 실행**

```bash
./gradlew :payment-service:test --tests "*.CancelRequestJpaAdapterTest" 2>&1 | tail -30
```

Expected: PASS (MySQL 컨테이너 기동 후 V1~V8 Flyway 마이그레이션 자동 실행)

- [ ] **Step 3: 전체 테스트 실행**

```bash
./gradlew :payment-service:test 2>&1 | tail -30
```

Expected: 전체 PASS

- [ ] **Step 4: V8 DDL + 마이그레이션 파일 커밋**

```bash
git add payment-service/src/main/resources/db/migration/V8__align_cancel_schema.sql \
        payment-service/src/test/java/com/example/payment/infrastructure/
git commit -m "feat: V8 DDL 스키마 정렬 및 Testcontainers 통합 테스트"
```

---

## 검증 명령어 요약

```bash
# 도메인 테스트
./gradlew :payment-service:test --tests "com.example.payment.domain.*"

# 애플리케이션 테스트
./gradlew :payment-service:test --tests "com.example.payment.application.*"

# 전체 테스트
./gradlew :payment-service:test

# 빌드
./gradlew :payment-service:build
```
