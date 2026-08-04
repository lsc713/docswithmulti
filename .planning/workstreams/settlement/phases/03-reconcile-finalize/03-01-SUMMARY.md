---
phase: 03-reconcile-finalize
plan: 01
subsystem: payment-service
tags: [reconcile, read-only-query, settlement, RECON-03]
requires: [payment.created_at, cancel_request.completed_at (V8), idx_payment_merchant_id]
provides: [GET /v1/payments/settlement, PaymentSettlementResponse wire contract]
affects: [settlement-service 03-02 HTTP pull client]
tech-stack:
  added: []
  patterns: [read-only hexagonal query (mirror PaymentExistsQuery), native JPA interface projection, controller-local @ExceptionHandler]
key-files:
  created:
    - payment-service/src/main/java/com/example/payment/application/usecase/PaymentSettlementQuery.java
    - payment-service/src/main/java/com/example/payment/application/service/PaymentSettlementQueryService.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentSettlementJpaRepository.java
    - payment-service/src/main/java/com/example/payment/presentation/dto/PaymentSettlementResponse.java
    - payment-service/src/main/java/com/example/payment/presentation/controller/PaymentSettlementController.java
    - payment-service/src/test/java/com/example/payment/integration/PaymentSettlementQueryIntegrationTest.java
  modified: []
decisions:
  - "D-Q1: SALE windowed by payment.created_at, CANCEL windowed independently by cancel_request.completed_at — cross-week carrier appears with only its in-window cancel"
  - "Validation 400 via controller-local @ExceptionHandler (GlobalExceptionHandler untouched — outside INV-01 allowlist)"
metrics:
  duration: ~15m
  completed: 2026-08-04
status: complete
---

# Phase 3 Plan 01: RECON-03 Read-Only Payment Settlement Query Summary

Read-only `GET /v1/payments/settlement?merchantId&from&to` returning completed payments (SALE window by `payment.created_at`) with their in-window cancellations (CANCEL window by `cancel_request.completed_at`, windowed independently of the parent payment's week), assembled into `{paymentKey, merchantId, totalAmount, status, createdAt, cancels:[{cancelRequestId, cancelAmount, completedAt}]}` — five NEW `PaymentSettlement*` files, no migration, no cancel-core edit.

## What was built

- **PaymentSettlementQuery** (usecase): interface + `PaymentSettlementView`/`CancelView` application-layer projection records (no web/Spring types).
- **PaymentSettlementJpaRepository** (persistence, NEW, extends `JpaRepository<PaymentJpaEntity, Long>`): derived `findByMerchantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan` (SALE) + native JOIN query `findCompletedCancelsInWindow` returning a `CancelRow` interface projection carrying parent-payment fields (CANCEL).
- **PaymentSettlementQueryService** (`@Transactional(readOnly=true)`): runs both queries, seeds a `LinkedHashMap` from non-PENDING SALE payments, folds in cancels creating carrier entries for out-of-window parents. UTC `Instant`↔`LocalDateTime` conversion both directions.
- **PaymentSettlementResponse** (DTO record + nested `Cancel`): wire contract, `Instant` serialized ISO-8601 UTC (trailing Z).
- **PaymentSettlementController** (`/v1/payments/settlement`): validates `merchantId>0`, `from<to`, ≤60-day window (+ ISO-8601 parse) BEFORE querying; maps views→responses. No caller authz by design — fenced by deploy-time NetworkPolicy (documented gate below).
- **PaymentSettlementQueryIntegrationTest**: Testcontainers MySQL + webAppContextSetup MockMvc, JdbcTemplate seed. 3 tests, all green.

## Verification

- `./gradlew :payment-service:compileJava` — BUILD SUCCESSFUL.
- `./gradlew :payment-service:test --tests '*PaymentSettlementQueryIntegrationTest*'` — **3 tests, 0 failures**:
  1. window semantics: in-window SALE returned; out-of-window SALE excluded; cross-week carrier returned with only its in-window COMPLETED cancel; non-COMPLETED (FAILED) and out-of-window cancels excluded; other-merchant excluded; `createdAt`/`completedAt` serialized with trailing `Z`.
  2. read-only invariance: payment/payment_item/cancel_request counts + sample cancel row (status, completed_at) unchanged before/after GET.
  3. input validation: `merchantId<=0`, `from>=to`, `>60-day` window each → HTTP 400.

## Deviations from Plan

**1. [Rule 3 - Blocking] 400 mapping via controller-local `@ExceptionHandler` instead of relying on `GlobalExceptionHandler`**
- **Found during:** Task 1.
- **Issue:** The plan stated "throw IllegalArgumentException; the existing GlobalExceptionHandler maps it" to 400. `GlobalExceptionHandler` only maps `BusinessException`, `MethodArgumentNotValidException`, and generic `Exception`→500 — an unmapped `IllegalArgumentException` would return 500, not 400. Editing `GlobalExceptionHandler` was forbidden (not `PaymentSettlement*`-prefixed → would break the INV-01 single-token allowlist).
- **Fix:** Added a controller-local `@ExceptionHandler(IllegalArgumentException.class)` inside `PaymentSettlementController` returning `400 {code:INVALID_REQUEST, message}` (mirrors `GlobalExceptionHandler.errorBody` style). Controller-local handlers take precedence over `@RestControllerAdvice`, so the 400 path works while staying entirely inside an allowlisted file.
- **Files:** `PaymentSettlementController.java`.
- **Commit:** `0fc993b`.

**2. [Test harness] MockMvc `webAppContextSetup` (allowed path) instead of RANDOM_PORT + `java.net.http.HttpClient`**
- The guardrail permits either MockMvc `webAppContextSetup` OR RANDOM_PORT+HttpClient (only `TestRestTemplate` is forbidden). Chose the MockMvc path to reuse the proven `PaymentExistsEndpointIntegrationTest` pattern already on the payment-service test classpath (which uses `com.fasterxml.jackson.databind.ObjectMapper`); this exercises `@RestControllerAdvice` and the controller-local `@ExceptionHandler`, so 400 behavior is genuinely verified. No `TestRestTemplate` used. Seeding is JdbcTemplate-direct (plan-specified) for exact `created_at`/`completed_at` control.

## Deploy Gate (record for ops)

`GET /v1/payments/settlement` exposes **cross-merchant financial data with no caller authz by design** (T-03-01). It MUST be fenced at deploy time by a NetworkPolicy restricting payment ingress to the gateway pod only — same class as `infra/k8s/networkpolicy/product-ingress.yaml` and the existing payment/auth ingress gate. Without it, `merchantId` tampering reads any merchant's settlement. Input validation (merchantId>0, from<to, ≤60d) bounds the request but does NOT provide authz.

## Threat Flags

None — no new security surface beyond the documented, mitigated register (T-03-01 fenced by NetworkPolicy + input validation; T-03-02 read-only + IT-asserted invariance; T-03-03 all-new PaymentSettlement* files). No new dependency.

## Known Stubs

None.

## Scope confirmation

`git diff --name-only HEAD~2 HEAD` = exactly the 6 files above. No settlement-service file, no cancel-core write path, no `PaymentController.java`/`PaymentJpaRepository.java`, no Flyway migration. INV-01 allowlist delta = single `PaymentSettlement` token.

## Self-Check: PASSED
- All 6 created files exist on disk (Write confirmed).
- Commits `0fc993b` (feat, Task 1) and `3890cf6` (test, Task 2) exist on `feat/settlement-aggregation`.
