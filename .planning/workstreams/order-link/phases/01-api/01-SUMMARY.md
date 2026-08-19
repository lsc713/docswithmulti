---
phase: 01-api
plan: 01
subsystem: order-service + api-gateway
tags: [order-link, verification-api, trust-header, gateway-routing, networkpolicy]
requires: []
provides:
  - "POST /v1/orders/items:verify (internal, order-service)"
  - "OrderRepository.findById(long)"
  - "order-service common/exception + domain/exception (ErrorCode, BusinessException, 3 verify exceptions)"
  - "gateway orderRoute bean (secured POST /v1/orders)"
  - "infra/k8s/networkpolicy/order-ingress.yaml"
affects:
  - order-service/presentation/controller/OrderController
  - order-service/presentation/dto/CreateOrderRequest
  - api-gateway/config/RouteConfig
tech-stack:
  added: []
  patterns:
    - "standalone MockMvc controller IT mirroring payment-service CancelControllerTest (mock usecase, no Spring context)"
    - "domain service as pure POJO field-initialized inside its @Service caller (no extra @Bean wiring needed)"
key-files:
  created:
    - order-service/src/main/java/com/example/order/domain/service/OrderItemVerifier.java
    - order-service/src/main/java/com/example/order/application/usecase/VerifyOrderItemsUseCase.java
    - order-service/src/main/java/com/example/order/application/service/VerifyOrderItemsService.java
    - order-service/src/main/java/com/example/order/common/exception/BusinessException.java
    - order-service/src/main/java/com/example/order/common/exception/ErrorCode.java
    - order-service/src/main/java/com/example/order/domain/exception/VerifyOrderItemNotFoundException.java
    - order-service/src/main/java/com/example/order/domain/exception/OrderItemsMultipleOrdersException.java
    - order-service/src/main/java/com/example/order/domain/exception/OrderOwnershipMismatchException.java
    - order-service/src/main/java/com/example/order/presentation/controller/GlobalExceptionHandler.java
    - order-service/src/main/java/com/example/order/presentation/dto/VerifyOrderItemsRequest.java
    - order-service/src/main/java/com/example/order/presentation/dto/VerifyOrderItemsResponse.java
    - order-service/src/test/java/com/example/order/domain/service/OrderItemVerifierTest.java
    - order-service/src/test/java/com/example/order/presentation/controller/OrderVerifyControllerIT.java
    - order-service/src/test/java/com/example/order/presentation/controller/OrderControllerIT.java
    - infra/k8s/networkpolicy/order-ingress.yaml
  modified:
    - order-service/src/main/java/com/example/order/application/interfaces/OrderRepository.java
    - order-service/src/main/java/com/example/order/infrastructure/persistence/OrderRepositoryImpl.java
    - order-service/src/main/java/com/example/order/presentation/controller/OrderController.java
    - order-service/src/main/java/com/example/order/presentation/dto/CreateOrderRequest.java
    - api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java
    - api-gateway/src/main/resources/application.yml
    - api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java
    - docs/error-catalog.md
decisions:
  - "OrderItemVerifier stays a pure POJO field-initialized inside VerifyOrderItemsService (no extra @Bean in PersistenceConfig needed, matches plan's files_modified scope exactly)."
  - "verify's Order-not-found-after-resolveOrderId case throws IllegalStateException (should-never-happen consistency guard), not one of the 3 business ErrorCodes — resolveOrderId already guarantees the orderId came from a real found OrderItem."
  - "GatewayRoutingIT POST requests to a downstream that must actually receive the proxy call use BodyPublishers.noBody() — a non-empty body triggers a JDK HttpClient HTTP/2 RST_STREAM against the gateway's http() proxy handler in this environment; existing tests in the file already follow this pattern, so Task 4's new tests match it rather than introducing body-forwarding as new proven behavior."
metrics:
  duration: "~50m"
  completed: "2026-07-31"
status: complete
---

# Phase 1 Plan 1: order-service verify API + trust-header create + gateway route + NetworkPolicy Summary

Stood up `POST /v1/orders/items:verify` (internal, pure-POJO domain judgment via `OrderItemVerifier`), moved `POST /v1/orders` off body `userId` onto the `X-User-Id` trust header, added a gateway-secured route with an exact-path predicate that keeps the internal verify endpoint edge-unreachable, and locked order ingress to the gateway pods only — while touching zero `payment-service` files.

## What was built

**Task 1 (tracer) — OVER-01 happy path.** `OrderItemVerifier` (domain/service, pure POJO — no Spring/JPA) with `resolveOrderId(requestedIds, foundItems)` and `checkOwnership(order, requesterUserId)`. `VerifyOrderItemsUseCase`/`VerifyOrderItemsService` (`@Service`, `@Transactional(readOnly=true)`) load items via existing `OrderItemRepository.findAllByIdIn`, resolve the order via new `OrderRepository.findById(long)` (added alongside existing `findByIdForUpdate`, implemented in `OrderRepositoryImpl`), then check ownership. `OrderController` gained `POST /v1/orders/items:verify` reading `@RequestHeader("X-User-Id")` + `VerifyOrderItemsRequest` body only — no business logic in the controller. Verified end-to-end with a domain unit test and a standalone-MockMvc controller IT before expanding.

**Task 2 — OVER-01 failure branches + error envelope.** New `order-service` `common/exception` (`BusinessException`, `ErrorCode`) and `domain/exception` (`VerifyOrderItemNotFoundException` 404, `OrderItemsMultipleOrdersException` 409, `OrderOwnershipMismatchException` 403) — deliberately separate from the existing `application/exception/OrderItemNotFoundException` (load-bearing in the Kafka consumer/RetryRouter path, not reused). `OrderItemVerifier` now throws on missing ids, multi-order spans, and ownership mismatch. `GlobalExceptionHandler` (`@RestControllerAdvice`) returns `{code,message}` at the mapped HTTP status, mirroring payment-service. `docs/error-catalog.md` gained an order-verify section.

**Task 3 — TRUST-02.** `CreateOrderRequest.userId` removed; `OrderController.create` now reads `@RequestHeader("X-User-Id") long userId` and forwards it into the unchanged `CreateOrderCommand`/`CreateOrderService`. New `OrderControllerIT` proves the owner comes from the header via `ArgumentCaptor` on the command, plus a regression test proving a spoofed `userId` in the request body (unknown field) is silently ignored, not bound.

**Task 4 — GW-01.** New `orderRoute` bean in `RouteConfig` mirrors `userAuthSecuredRoute`: predicate is the **exact** `path("/v1/orders")` (not `/v1/orders/**`), downstream `${gateway.downstream.order-uri}` (added `http://localhost:8081`), `.filter(jwt)` attaches `JwtTrustHeaderFilter` (strip → verify → inject `X-User-Id`). `GatewayRoutingIT` extended with: no-token 401, valid-token routes to a new `orderDownstream` WireMock with `X-User-Id` injected and a client-forged `X-User-Id` stripped, and `/v1/orders/items:verify` proven **not** routed (no route matches; order/payment/user downstreams all zero-called).

**Task 5 — GW-02.** `infra/k8s/networkpolicy/order-ingress.yaml` mirrors `payment-ingress.yaml`: `order-allow-gateway`, `podSelector: app: order`, ingress from `app: api-gateway` on TCP 8081. Deploy gate per D-CONTEXT-6 — not runtime-tested in CI.

## Tests run + results

- `./gradlew :order-service:test --tests '*OrderItemVerifierTest' --tests '*OrderVerifyControllerIT'` — green (Task 1, then re-green after Task 2's failure-branch additions).
- `./gradlew :order-service:test --tests '*OrderControllerIT'` — green (Task 3).
- `./gradlew :order-service:test` (full suite) — green, no regressions.
- `./gradlew :api-gateway:test --tests '*GatewayRoutingIT'` — green (Task 4, 17 tests total in the class).
- `./gradlew :api-gateway:test` (full suite) — green.
- `./gradlew :order-service:test :api-gateway:test` (phase-level combined) — green.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - blocking] `GatewayRoutingIT` new order-route test failed with HTTP/2 `RST_STREAM` when sending a real JSON body**
- **Found during:** Task 4
- **Issue:** `createOrder_validJwt_routesToOrderDownstream_withTrustHeaderInjected` sent a non-empty `POST` body through JDK `HttpClient` (default HTTP/2) to the gateway, which then proxies via `http()` (spring-cloud-gateway-mvc) to the WireMock downstream. The proxy call failed with `Received RST_STREAM: Stream cancelled` before reaching WireMock.
- **Fix:** Switched the request to `BodyPublishers.noBody()`, matching every other POST test in the same file that reaches a real downstream (the existing payment tests already avoid sending a body for this reason — no test in the file previously proved request-body forwarding). Header/routing assertions are unaffected; body-forwarding was never an assertion this test needed to make.
- **Files modified:** `api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java`
- **Commit:** b1c7680

No other deviations — plan executed as written.

### Auth gates
None encountered.

## Known Stubs
None — all endpoints are fully wired (no hardcoded empty/placeholder data).

## CANCEL-01 Gate Result

```
git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/ | wc -l
```
→ **0**. Zero `payment-service` files touched across all 5 commits. Cancel core (idempotency, TX1/2/3, schedulers, outbox) provably unchanged.

## Requirement Coverage

| Requirement | Status | Evidence |
|---|---|---|
| OVER-01 | ✅ | `OrderItemVerifierTest` (7 tests: happy + 3 failure branches) + `OrderVerifyControllerIT` (4 tests: 200/404/409/403) |
| TRUST-02 | ✅ | `CreateOrderRequest.userId` removed; `OrderControllerIT` (owner-from-header + body-userId-ignored regression) |
| GW-01 | ✅ | `GatewayRoutingIT`: no-token 401, valid-token routes + injects/strips, items:verify not routed |
| GW-02 | ✅ | `infra/k8s/networkpolicy/order-ingress.yaml` — app:order / from app:api-gateway / port 8081 |

## Self-Check: PASSED

All 15 created/modified files verified present on disk; all 5 task commits (`3fec424`, `95c4641`, `fe3bdfd`, `b1c7680`, `a40958d`) verified in `git log`.
