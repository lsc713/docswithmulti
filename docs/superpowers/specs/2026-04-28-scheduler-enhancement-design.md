# Scheduler Enhancement Design
## pending-recovery / processing-recovery 보강

**Date**: 2026-04-28  
**Branch**: feature/payment-service-tests  
**Scope**: payment-service

---

## 1. 배경 및 목표

`PendingRecoveryScheduler`와 `ProcessingRecoveryScheduler`는 Redis 분산락 구조만 구현된 스텁 상태다.
이 스펙은 두 스케줄러에 실제 복구 로직을 채우는 작업을 정의한다.

cancel-design.md 섹션 8 기준.

---

## 2. 결정 사항 요약

| 항목 | 결정 |
|------|------|
| cancel_item_ids 저장 방식 | `cancel_request.cancel_item_ids` JSON 컬럼 (V9 migration) |
| PG 재시도 최대 횟수 | 5회 (compensation-retry와 동일 정책) |
| 운영팀 알림 방식 | `OperationAlertPort` 추상화, 현재 구현은 log.error |
| 구현 구조 | 스케줄러 + 전용 Application Service (CompensationRetryService 패턴 동일) |

---

## 3. 스키마 변경 (V9)

```sql
ALTER TABLE cancel_request
    ADD COLUMN cancel_item_ids JSON         NOT NULL
        COMMENT '취소 대상 payment_item_id 목록'
        AFTER cancel_amount,
    ADD COLUMN pg_retry_count  INT          NOT NULL DEFAULT 0
        COMMENT 'PG사 취소 재시도 횟수 (processing-recovery 전용)'
        AFTER pg_pending_since;
```

---

## 4. 도메인 엔티티 변경: `CancelRequest`

### 추가 필드
- `List<Long> cancelItemIds` — 생성 시 설정, 이후 불변
- `int pgRetryCount` — `processing-recovery`에서 PG 재시도 횟수 추적

### 추가 메서드
```java
// pgPendingSince == null일 때만 설정 (멱등)
public void markPgPending() { ... }

// pgRetryCount 1 증가
public void incrementPgRetryCount() { ... }
```

### 삭제
- `processingStartedAt` 필드 — V8에서 DB 컬럼 삭제됨, JPA 매핑에서 이미 null 하드코딩
- `failedReason` 필드 — V8에서 DB 컬럼 삭제됨, `toFailed(reason)` 호출 시 DB 반영 안 됨

### 팩토리 메서드 시그니처 변경
```java
// 기존
CancelRequest.create(paymentId, requestHash, cancelAmount, cancelReason)

// 변경
CancelRequest.create(paymentId, requestHash, cancelAmount, cancelReason, cancelItemIds)
```

`reconstruct()` 도 `cancelItemIds`, `pgRetryCount` 파라미터 추가.

---

## 5. 인터페이스 변경

### `CancelRequestRepository`
```java
// 신규 (기존 범용 메서드 findByStatusAndCreatedAtBefore 삭제)
List<CancelRequest> findPendingCreatedBefore(Instant before);
List<CancelRequest> findProcessingUpdatedBefore(Instant before);
```

기존 `findByStatusAndCreatedAtBefore(status, before)` 삭제 — 상태를 파라미터로 받는 범용 형태는
pending/processing 각각 다른 시간 기준(createdAt vs updatedAt)이 필요함을 숨겨 혼동을 유발한다.

### `PaymentRepository`
```java
// processing-recovery에서 cancelRequest.getPaymentId()로 Payment 로드
Optional<Payment> findById(Long paymentId);
```

### `RiskManagementPort`
```java
/** charged=true: used_amount 차감 완료 상태. pending-recovery 보상 필요 여부 판단용 */
boolean isCharged(long cancelRequestId);
```

### `PgCancelPort`
```java
/** 취소 건 상태 조회. 조회 실패 시 예외 throw → 스케줄러가 PROCESSING 유지 */
PgCancelResult getStatus(String paymentKey);
```

### `PgCancelResult` 변경
```java
// retryable 필드 추가
public record PgCancelResult(String pgTransactionId, String status, boolean retryable) {
    public boolean isApproved() { return "APPROVED".equals(status); }
    public boolean isFailed()   { return "FAILED".equals(status); }
    public boolean isPending()  { return "PENDING".equals(status); }
}
```

`retryable=true`: 네트워크 오류, 일시적 PG 오류.  
`retryable=false`: 카드사 정책 위반, 취소 기간 만료.  
판단은 `PgCancelPort` 구현체(infrastructure)에서 담당.

### `OperationAlertPort` (신규)
```java
// application/interfaces
public interface OperationAlertPort {
    void alertPgPendingTimeout(long cancelRequestId, String paymentKey, Instant pgPendingSince);
}
```

구현체: `infrastructure` 패키지에 `LogOperationAlertAdapter` — `log.error` 출력.  
추후 Slack/PagerDuty 교체 가능.

---

## 6. Application Services

### `PendingRecoveryService`

```
recoverAll():
  대상: findPendingCreatedBefore(now - 5분)
  각 건:
    riskManagementPort.isCharged(cancelRequestId)
    → true:
        compensate 호출
        성공: toFailed + save + history
        실패: compensationRetryRepository.save + toFailed + save + history
    → false:
        toFailed + save + history
    예외: log.warn + 다음 건 계속 (스케줄러 중단 없음)
```

### `ProcessingRecoveryService`

```
recoverAll():
  대상: findProcessingUpdatedBefore(now - 5분)
  각 건:
    pgCancelPort.getStatus(paymentKey)
    └─ 조회 실패(예외): log.warn + PROCESSING 유지 + skip
    └─ APPROVED: TX3 재실행 + history(COMPLETED)
    └─ FAILED, retryable=false: compensate + toFailed + history
    └─ FAILED, retryable=true, pgRetryCount < 5:
         pgCancelPort.cancel() 재호출
         성공(APPROVED): TX3 재실행
         실패: incrementPgRetryCount + save
               pgRetryCount == 5: compensate + toFailed + history
    └─ PENDING:
         markPgPending + save
         pgPendingSince > 1시간:
           compensate + toFailed + history
           operationAlertPort.alertPgPendingTimeout(...)
    예외: log.warn + 다음 건 계속
```

TX3 재실행은 기존 `CancelTxWriter.saveTx3(cancelRequest, payment, cancelItemIds)` 재사용.  
`payment`는 `paymentRepository.findById(cancelRequest.getPaymentId())`로 로드.

---

## 7. Infrastructure 변경

### `CancelRequestJpaEntity`
- `cancel_item_ids` JSON 컬럼 매핑 추가 (`@Column(columnDefinition = "JSON")`, `List<Long>` Jackson 변환)
- `pg_retry_count` 컬럼 매핑 추가
- `processingStartedAt`, `failedReason` 잔재 코드 삭제 (V8에서 컬럼 삭제됨)
- `from()` / `toDomain()` 갱신

### `CancelRequestJpaRepository`
```java
// 신규
@Query("SELECT c FROM CancelRequestJpaEntity c WHERE c.status = :status AND c.updatedAt < :before")
List<CancelRequestJpaEntity> findByStatusAndUpdatedAtBefore(
    @Param("status") CancelStatus status, @Param("before") LocalDateTime before);
```

### `CancelRequestRepositoryImpl`
- `findPendingCreatedBefore`, `findProcessingUpdatedBefore` 구현
- 기존 `findByStatusAndCreatedAtBefore` 구현 삭제

### `PaymentRepositoryImpl` + `PaymentJpaRepository`
- `findById(Long)` 추가 (Spring Data JPA 기본 제공, 구현체에서 위임만)

### `LogOperationAlertAdapter` (신규)
```java
// infrastructure/adapter
@Component
public class LogOperationAlertAdapter implements OperationAlertPort {
    public void alertPgPendingTimeout(long cancelRequestId, String paymentKey, Instant pgPendingSince) {
        log.error("[alert] PG pending timeout cancelRequestId={} paymentKey={} since={}",
            cancelRequestId, paymentKey, pgPendingSince);
    }
}
```

---

## 8. Scheduler 계층

기존 스텁의 TODO만 채움. 락 구조/주기 변경 없음.

```java
// PendingRecoveryScheduler
try {
    pendingRecoveryService.recoverAll();
} finally { ... }

// ProcessingRecoveryScheduler
try {
    processingRecoveryService.recoverAll();
} finally { ... }
```

---

## 9. 영향 받는 기존 코드

### `CancelPaymentService`
`CancelRequest.create()` 시그니처 변경 → `command.cancelPaymentItemIds()` 전달 추가.

### 기존 테스트
`CancelRequest` 팩토리 메서드 시그니처 변경으로 인한 컴파일 오류 수정 필요.  
`findByStatusAndCreatedAtBefore` 삭제로 인한 참조 수정 필요.

---

## 10. 파일 목록 요약

| 파일 | 유형 |
|------|------|
| `V9__add_cancel_item_ids_and_pg_retry_count.sql` | 신규 migration |
| `CancelRequest` | 수정 |
| `CancelRequestRepository` | 수정 |
| `PaymentRepository` | 수정 |
| `RiskManagementPort` | 수정 |
| `PgCancelPort` | 수정 |
| `PgCancelResult` | 수정 |
| `OperationAlertPort` | 신규 |
| `PendingRecoveryService` | 신규 |
| `ProcessingRecoveryService` | 신규 |
| `CancelRequestJpaEntity` | 수정 |
| `CancelRequestJpaRepository` | 수정 |
| `CancelRequestRepositoryImpl` | 수정 |
| `PaymentRepositoryImpl` | 수정 |
| `LogOperationAlertAdapter` | 신규 |
| `PendingRecoveryScheduler` | 수정 (스텁 채움) |
| `ProcessingRecoveryScheduler` | 수정 (스텁 채움) |
| `CancelPaymentService` | 수정 (create 시그니처) |
| 기존 테스트 파일들 | 컴파일 오류 수정 |
