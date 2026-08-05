---
phase: 02-hardening
plan: 01
subsystem: settlement-service / payout
tags: [payout, idempotency, concurrency, exception-handling, PAY-02]
requires: [PAY-01 payout tracer (Phase 1)]
provides: [race-safe 409-return-existing approve()]
affects: [settlement-service ErrorCode, GlobalExceptionHandler, PayoutService]
tech-stack:
  added: []
  patterns: [unique-constraint + DataIntegrityViolationException→409 catch, subclass @ExceptionHandler out-ranks generic]
key-files:
  created:
    - settlement-service/src/main/java/com/example/settlement/application/exception/PayoutAlreadyExistsException.java
    - settlement-service/src/test/java/com/example/settlement/integration/PayoutApproveHardeningIntegrationTest.java
  modified:
    - settlement-service/src/main/java/com/example/settlement/common/exception/ErrorCode.java
    - settlement-service/src/main/java/com/example/settlement/presentation/GlobalExceptionHandler.java
    - settlement-service/src/main/java/com/example/settlement/application/service/PayoutService.java
    - docs/error-catalog.md
decisions:
  - "approve() stays non-@Transactional so the IDENTITY INSERT flushes at save() and the uk_payout_settlement violation surfaces as DataIntegrityViolationException inside the catch (a TX wrapper would relocate the INSERT to commit → catch misses it → race-loser 500 + double submit)"
  - "Dedicated @ExceptionHandler(PayoutAlreadyExistsException) carries the existing PayoutResponse body; generic BusinessException handler ({code,message}) untouched — subclass handler out-ranks it in Spring"
metrics:
  duration: ~15m
  completed: 2026-08-05
status: complete
---

# Phase 2 Plan 01: Duplicate/Concurrent Approve → 409-Return-Existing Summary

PAY-02 이중지급 차단: `uk_payout_settlement` UK + `DataIntegrityViolationException`→409 catch 로 정산당 payout 정확히 1행을 보장하고, 순차 재승인 pre-check 경로와 경합 패자 catch 경로 모두 기존 payout 을 HTTP 409 로 반환.

## What Was Built

- **`ErrorCode.PAYOUT_ALREADY_EXISTS` (409)** — enum 의 status 정렬 규약대로 401 과 404 사이 `// 409` 섹션에 단일 신설.
- **`PayoutAlreadyExistsException extends BusinessException`** — 생성자가 기존 `Payout` 을 받아 final 필드로 보관, `getExisting()` 노출(핸들러가 바디 구성용). `PayoutNotPayableException` ctor 스타일 미러.
- **전용 `@ExceptionHandler(PayoutAlreadyExistsException.class)`** — `ResponseEntity.status(409).body(new PayoutResponse(existing.getId(), existing.getStatus(), existing.getAmount()))`. 더 구체적인 핸들러가 generic `@ExceptionHandler(BusinessException)` 보다 우선하므로 이 건만 payout 바디를 싣고, 나머지 BusinessException 은 그대로 `{code,message}`.
- **`PayoutService.approve` 양 경로 재배선**:
  - (a) pre-check `findBySettlementId(...).ifPresent(...)` → `throw new PayoutAlreadyExistsException(existing)` (기존 400 PayoutNotPayableException 대체).
  - (b) `insertProcessing(...)` 를 `try { } catch (DataIntegrityViolationException dup)` 로 감쌈 — catch 에서 승자 `findBySettlementId` 재조회 → `PayoutAlreadyExistsException(won)`, 재조회 empty 면 원 `dup` 재던짐.
  - `submit(...)` 은 INSERT 성공 후에만 도달 → 패자는 catch 에서 던져 submit 미도달 → 은행 제출 정확히 1회.
  - `approve()` 비-@Transactional 유지 + catch 에 DIVE-boundary `ponytail:` 주석(왜 TX 를 두면 안 되는지: IDENTITY flush 불변식).
- **`docs/error-catalog.md`** — payout 표 401 행 뒤에 409 행 추가(enum 순서 미러).

## Tests

`PayoutApproveHardeningIntegrationTest` (PayoutTracerIntegrationTest 하네스 복제: Testcontainers MySQL + RANDOM_PORT + kafka listener off, `@MockitoBean BankTransferPort`→`TransferAck(true)`, java.net.http.HttpClient/@LocalServerPort, TestRestTemplate 없음) — **6/6 green**:

| 케이스 | 검증 |
|--------|------|
| 순차 중복 | 1차 200 PROCESSING, 2차 **409 + 기존 payout 바디**(`"id":<payoutId>`/PROCESSING/24500.00, `PAYOUT_ALREADY_EXISTS` 코드 문자열 없음), 1행, `times(1).submit` |
| 2-thread 경합 | payout **정확히 1행**, 응답 코드 multiset **{200, 409}**(500 없음), **`verify(bankTransferPort, times(1)).submit(eq("PO-"+id), any(), any())`** — 이중지급 차단의 최핵심 가드 |
| 가드1 not-FINALIZED (OPEN) | 400 PAYOUT_NOT_PAYABLE, 0행 |
| 가드2 net≤0 (net 0) | 400 PAYOUT_NOT_PAYABLE, 0행 |
| 가드3 no active account | 400 PAYOUT_ACCOUNT_INACTIVE, 0행 |
| 존재하지 않는 정산 | 404 SETTLEMENT_NOT_FOUND |

전체 `:settlement-service:test` 스위트도 green (no-regression, BUILD SUCCESSFUL 8m23s).

## Commits

- `ffb1123` feat(02-01): PAYOUT_ALREADY_EXISTS(409) + 전용 409-with-body 핸들러 (Task 1)
- `9dace96` test(02-01): PAY-02 중복/경합 승인 hardening 실패 테스트(RED)
- `d46c5ee` feat(02-01): approve() 양 경로 409-return-existing (GREEN)

## TDD Gate Compliance

Task 2 는 tdd="true" — RED(`9dace96`, 순차 400·경합 500 2건 실패 확인) → GREEN(`d46c5ee`, 6/6 통과). REFACTOR 불필요(코드 clean). 게이트 순서 준수.

## Deviations from Plan

None — plan executed exactly as written. (Task 1 verify 의 `grep -q 'getExisting'` 를 통과시키려 예외를 Lombok `@Getter` 대신 명시적 `getExisting()` 게터로 작성 — 동작 동일, 계획 의도 그대로.)

## Threat Model Compliance

- **T-02-01** (concurrent double-pay, mitigate): uk_payout_settlement + DIVE→409 catch + 2-thread 테스트가 정확히 1행 + `times(1).submit` 로 이중지급 불가 실증. ✅
- T-02-02 / T-02-SC (accept): 신규 surface·의존성 없음.

## Settlement-Only Confirmation

payment/order/product/merchant 모듈 무변경. 신규 Flyway 마이그레이션 없음(기존 `uk_payout_settlement` 활용). 신규 패키지 0개(기존 JPA/web/Mockito/Testcontainers 재사용). 모든 변경은 `settlement-service/` + `docs/error-catalog.md` 한정.

## Known Stubs

None.

## Self-Check: PASSED

All created artifacts (PayoutAlreadyExistsException, PayoutApproveHardeningIntegrationTest, 02-01-SUMMARY) present; all three commits (ffb1123, 9dace96, d46c5ee) exist in git history.
