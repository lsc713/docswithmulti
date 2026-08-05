---
phase: 01-paid-tracer
plan: 02
subsystem: settlement-service / payout
tags: [payout, query, validation, idempotency, convergence]
requires: ["01-01"]
provides: ["GET payout-account (ACCT-02)", "GET payout (PAY-03)", "blank-field 400 (ACCT-01 edge)", "CONFIRM-03 convergence proof"]
affects: [settlement-service]
tech-stack:
  added: []
  patterns: ["custom BusinessException 404 (supersedes ResponseEntity.notFound)", "status-guarded UPDATE 0-row no-op idempotency"]
key-files:
  created:
    - settlement-service/src/main/java/com/example/settlement/application/exception/PayoutNotFoundException.java
    - settlement-service/src/main/java/com/example/settlement/application/exception/MerchantPayoutAccountNotFoundException.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/PayoutAccountResponse.java
    - settlement-service/src/test/java/com/example/settlement/integration/PayoutQueryIntegrationTest.java
    - settlement-service/src/test/java/com/example/settlement/integration/PayoutConvergenceIntegrationTest.java
  modified:
    - settlement-service/src/main/java/com/example/settlement/common/exception/ErrorCode.java
    - settlement-service/src/main/java/com/example/settlement/application/service/PayoutService.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/PayoutAccountController.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/PayoutController.java
    - docs/error-catalog.md
decisions:
  - "404 via settlement custom exception hierarchy (PAYOUT_NOT_FOUND / PAYOUT_ACCOUNT_NOT_FOUND), not ResponseEntity.notFound() — follows abb5e78 convention; GlobalExceptionHandler already maps BusinessException → errorCode.httpStatus."
  - "GET payout keyed by settlementId (findBySettlementId), consistent with POST approve; reuses existing PayoutResponse record."
  - "Query IT needs no @MockitoBean — IT profile (!local) BankTransferHttpClient stub satisfies the BankTransferPort bean; no bank interaction (payout rows seeded via jdbc)."
metrics:
  duration: "~90m"
  completed: 2026-08-05
status: complete
---

# Phase 01 Plan 02: Payout Query + Validation + Convergence Idempotency Summary

Rounded out the Phase-1 payout read/validation surface — GET account (ACCT-02) and GET payout (PAY-03) returning 200/404, the blank-field 400 edge (ACCT-01), and a 3-ordering CONFIRM-03 proof that webhook/poll/duplicate-callback converge to exactly one terminal transition through the shared status-guarded `applyResult`.

## What was built

**Task 1 — GET account + GET payout (404 via custom exception) + blank-field 400** (commit `a9ab06f`)
- `ErrorCode`: added `PAYOUT_NOT_FOUND` (404) and `PAYOUT_ACCOUNT_NOT_FOUND` (404).
- New exceptions `PayoutNotFoundException`, `MerchantPayoutAccountNotFoundException` (extend `BusinessException`, mirror `SettlementNotFoundException`).
- `PayoutService.getAccount(merchantId)` → `findActive` or throw 404; `PayoutService.getPayout(settlementId)` → `findBySettlementId` or throw 404.
- `PayoutAccountController` `GET /{merchantId}` → `PayoutAccountResponse {merchantId, bankCode, accountNumber, holderName, active}`; `PayoutController` `GET /{id}/payout` → existing `PayoutResponse`.
- Blank-field guard unchanged — reuses `InvalidPayoutAccountException` (400) from 01-01, asserted here.
- `docs/error-catalog.md`: 2 new 404 rows (1:1 with ErrorCode) + title updated for ACCT-02/PAY-03.
- `PayoutQueryIntegrationTest` (5 tests): missing account→404, PUT-then-GET account→200 fields, blank bankCode→400 + nothing written, missing payout→404, seeded payout→200. 404 tests assert `{code}` body (not empty-body).

**Task 2 — Order-independent convergence + duplicate-callback idempotency (CONFIRM-03)** (commit `7ac1ef4`)
- `PayoutConvergenceIntegrationTest` (3 tests): webhook→poll, poll→webhook, duplicate-callback. Each captures `paid_at` after the first arrival and asserts it is byte-identical after the second arrival — the second `applyResult` matches 0 rows (`WHERE status='PROCESSING'`) once the row is already PAID, so exactly one terminal transition occurs.

## Deviations from Plan

**1. [Directive from spawning agent — supersedes plan] 404 via custom exception, not `ResponseEntity.notFound()`**
- The plan's `<objective>` encoded not-found as `ResponseEntity.notFound()` with NO ErrorCode/handler edit (because at plan-time the handler had no 404 mapping). Commit `abb5e78` refactored payout to settlement's custom-exception hierarchy after the plan was written. Per the spawning agent's explicit instruction, implemented 404 via `PayoutNotFoundException` / `MerchantPayoutAccountNotFoundException` + 2 new ErrorCode entries; tests assert the `{code, message}` JSON body and status (strengthened, not weakened). GlobalExceptionHandler maps these correctly (BusinessException → errorCode.httpStatus). No shared/other-module ErrorCode touched — settlement-service only.

Otherwise the plan was executed as written.

## Threat model

- T-01-07 (racing terminal callback double-applies) — **mitigated & proven** by `PayoutConvergenceIntegrationTest` (0-row no-op, paid_at immutable).
- T-01-08 (blank account fields) — **mitigated**: `InvalidPayoutAccountException` → 400, nothing written (asserted).
- T-01-06 (cross-merchant IDOR on GET) — **accepted**, deploy-time NetworkPolicy gateway-only ingress (same class as SettlementQueryController). No in-service authz (spec §10).

## Verification

- `./gradlew :settlement-service:test --tests '*PayoutQueryIntegrationTest'` → 5/5 green.
- `./gradlew :settlement-service:test --tests '*PayoutConvergenceIntegrationTest'` → 3/3 green.
- Full module `./gradlew :settlement-service:test` → **43 tests, 0 failures, 0 errors, 0 skipped** (13 classes). No regression.

## settlement-only confirmation

All changes under `settlement-service/**` + `docs/error-catalog.md`. No payment/order/product/merchant edits, no new Flyway migration (V2 already has both tables), no shared ErrorCode edit. Zero new dependencies.

## Self-Check: PASSED
- All 5 created files exist; both commits (`a9ab06f`, `7ac1ef4`) present on `feat/settlement-payout`.
