---
phase: 02-link
verified: 2026-07-31T17:02:33Z
status: passed
score: 6/6 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 2: 결제–주문 검증 링크 Verification Report

**Phase Goal:** 결제 생성이 상류 주문을 검증(fail-closed)하고 `payment.order_id`로 강하게 링크하며, 결제 신원을 X-User-Id 신뢰헤더에서 취득한다. 취소 코어는 불변.
**Verified:** 2026-07-31T17:02:33Z (worktree `/Users/juho/Documents/docswithmulti-order`, branch `feat/order-link`)
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Phase 2 Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `CreatePaymentService` calls order verify at the FRONT (before product reserve), forwarding X-User-Id; on verify failure, reserve+persist do NOT happen | ✓ VERIFIED | `CreatePaymentService.java:51` calls `orderVerifyPort.verify(command.userId(), orderItemIds)` before paymentKey generation (line 54) and `productStockPort.reserve` (line 60). `OrderVerifyHttpClient.java:49` sets header `X-User-Id` from the `userId` param, which traces to `PaymentController.java:31` `@RequestHeader("X-User-Id") long userId` → `CreatePaymentCommand.userId`. Behavioral proof: `CreatePaymentServiceTest.shouldNotReserveOrPersistWhenOrderVerifyUnavailable`/`...Rejected` assert `verifyNoInteractions(productStockPort)` + `verifyNoInteractions(paymentCreateTxWriter)`; `shouldReserveThenPersist` has an explicit `InOrder` assertion verify→reserve. `CreatePaymentReserveIntegrationTest.reserveSuccess_persists` (Testcontainers e2e) stubs verify before reserve via `MockRestServiceServer` (stub-consumption order = call order) and asserts `mockServer.verify()`. All tests re-run independently by verifier — green (see Behavioral Spot-Checks). |
| 2 | `OrderVerifyHttpClient` is fail-closed (order-service down/timeout/non-200 → payment rejected), mirroring `ProductStockHttpClient` | ✓ VERIFIED | `OrderVerifyHttpClient.java:57-59` explicit non-2xx guard → `OrderVerifyUnavailableException`; `catch (Throwable t)` (line 83-87) catches 5xx/timeout/`CallNotPermittedException`(CB OPEN) → `OrderVerifyUnavailableException` (503, fail-closed); `Error` rethrown unchanged (line 80-82); `HttpClientErrorException` 404/409/403 mapped to `OrderVerifyRejectedException` with matching `ErrorCode`, other 4xx → unavailable. `OrderVerifyHttpClientTest` (6 cases, re-run by verifier, all green) proves 200-success, 404/409/403-rejected, 5xx-unavailable, and CB-OPEN-after-2-failures (3rd call short-circuits, `restTemplate` invoked only twice — `verify(restTemplate, times(2))`). |
| 3 | On success, payment row stores the verified `order_id` (NOT NULL) | ✓ VERIFIED | `V18__add_order_id_to_payment.sql:5-7` — `ALTER TABLE payment ADD COLUMN order_id BIGINT NOT NULL, ADD INDEX idx_payment_order_id`. `PaymentCreateTxWriter.persist(...)` (line 29-39) receives `orderId` from `CreatePaymentService` and calls the new 8-arg `Payment.of(...,orderId)` (`Payment.java:124-149`). `PaymentJpaEntity` maps `order_id` `nullable=false` (line 40-41) and `from(Payment)` reads `payment.getOrderId()` (line 106). `CreatePaymentReserveIntegrationTest.reserveSuccess_persists` asserts via raw JDBC: `SELECT order_id FROM payment` equals the stubbed verified orderId (777L) — re-run by verifier, green. |
| 4 | `PaymentController` uses X-User-Id (`CreatePaymentRequest.userId` removed; merchant_id stays body) | ✓ VERIFIED | `CreatePaymentRequest.java` has no `userId` field (only `merchantId`, `pgType`, `cancelPeriodDays`, `items`). `PaymentController.java:31` binds `@RequestHeader("X-User-Id") long userId` into the command; `request.merchantId()` still read from body (line 35). `PaymentControllerCreateIT` (3 cases, re-run green): header value flows to command (`create_ownerComesFromXUserIdHeader`), a spoofed body `userId` is silently ignored (`create_bodyUserId_ifSent_isIgnored`, Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false` mirrors Spring Boot default), missing header → `verifyNoInteractions(createPaymentUseCase)`. |
| 5 | Flyway V18 adds `payment.order_id` NOT NULL + index; app boots (Testcontainers) | ✓ VERIFIED | `V18__add_order_id_to_payment.sql` present, V1-V17 untouched (only new file added). `CreatePaymentReserveIntegrationTest` and `CancelFlowIntegrationTest`/`CancelRaceIdempotencyIT`/`ProcessingRecoveryConcurrencyIT` all boot a fresh Testcontainers MySQL with the full V1-V18 chain applied and pass (re-run by verifier — all green, see below). No FK on `order_id` (index-only, cross-module DB access is HTTP-only per CLAUDE.md so no FK is possible), so the `orderId=0L` sentinel used by legacy 7-arg `Payment.of()`/`ofPending()`/`reconstruct()` call sites is schema-harmless. |
| 6 (CANCEL-01 gate) | Cancel-core source files unchanged vs merge-base AND cancel integration tests green | ✓ VERIFIED | Verifier independently re-ran the exact gate command: `git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/ \| grep -E 'Cancel(PaymentService\|TxWriter\|AuthorizationService\|HistoryRecorder\|PaymentCommand)\|CompensationRetryService\|PendingRecoveryService\|ProcessingRecoveryService\|CancelEventOutbox' \| grep -v '/test/' \| wc -l` → **0**. Full phase diff (20 files) confirmed to touch only the payment-creation path (OrderVerify*, CreatePaymentService, PaymentCreateTxWriter, Payment/PaymentJpaEntity, PaymentController/Request, ErrorCode, ResilienceConfig, V18, application.yml, tests). `CancelFlowIntegrationTest` (5 tests), `CancelRaceIdempotencyIT` (1 test), `ProcessingRecoveryConcurrencyIT` (3 tests) re-run by verifier — all green, 0 failures/errors. Grepped cancel-core service files (`Cancel*.java`, scheduler services) — none reference `orderId`/`getOrderId`, confirming the domain sentinel change is invisible to cancel logic. |

**Score:** 6/6 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `payment-service/.../application/interfaces/OrderVerifyPort.java` | Port interface, fail-closed contract documented | ✓ VERIFIED | Exists, `long verify(long userId, List<Long> orderItemIds)`, substantive Javadoc |
| `payment-service/.../infrastructure/http/OrderVerifyHttpClient.java` | Fail-closed HTTP client mirroring ProductStockHttpClient | ✓ VERIFIED | Exists, implements port, full catch-layer discipline (2xx guard, 404/409/403 mapping, Error rethrow, Throwable→unavailable) |
| `payment-service/.../db/migration/V18__add_order_id_to_payment.sql` | order_id NOT NULL + index | ✓ VERIFIED | Exists, exact DDL matches must-have |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `CreatePaymentService` | `OrderVerifyPort.verify(userId, orderItemIds)` | Called before `ProductStockPort.reserve` | ✓ WIRED | Line 51 (verify) precedes line 60 (reserve); constructor-injected; `InOrder` test assertion |
| `PaymentCreateTxWriter.persist(...)` | `payment.order_id` NOT NULL | 8-arg `Payment.of(...,orderId)` → `PaymentJpaEntity.from` | ✓ WIRED | orderId threaded through persist → domain factory → JPA column; DB-asserted in integration test |
| `PaymentController` | `OrderVerifyHttpClient` X-User-Id forwarding | `@RequestHeader X-User-Id` → `CreatePaymentCommand.userId` → `orderVerifyPort.verify(command.userId(), ...)` → HTTP header | ✓ WIRED | Full chain traced across 3 files; integration test asserts outbound `X-User-Id: 100` header via `MockRestRequestMatchers` |

### Behavioral Spot-Checks (independently re-run by verifier, not trusted from SUMMARY)

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Phase 2 unit/integration tests | `./gradlew :payment-service:test --tests '*OrderVerifyHttpClientTest' --tests '*CreatePaymentServiceTest' --tests '*PaymentControllerCreateIT' --tests '*CreatePaymentReserveIntegrationTest' --tests '*CancelFlowIntegrationTest' --tests '*CancelRaceIdempotencyIT' --tests '*ProcessingRecoveryConcurrencyIT'` | BUILD SUCCESSFUL; per-suite XML: CreatePaymentServiceTest 5/5, OrderVerifyHttpClientTest 6/6, CancelFlowIntegrationTest 5/5, CancelRaceIdempotencyIT 1/1, CreatePaymentReserveIntegrationTest 3/3, ProcessingRecoveryConcurrencyIT 3/3, PaymentControllerCreateIT 3/3 — all `failures="0" errors="0" skipped="0"` | ✓ PASS |
| CANCEL-01 diff gate | `git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/ \| grep -E '...' \| grep -v '/test/' \| wc -l` | `0` | ✓ PASS |
| No FK / cancel-core doesn't read orderId | `grep -rln getOrderId\|orderId` over Cancel*/CompensationRetryService/PendingRecoveryService/ProcessingRecoveryService | no matches | ✓ PASS |

### Anti-Patterns Found

None. Scanned all Phase 2 created/modified main-source files (`OrderVerifyPort`, `OrderVerifyHttpClient`, `CreatePaymentService`, `PaymentCreateTxWriter`, `Payment`, `PaymentJpaEntity`, `PaymentController`, `CreatePaymentRequest`, `V18` migration) for TBD/FIXME/XXX/TODO/HACK/PLACEHOLDER/"not yet implemented" — zero hits.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| PLINK-01 | 02-01-PLAN.md | Fail-closed order verify | ✓ SATISFIED | `OrderVerifyHttpClient` catch layer + `OrderVerifyHttpClientTest` (6/6 green) |
| PLINK-02 | 02-01-PLAN.md | `payment.order_id` NOT NULL link | ✓ SATISFIED | `V18` + integration test DB assertion |
| PLINK-03 | 02-01-PLAN.md | Verify at front, no side effects on failure | ✓ SATISFIED | `CreatePaymentService` ordering + `verifyNoInteractions` tests |
| TRUST-01 | 02-01-PLAN.md | X-User-Id trust header, body userId removed | ✓ SATISFIED | `CreatePaymentRequest` (no userId field) + `PaymentControllerCreateIT` |
| CANCEL-01 | 02-01-PLAN.md | Cancel core unchanged | ✓ SATISFIED | Independently re-run diff gate = 0; cancel integration tests green |

Note: `.planning/workstreams/order-link/REQUIREMENTS.md` traceability table still shows these as "Pending" in its status column — this is a documentation-freshness lag in REQUIREMENTS.md, not a code gap. Does not affect this phase's verdict; worth updating in a follow-up doc pass.

### Deviation Scrutiny

**(a) `reconstruct` sentinel overload extension.** Plan claimed the pre-existing 11-arg `Payment.reconstruct(...)` had no test callers; a grep found it does (`PaymentTest`, `CreatePaymentServiceTest`, `CreatePaymentCompensationTest`, `PaymentControllerCreateIT`). Executor kept the 11-arg signature byte-identical (delegates to new 12-arg with `orderId=0L`), so none of those call sites needed edits — confirmed by reading `Payment.java:184-201` and grepping all 6 `reconstruct(` call sites; all pre-existing tests still compile/pass with the original arg count. `order_id=0` sentinel is confirmed harmless: no FK constraint on the column (index-only, `V18__add_order_id_to_payment.sql` line 6-7) and no cancel-core file reads `getOrderId()`/`orderId` (verified by grep across `Cancel*Service`, `CompensationRetryService`, `PendingRecoveryService`, `ProcessingRecoveryService`). **Confirmed correct.**

**(b) Standalone-MockMvc missing-X-User-Id → 500.** `PaymentControllerCreateIT` uses `MockMvcBuilders.standaloneSetup(...)` which lacks Spring Boot's full `DispatcherServlet` binding-exception resolvers, so `MissingRequestHeaderException` falls to the generic handler (500) instead of the framework default (400). Verified this is a test-harness artifact, not a production gap: (1) `RouteConfig.java:38-43` routes `/v1/payments/**` through `JwtTrustHeaderFilter` (`.filter(jwt)`); (2) `JwtTrustHeaderFilter.java:41-59` strips any client-forged `X-User-Id` and short-circuits with 401 (`unauthorized("TOKEN_MISSING")`) if no valid cookie/Bearer token is present — `next.handle(...)` (which proxies to payment-service) is only reached after step 4 injects the real `X-User-Id` from the verified JWT subject. So in production, a request without a valid token never reaches payment-service at all (401 at the gateway), and a request that does reach payment-service always carries `X-User-Id` (injected by the filter, not client-controlled). Combined with the NetworkPolicy gate (payment ingress restricted to gateway pods, verified in Phase 1 GW-02), the missing-header path the standalone test exercises (500) is unreachable in the deployed system. The IT's real contract — `verifyNoInteractions(createPaymentUseCase)` — is unaffected by the status-code detail. **Confirmed correct, no action needed.**

### Human Verification Required

None. All 6 success criteria are verified through independently re-run automated tests and direct code inspection; no visual/real-time/external-service behavior in scope for this phase.

### Gaps Summary

None. All ROADMAP Phase 2 success criteria hold in the codebase, all evidence was independently reproduced by the verifier (not taken from SUMMARY.md claims), and the CANCEL-01 gate was independently re-executed with the exact specified command, returning 0.

---

_Verified: 2026-07-31T17:02:33Z_
_Verifier: Claude (gsd-verifier)_
