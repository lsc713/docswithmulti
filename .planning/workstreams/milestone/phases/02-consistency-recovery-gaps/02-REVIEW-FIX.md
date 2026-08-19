---
phase: 02-consistency-recovery-gaps
fixed_at: 2026-07-29T04:34:39Z
review_path: .planning/phases/02-consistency-recovery-gaps/02-REVIEW.md
iteration: 1
findings_in_scope: 8
fixed: 8
skipped: 0
status: all_fixed
---

# Phase 02: Code Review Fix Report

**Fixed at:** 2026-07-29T04:34:39Z
**Source review:** .planning/phases/02-consistency-recovery-gaps/02-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 8 (CR-01, CR-02, CR-03, WR-01, WR-02, WR-03, WR-04, WR-05)
- Fixed: 8
- Skipped: 0

All work was performed in an isolated git worktree (`gsd-reviewfix/02-28913`, branched from
`fix/payment-service-HighCancelLatency`) and fast-forwarded back onto the source branch on
completion. `./gradlew :payment-service:test` (full suite, including Testcontainers ITs) passes
with 0 failures/errors after all 8 fixes are applied.

## Fixed Issues

### CR-01: FAILED 재시도가 자기 자신과 UK 충돌 → risk/PG 재호출 없이 조용히 무시됨

**Files modified:** `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java`, `payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java`
**Commit:** a570ead
**Commit status:** fixed: requires human verification (state-machine flow re-plumb — logic error class per verification_strategy)
**Applied fix:** Split `executeCancel` into a "TX1 INSERT"-only path (`executeCancel`) and a shared
`proceedFromRisk(payment, cancelRequest, command)` continuation (risk→TX2→PG→TX3). The FAILED-retry
branch in `handleExistingRequest` now calls `raiseToPending()` + `cancelRequestRepository.save()`
(existing row, id preserved) and re-enters directly at `proceedFromRisk` — no `saveTx1` re-INSERT,
so the self-collision against the `(payment_id, request_hash)` UK described in the finding cannot
occur anymore. Updated the existing `shouldRaiseFailedToPendingAndContinueWhenExistingFailed` test
to assert `saveTx1` is never called on retry and the resurrected row's id (99L) survives through to
COMPLETED.

### CR-02: risk 호출 실패 시 "명확한 에러"와 "타임아웃/네트워크 유실"을 구분하지 않고 항상 compensate() 호출

**Files modified:** `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java`, `payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java`
**Commit:** d0087a8
**Commit status:** fixed: requires human verification (branch selection on financial compensation path — logic error class)
**Applied fix:** In `proceedFromRisk`'s risk-call catch block, wired `riskManagementPort.isCharged(cancelRequest.getId())`
(D-05, already used by `PendingRecoveryService`) to decide whether `tryCompensate` runs, matching
`cancel-design.md`'s 97-113 branch. Added a fail-safe: if `isCharged()` itself throws (double
failure), falls back to compensating (safer than silently skipping a real deduction). Added tests
for isCharged=true (compensate), isCharged=false (no compensate, no compensation_retry), and
isCharged()-throws-itself (fail-safe compensate).

### CR-03: ProcessingRecoveryService.compensateAndFail()에 동시성 가드 부재 — 이중 보상 위험

**Files modified:** `payment-service/src/main/java/com/example/payment/application/interfaces/CancelRequestRepository.java`, `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaRepository.java`, `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImpl.java`, `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java`, `payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java`, `payment-service/src/test/java/com/example/payment/integration/ProcessingRecoveryConcurrencyIT.java`
**Commit:** 016ebc1
**Commit status:** fixed: requires human verification (concurrency guard on financial compensation path — logic error class)
**Applied fix:** Per D-04 (no per-record distributed lock) and the CONTEXT.md instruction, added a
new conditional atomic UPDATE `compareAndSetFailed(id)` (`WHERE status='PROCESSING'`) that reuses
the exact `incrementPgRetryCount` pattern already in the codebase. `compensateAndFail` now calls
this guard **before** calling `riskManagementPort.compensate()` — only the thread that wins the
atomic PROCESSING→FAILED transition proceeds to compensate; losers (`rowcount=0`) skip entirely.
This directly fixes the "compensate runs before state transition" ordering bug the finding called
out, without introducing any new lock or schema change. Added a unit test proving the skip path and
a new real-MySQL Testcontainers verification (C) in `ProcessingRecoveryConcurrencyIT` proving that
N concurrent threads calling `compareAndSetFailed` on the same PROCESSING row produce exactly 1
winner.

### WR-01: PG 상태값이 APPROVED/FAILED/PENDING 중 어느 것도 아니면 조용히 무시(로그 없음)

**Files modified:** `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java`, `payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java`
**Commit:** ae14fe6
**Applied fix:** Added an `else` branch to the APPROVED/FAILED/PENDING if-chain in `recoverOne` that
logs `log.warn` with the unknown status string and cancelRequestId, matching the existing PG-query-failure
log pattern. Added a unit test asserting no state change happens for an unrecognized status and the
service doesn't throw.

### WR-02: 정상적인 레이스 패자(BusinessException)를 ERROR 레벨 "데이터 정합성 문제"로 로깅 — 알림 오탐

**Files modified:** `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java`, `payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java`
**Commit:** 62e34a3
**Applied fix:** Added a `catch (InvalidPaymentItemStatusException e)` block before the generic
`catch (BusinessException e)`, logging at WARN with "동시 처리 경쟁(예상됨)" instead of ERROR
"데이터 정합성 문제". Other `BusinessException` subtypes keep the original ERROR logging. Added two
Logback `ListAppender`-based tests: one confirming the race-loss path logs WARN (never ERROR), one
confirming a non-race `BusinessException` (`InvalidCancelStateTransitionException`) still logs ERROR.

### WR-03: RiskManagementHttpClient.compensate()가 응답 상태/바디를 검증하지 않음

**Files modified:** `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java`, `payment-service/src/test/java/com/example/payment/infrastructure/http/RiskManagementHttpClientTest.java`
**Commit:** cf215c8
**Applied fix:** Added the same explicit `is2xxSuccessful()` guard already present on
`validateAndReserve`/`isCharged`/PG client methods, throwing `RiskServiceException` on a non-2xx
response instead of relying on `RestTemplate`'s default error handler. Added tests for the success
path and the non-2xx-without-thrown-exception path.

### WR-04: 서로 무관한 오퍼레이션이 CircuitBreaker 인스턴스를 공유

**Files modified:** `payment-service/src/main/java/com/example/payment/infrastructure/config/ResilienceConfig.java`, `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java`, `payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java`, `payment-service/src/test/java/com/example/payment/infrastructure/http/RiskManagementHttpClientTest.java`, `payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java`
**Commit:** a743e1a
**Applied fix:** Added two new CircuitBreaker beans (`risk-management-read`, `pg-cancel-read`) in
`ResilienceConfig`. `RiskManagementHttpClient.isCharged()` and `PgCancelHttpClient.getStatus()` now
run on their dedicated read circuit breaker, separate from the write/compensation-path breaker used
by `validateAndReserve`/`compensate`/`cancel`. Constructor signatures gained a 4th
`CircuitBreaker` parameter (autowired by bean name via Spring's constructor-injection-by-name
disambiguation). Updated both existing test files' 3-arg constructions to 4-arg, and added a
regression test per client proving the read breaker opening does not block the write breaker.

### WR-05: catch(Throwable t)가 Error까지 포섭해 애플리케이션 예외로 재포장

**Files modified:** `payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java`, `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java`, `payment-service/src/test/java/com/example/payment/infrastructure/http/RiskManagementHttpClientTest.java`, `payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java`
**Commit:** 9fe172a
**Applied fix:** Added `catch (Error e) { throw e; }` before the existing `catch (Throwable t)` in
all 5 call sites across both HTTP clients (`PgCancelHttpClient.cancel/getStatus`,
`RiskManagementHttpClient.validateAndReserve/compensate/isCharged`), so `Error`s (e.g.
`OutOfMemoryError`, `StackOverflowError`) propagate unwrapped instead of being re-packaged as
`PgServiceException`/`RiskServiceException` and continuing normal business-exception flow. Added a
test per client proving a thrown `Error` propagates as-is through `CircuitBreaker.executeCheckedSupplier`.

## Skipped Issues

None — all 8 in-scope findings (3 Critical, 5 Warning) were fixed.

---

_Fixed: 2026-07-29T04:34:39Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
