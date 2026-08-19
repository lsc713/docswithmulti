---
phase: 02-consistency-recovery-gaps
plan: 02
subsystem: payments
tags: [concurrency, idempotency, unique-constraint, testcontainers, cancel-flow]

# Dependency graph
requires:
  - phase: 02-01
    provides: PgCancelHttpClient.getStatus()/RiskManagementHttpClient.isCharged() 실 구현(테스트 mock 대체 대상 아님, 독립 경로)
provides:
  - "CancelPaymentService.executeCancel saveTx1 UK 위반 → handleExistingRequest 멱등 응답 번역"
  - "CancelRaceIdempotencyIT — 동시 취소 레이스 Testcontainers 재현 패턴 (02-03 이 이식할 골격)"
affects: [02-03, k3s-scaleout-load-test]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "UK 위반 → 멱등 응답 번역: saveTx1 호출 지점 국소 try/catch(DataIntegrityViolationException) → findByPaymentIdAndRequestHash 재조회 → 기존 handleExistingRequest 상태 스위치 위임 (신규 응답 형태 없음, 전역 핸들러 오염 없음)"
    - "동시성 재현: CancelFlowIntegrationTest 의 @SpringBootTest+Testcontainers+@MockitoBean 골격 + MerchantCancelUsageAtomicDeductIT 의 ExecutorService+CountDownLatch+Future.get(timeout) 골격 결합 (D-02: 같은 JVM 동시 스레드, 실 2인스턴스 아님)"

key-files:
  created:
    - "payment-service/src/test/java/com/example/payment/integration/CancelRaceIdempotencyIT.java"
  modified:
    - "payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java"
    - "payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java"

key-decisions:
  - "D-03 (locked, 그대로 준수): 레이스 패자 응답은 새 DTO/형태 없이 handleExistingRequest 의 기존 상태 스위치(COMPLETED/PENDING/PROCESSING→기존건 반환)로 흘려 api-spec.md §멱등성 처리 응답(200+status) 계약 그대로 충족."
  - "catch 는 CancelPaymentService.executeCancel 의 saveTx1 호출 지점에만 국소 배치 — GlobalExceptionHandler 에 DataIntegrityViolationException→200 전역 매핑 추가하지 않음(다른 UK 제약과 의미 오염 방지, RESEARCH.md Anti-Patterns)."

patterns-established:
  - "UK 위반 catch 지점: CancelRequestJpaEntity 가 GenerationType.IDENTITY 이므로 saveTx1() 호출 시점에 동기적으로 예외가 던져짐 — catch 는 정확히 그 호출 직후."

requirements-completed: [RESIL-02]

coverage:
  - id: D1
    description: "saveTx1 이 DataIntegrityViolationException 을 던지면 findByPaymentIdAndRequestHash 로 재조회한 승자를 handleExistingRequest 경유로 반환(예외 전파 아님)"
    requirement: "RESIL-02"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java::shouldReturnWinnerIdempotentlyWhenSaveTx1ViolatesUniqueConstraint"
        status: pass
    human_judgment: false
  - id: D2
    description: "동일 취소를 2 스레드가 동시 착지시켜도 cancel_request 는 (payment_id, request_hash) 정확히 1건, 최종 COMPLETED, PaymentItem 이중취소 0, 두 응답 모두 예외 없이 반환(500 아님)"
    requirement: "RESIL-02"
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/CancelRaceIdempotencyIT.java::concurrentCancelRequests_produceExactlyOneCompletedRow_loserGetsIdempotentResponse"
        status: pass
    human_judgment: false

# Metrics
duration: ~20min
completed: 2026-07-29
status: complete
---

# Phase 2 Plan 02: 멀티파드 동시 취소 멱등 응답 Summary

**saveTx1 의 (payment_id, request_hash) UK 위반을 국소 catch 로 잡아 handleExistingRequest 상태 스위치로 위임 — 레이스 패자가 500 대신 기존 계약 그대로 200 멱등 응답을 반환**

## Performance

- **Duration:** ~20 min
- **Completed:** 2026-07-29
- **Tasks:** 2 (Task 1 tracer + Task 2 auto)
- **Files modified:** 3 (1 created, 2 modified)

## Accomplishments
- `CancelPaymentService.executeCancel` — `saveTx1` 호출을 `DataIntegrityViolationException` 국소 try/catch 로 감싸 레이스 패자를 `findByPaymentIdAndRequestHash` 재조회 → 기존 `handleExistingRequest` 상태 스위치로 위임(D-03 그대로, 새 응답 형태 없음)
- `CancelPaymentServiceTest` — `saveTx1` 이 UK 위반을 던지는 케이스에서 승자 재조회 후 멱등 반환(risk/PG 미호출)을 검증하는 단위 테스트 추가
- `CancelRaceIdempotencyIT` (신규, Testcontainers 실 MySQL) — 2 스레드(ExecutorService+CountDownLatch)가 동일 취소를 동시 착지시켜도 `cancel_request` 정확히 1건 COMPLETED, `PaymentItem` 이중취소 0, 양쪽 응답 모두 예외 없이 반환(500 아님)됨을 실 DB 로 증명

## Task Commits

1. **Task 1: executeCancel UK 위반 → 멱등 응답 번역 + 단위 테스트** — `b0e5a21` (feat)
2. **Task 2: CancelRaceIdempotencyIT — 2 파드 동시 취소 Testcontainers 재현** — `5479361` (test)

## Files Created/Modified
- `application/service/CancelPaymentService.java` - saveTx1 호출부 DataIntegrityViolationException 국소 catch → handleExistingRequest 위임
- `application/service/CancelPaymentServiceTest.java` - 레이스 패자 멱등 단위 케이스 추가
- `integration/CancelRaceIdempotencyIT.java` (신규) - 동시 취소 레이스 Testcontainers 재현

## Decisions Made
- **D-03 (locked):** 새 응답 DTO/형태 없이 `handleExistingRequest` 기존 상태 스위치 재사용 — api-spec.md §멱등성 처리 응답 계약(200+status) 그대로 준수.
- catch 는 `executeCancel` 의 `saveTx1` 호출 지점에만 국소 배치. `GlobalExceptionHandler` 미변경(전역 핸들러 오염 방지, RESEARCH.md Anti-Patterns 준수).
- `CancelRaceIdempotencyIT` 는 `CancelFlowIntegrationTest`(Spring/Testcontainers 골격, 이미 파일 존재해 재사용)와 `MerchantCancelUsageAtomicDeductIT`(동시성 골격)를 결합 — 신규 동시성 프리미티브 발명 없음(D-02).

## Deviations from Plan
None — plan executed exactly as written. RESEARCH.md Pitfall 1(TX3 동시 재실행 시 `InvalidPaymentItemStatusException` 오탐)은 이 플랜의 레이스 시나리오(TX1 INSERT UK 경합)에서는 발생하지 않았다 — saveTx1 이 UK 위반을 애플리케이션 레벨에서 이미 catch 하므로 패자는 TX3 근처에 도달하지 않고 즉시 `handleExistingRequest` 로 반환된다. Pitfall 1은 02-03(RESIL-03, ProcessingRecovery TX3 재실행 동시성)에서 실제로 마주칠 시나리오이며 이 플랜의 범위 밖이다.

## Issues Encountered
None.

## User Setup Required
None — 신규 인프라/외부 설정 없음. Docker 데몬만 로컬에 떠 있으면 `CancelRaceIdempotencyIT` 가 Testcontainers MySQL 을 자동 기동한다(CLAUDE.md `docker compose up -d` 전제와 별개로 이 IT 자체는 독립 컨테이너 사용).

## Next Phase Readiness
- RESIL-02 완료: 멀티파드 동시 취소 레이스 패자가 500 대신 멱등 200 을 반환하고, DB 는 이중취소 0 · COMPLETED 정확히 1건임을 실 MySQL 로 증명.
- k3s 스케일아웃 실험②의 "패자 500" 발견이 이 플랜으로 마감됨.
- 02-03(RESIL-03, ProcessingRecovery 동시성 가드)은 이 플랜과 독립적으로 `pg_retry_count` 원자 UPDATE 를 다루며, `CancelRaceIdempotencyIT` 가 증명한 동시성 재현 골격(ExecutorService+CountDownLatch+Testcontainers)을 그대로 이식할 수 있다. RESEARCH.md Pitfall 1(TX3 재실행 오탐 로깅)은 02-03 구현 시 재검토 대상.

---
*Phase: 02-consistency-recovery-gaps*
*Completed: 2026-07-29*

## Self-Check: PASSED

- FOUND: payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java
- FOUND: payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java
- FOUND: payment-service/src/test/java/com/example/payment/integration/CancelRaceIdempotencyIT.java
- FOUND: .planning/phases/02-consistency-recovery-gaps/02-02-SUMMARY.md
- FOUND commit: b0e5a21 (feat: saveTx1 UK 위반 → handleExistingRequest 멱등 응답)
- FOUND commit: 5479361 (test: CancelRaceIdempotencyIT)
