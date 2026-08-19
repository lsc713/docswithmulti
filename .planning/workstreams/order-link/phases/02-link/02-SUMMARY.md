---
phase: 02-link
plan: 01
subsystem: payment-service
tags: [order-link, order-verify, fail-closed, trust-header, flyway, cancel-core-gate]
requires:
  - "POST /v1/orders/items:verify (internal, order-service, Phase 1)"
provides:
  - "OrderVerifyPort/OrderVerifyHttpClient (payment-service, fail-closed order verification)"
  - "payment.order_id (NOT NULL, V18)"
  - "POST /v1/payments X-User-Id trust header (CreatePaymentRequest.userId removed)"
affects:
  - payment-service/application/service/CreatePaymentService
  - payment-service/application/service/PaymentCreateTxWriter
  - payment-service/domain/entity/Payment
  - payment-service/infrastructure/persistence/PaymentJpaEntity
  - payment-service/presentation/controller/PaymentController
  - payment-service/presentation/dto/CreatePaymentRequest
tech-stack:
  added: []
  patterns:
    - "OrderVerifyPort/OrderVerifyHttpClient mirrors ProductStockPort/ProductStockHttpClient (RestTemplate + Resilience4j CircuitBreaker + catch-layer discipline)"
    - "Domain factory sentinel pattern: legacy N-arg Payment.of()/ofPending()/reconstruct() signatures kept byte-identical, delegating orderId=0L internally; only a new N+1-arg overload carries the real orderId — keeps unrelated cancel-flow fixtures/tests uneditted"
key-files:
  created:
    - payment-service/src/main/resources/db/migration/V18__add_order_id_to_payment.sql
    - payment-service/src/main/java/com/example/payment/application/interfaces/OrderVerifyPort.java
    - payment-service/src/main/java/com/example/payment/infrastructure/http/OrderVerifyHttpClient.java
    - payment-service/src/main/java/com/example/payment/infrastructure/exception/OrderVerifyUnavailableException.java
    - payment-service/src/main/java/com/example/payment/infrastructure/exception/OrderVerifyRejectedException.java
    - payment-service/src/test/java/com/example/payment/infrastructure/http/OrderVerifyHttpClientTest.java
    - payment-service/src/test/java/com/example/payment/presentation/controller/PaymentControllerCreateIT.java
  modified:
    - payment-service/src/main/java/com/example/payment/domain/entity/Payment.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentJpaEntity.java
    - payment-service/src/main/java/com/example/payment/application/service/PaymentCreateTxWriter.java
    - payment-service/src/main/java/com/example/payment/application/service/CreatePaymentService.java
    - payment-service/src/main/java/com/example/payment/presentation/controller/PaymentController.java
    - payment-service/src/main/java/com/example/payment/presentation/dto/CreatePaymentRequest.java
    - payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java
    - payment-service/src/main/java/com/example/payment/infrastructure/config/ResilienceConfig.java
    - payment-service/src/main/resources/application.yml
    - payment-service/src/test/resources/application.yml
    - docs/error-catalog.md
    - payment-service/src/test/java/com/example/payment/application/service/CreatePaymentServiceTest.java
    - payment-service/src/test/java/com/example/payment/application/service/CreatePaymentCompensationTest.java
    - payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java
    - payment-service/src/test/java/com/example/payment/integration/PaymentExistsEndpointIntegrationTest.java
decisions:
  - "Flyway V18 goes straight to NOT NULL (spec option a, no nullable→backfill→NOT NULL two-step): test surface is Testcontainers (fresh MySQL, V1-V18 applied to empty tables), and any pre-existing local dev payment rows have no known order_id to backfill — dev DB must be recreated if it has legacy rows."
  - "Payment domain factory/reconstruct overloads kept byte-identical for all existing call sites (7/8-arg of(), ofPending(), 11-arg reconstruct()) with an internal orderId=0L sentinel; a new 8-arg of(...,orderId) and 12-arg reconstruct(...,orderId) carry the real value, used only by the create path and PaymentJpaEntity.toDomain respectively."
  - "OrderVerifyHttpClient's response DTO (VerifyResponse) is package-private (not private) so OrderVerifyHttpClientTest, in the same package, can construct it directly for stubbing — mirrors no prior client in this codebase needing an explicit response body, this is the first payment-service HTTP client to parse a JSON response body rather than a Void/typed-port-record response."
metrics:
  duration: "~2h"
  completed: "2026-08-01"
status: complete
---

# Phase 2 Plan 1: Payment-side order verification link Summary

Payment creation now calls order-service's `items:verify` at the very front of the flow (before stock reservation), fail-closed on any order-service failure or ownership/existence rejection, and persists the verified `orderId` on every payment row (`NOT NULL`). Payment ownership is now taken exclusively from the `X-User-Id` trust header, not the request body. The cancel core (idempotency, TX1/2/3, three schedulers, outbox) is provably untouched — zero cancel-core files appear in the phase diff.

## What was built

**Task 1 (tracer) — end-to-end happy path.** `OrderVerifyPort`/`OrderVerifyHttpClient` (mirrors `ProductStockPort`/`ProductStockHttpClient`: shared `RestTemplate` bean, dedicated `orderServiceCircuitBreaker`, `@Value("${external.order-service.url}")`) call `POST /v1/orders/items:verify` with `X-User-Id` forwarded and `{orderItemIds}` in the body, returning the verified `orderId`. `CreatePaymentService` was re-sequenced to `order verify → paymentKey → product reserve → persist(orderId)` — verify runs before any side effect (reserve/persist), so a verify failure needs no compensation. Flyway `V18__add_order_id_to_payment.sql` adds `payment.order_id BIGINT NOT NULL` + `idx_payment_order_id`. `Payment` domain entity gained an `orderId` field with a new 8-arg `of(...)` factory used only by the create path; all pre-existing `of()`/`ofPending()`/`reconstruct()` call sites (cancel-flow tests/fixtures) compile unedited via an internal `orderId=0L` sentinel. `PaymentController.create` now reads `@RequestHeader("X-User-Id") long userId`; `CreatePaymentRequest.userId` was removed. Proven end-to-end by `CreatePaymentReserveIntegrationTest` (Testcontainers + MockRestServiceServer): verify(200) is stubbed and consumed before the reserve stub, and `payment.order_id` is asserted in the DB row.

**Task 2 — fail-closed + 4xx mapping.** New `infrastructure/exception` classes: `OrderVerifyUnavailableException` (503, `ORDER_VERIFY_UNAVAILABLE`) for order-service 5xx/timeout/non-2xx/CircuitBreaker-OPEN, and `OrderVerifyRejectedException` (carries the mapped `ErrorCode`) for order-service's 404/409/403. `OrderVerifyHttpClient`'s catch layer was completed to mirror `ProductStockHttpClient` exactly: explicit 2xx guard, `HttpClientErrorException` status-based remapping (404→`ORDER_ITEM_NOT_FOUND`, 409→`ORDER_ITEMS_MULTIPLE_ORDERS`, 403→`ORDER_OWNERSHIP_MISMATCH`, other 4xx→unavailable), `Error` rethrown unchanged, everything else (5xx/timeout/`CallNotPermittedException`)→unavailable. `OrderVerifyHttpClientTest` (new, 6 cases) proves all six branches including CB-OPEN short-circuiting the 3rd call. `CreatePaymentServiceTest` gained two fail-closed cases (`verifyNoInteractions(productStockPort)` + `verifyNoInteractions(paymentCreateTxWriter)` on both exception types) plus an `InOrder` assertion on the happy path proving verify precedes reserve. `docs/error-catalog.md` gained a payment-side "결제 생성 — order 검증 거부/장애" section and an `infrastructure/exception` mapping row.

**Task 3 — TRUST-01 regression + CANCEL-01 gate.** New `PaymentControllerCreateIT` (standalone MockMvc, mirrors order-service's `OrderControllerIT`): owner comes from `X-User-Id` header (`ArgumentCaptor` on the command), a spoofed `userId` in the request body is silently ignored (Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`, matches Spring Boot's default), and a missing `X-User-Id` header short-circuits before the use case is ever invoked. CANCEL-01 gate command run and green (see below). Full `payment-service` module test suite green including the existing cancel integration tests.

## Tests run + results

- `./gradlew :payment-service:test --tests '*CreatePaymentReserveIntegrationTest'` — green (Task 1).
- `./gradlew :payment-service:test --tests '*OrderVerifyHttpClientTest' --tests '*CreatePaymentServiceTest'` — green (Task 2).
- `./gradlew :payment-service:test --tests '*PaymentControllerCreateIT' --tests '*CancelFlowIntegrationTest' --tests '*CancelRaceIdempotencyIT' --tests '*ProcessingRecoveryConcurrencyIT'` — green (Task 3).
- `./gradlew :payment-service:test` (full module suite, run 3 times across the phase after each task) — green every time, no regressions (final run: all tests pass, 0 failures).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - blocking] Constructor/persist signature changes broke compilation of test files not listed in any task's `files_modified`**
- **Found during:** Task 1 (surfaced when running the full module test suite after committing the tracer)
- **Issue:** The plan's per-task `files_modified` lists were incomplete. Adding `OrderVerifyPort` to `CreatePaymentService`'s constructor and adding an `orderId` parameter to `PaymentCreateTxWriter.persist(...)` broke compilation (Gradle compiles the whole test sourceSet regardless of `--tests` filter) of three files the plan never mentioned: `CreatePaymentServiceTest.java` (listed only in Task 2, but its constructor call and `persist(...)` mock stubs needed updating for Task 1 to even compile), `CreatePaymentCompensationTest.java` (not listed in any task), and `PaymentExistsEndpointIntegrationTest.java` (not listed in any task, but posts to `/v1/payments` via the real endpoint and failed at runtime with a `NOT NULL` constraint / missing header once the flow changed).
- **Fix:** Added `@Mock OrderVerifyPort orderVerifyPort` + a `lenient()` stub returning a fixed orderId to `CreatePaymentServiceTest`/`CreatePaymentCompensationTest`, updated all `persist(...)` mock invocations to the new 4-arg signature, and added an `X-User-Id` header + an order-verify `MockRestServiceServer` stub (before the reserve stub) to `PaymentExistsEndpointIntegrationTest`.
- **Files modified:** `payment-service/src/test/java/com/example/payment/application/service/CreatePaymentServiceTest.java`, `payment-service/src/test/java/com/example/payment/application/service/CreatePaymentCompensationTest.java`, `payment-service/src/test/java/com/example/payment/integration/PaymentExistsEndpointIntegrationTest.java`
- **Commit:** c28beb4

**2. [Rule 1 - plan premise incorrect] Plan claimed `Payment.reconstruct(...)` has no test callers — false**
- **Found during:** Task 1 planning (before writing code)
- **Issue:** The plan states widening `reconstruct(...)` is safe because "유일 호출자는 PaymentJpaEntity.toDomain — 테스트 호출자 없음." A grep found direct test callers of the existing 11-arg `reconstruct(...)` in `PaymentTest.java`, `CreatePaymentServiceTest.java`, and `CreatePaymentCompensationTest.java`.
- **Fix:** Extended the same sentinel-overload discipline the plan already prescribes for `of()`/`ofPending()` to `reconstruct()`: the existing 11-arg `reconstruct(...)` signature is kept byte-identical and delegates internally to a new 12-arg `reconstruct(...,orderId,...)` with `orderId=0L`; the 12-arg overload (real orderId) is used only by `PaymentJpaEntity.toDomain`. No test file needed editing for this specific change.
- **Files modified:** `payment-service/src/main/java/com/example/payment/domain/entity/Payment.java`
- **Commit:** c28beb4

**3. [Rule 3 - blocking] Standalone-MockMvc missing-header test could not assert `4xxClientError` as planned**
- **Found during:** Task 3
- **Issue:** `PaymentControllerCreateIT` uses `MockMvcBuilders.standaloneSetup(...)` (no full Spring Boot `DispatcherServlet`/binding-exception resolvers), so a missing required `@RequestHeader("X-User-Id")` throws `MissingRequestHeaderException`, which falls through to `GlobalExceptionHandler`'s generic `Exception` handler and returns 500, not 400 as a full Spring Boot app would.
- **Fix:** Asserted the exact observed status (500) instead of `is4xxClientError()`, with a comment explaining the standalone-MockMvc limitation and that the test's real contract (owner determination never proceeds without the header — `verifyNoInteractions(createPaymentUseCase)`) is unaffected by the status-code detail.
- **Files modified:** `payment-service/src/test/java/com/example/payment/presentation/controller/PaymentControllerCreateIT.java`
- **Commit:** 17919c6

No other deviations — the rest of the plan (Flyway V18 direct-to-NOT-NULL, OrderVerifyPort/HttpClient mirroring ProductStockPort/HttpClient, verification-at-front ordering, X-User-Id trust header, CANCEL-01 gate scope) executed exactly as written.

### Auth gates
None encountered.

## Known Stubs
None — order verification, fail-closed catch layers, and the X-User-Id trust-header path are all fully wired against real (mocked-in-test) HTTP calls; no hardcoded/placeholder data.

## CANCEL-01 Gate Result

Command (exact, as specified):

```
git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/ | grep -E 'Cancel(PaymentService|TxWriter|AuthorizationService|HistoryRecorder|PaymentCommand)|CompensationRetryService|PendingRecoveryService|ProcessingRecoveryService|CancelEventOutbox' | grep -v '/test/' | wc -l
```

Output: **0**.

Full phase diff file list (19 files, all in the payment creation path — zero cancel-core):
```
payment-service/src/main/java/com/example/payment/application/interfaces/OrderVerifyPort.java
payment-service/src/main/java/com/example/payment/application/service/CreatePaymentService.java
payment-service/src/main/java/com/example/payment/application/service/PaymentCreateTxWriter.java
payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java
payment-service/src/main/java/com/example/payment/domain/entity/Payment.java
payment-service/src/main/java/com/example/payment/infrastructure/config/ResilienceConfig.java
payment-service/src/main/java/com/example/payment/infrastructure/exception/OrderVerifyRejectedException.java
payment-service/src/main/java/com/example/payment/infrastructure/exception/OrderVerifyUnavailableException.java
payment-service/src/main/java/com/example/payment/infrastructure/http/OrderVerifyHttpClient.java
payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentJpaEntity.java
payment-service/src/main/java/com/example/payment/presentation/controller/PaymentController.java
payment-service/src/main/java/com/example/payment/presentation/dto/CreatePaymentRequest.java
payment-service/src/main/resources/application.yml
payment-service/src/main/resources/db/migration/V18__add_order_id_to_payment.sql
payment-service/src/test/java/com/example/payment/application/service/CreatePaymentCompensationTest.java
payment-service/src/test/java/com/example/payment/application/service/CreatePaymentServiceTest.java
payment-service/src/test/java/com/example/payment/infrastructure/http/OrderVerifyHttpClientTest.java
payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java
payment-service/src/test/java/com/example/payment/integration/PaymentExistsEndpointIntegrationTest.java
payment-service/src/test/resources/application.yml
```
(`PaymentControllerCreateIT.java` and `docs/error-catalog.md` also changed but are excluded from the `payment-service/` path filter naturally / are docs, respectively — both non-cancel-core.)

Existing cancel integration tests confirmed green in the same run: `CancelFlowIntegrationTest`, `CancelRaceIdempotencyIT`, `ProcessingRecoveryConcurrencyIT`.

## Migration Decision

`V18__add_order_id_to_payment.sql` adds `order_id BIGINT NOT NULL` directly (no nullable→backfill→NOT NULL two-step), per spec §7 option (a): the verification surface is Testcontainers (fresh MySQL per test run, V1–V18 applied to empty tables) and there is no production data to preserve. If a local/dev MySQL instance has pre-existing `payment` rows, the dev database must be recreated (those rows have no real `order_id` to backfill against — a genuine past-order link cannot be reconstructed for pre-Phase-2 data).

## Requirement Coverage

| Requirement | Status | Evidence |
|---|---|---|
| PLINK-01 (fail-closed) | ✅ | `OrderVerifyHttpClientTest` (503 on 5xx/timeout/CB-OPEN/non-2xx); `CreatePaymentServiceTest` (`verifyNoInteractions` on reserve+persist when verify throws) |
| PLINK-02 (order_id NOT NULL link) | ✅ | `V18__add_order_id_to_payment.sql`; `CreatePaymentReserveIntegrationTest` asserts `payment.order_id` = verified orderId in DB |
| PLINK-03 (front-of-flow verify + no side effects on failure) | ✅ | `CreatePaymentService` reordered to verify→paymentKey→reserve→persist; `InOrder` assertion in `CreatePaymentServiceTest`; `CreatePaymentReserveIntegrationTest` proves verify stub consumed before reserve stub |
| TRUST-01 (X-User-Id owner, body userId removed) | ✅ | `CreatePaymentRequest.userId` removed; `PaymentControllerCreateIT` (header→command capture, body-userId-ignored regression, missing-header short-circuit) |
| CANCEL-01 (cancel core unchanged) | ✅ | Diff gate = 0 (command above); `CancelFlowIntegrationTest`/`CancelRaceIdempotencyIT`/`ProcessingRecoveryConcurrencyIT` green |

## Self-Check: PASSED

All created files verified present on disk (`V18__add_order_id_to_payment.sql`, `OrderVerifyPort.java`, `OrderVerifyHttpClient.java`, `OrderVerifyUnavailableException.java`, `OrderVerifyRejectedException.java`, `OrderVerifyHttpClientTest.java`, `PaymentControllerCreateIT.java`); all 3 task commits (`c28beb4`, `ab00f48`, `17919c6`) verified in `git log`.
