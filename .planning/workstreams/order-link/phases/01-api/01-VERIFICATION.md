---
phase: 01-api
verified: 2026-07-31T19:30:00+09:00
status: passed
score: 5/5 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 1: 주문 검증 API + 주문 생성 신뢰헤더 + 게이트웨이 경계 Verification Report

**Phase Goal:** order-service가 orderItemId 존재·소유 검증 API를 제공하고, 주문 생성이 게이트웨이 경유 신뢰헤더(X-User-Id)로 이뤄지며, order 경계가 게이트웨이로만 접근되도록 잠긴다.
**Verified:** 2026-07-31T19:30:00+09:00
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `POST /v1/orders/items:verify` returns 200 `{orderId}` on valid owned single-order input; 404/409/403 on the three failure cases | ✓ VERIFIED | `OrderController.verify()` (order-service/.../presentation/controller/OrderController.java:52-59) delegates to `VerifyOrderItemsUseCase`; `OrderItemVerifier.resolveOrderId`/`checkOwnership` (domain/service/OrderItemVerifier.java:21-40) implement all 4 branches. `OrderVerifyControllerIT` (4 tests) and `OrderItemVerifierTest` (5 tests) assert 200/404/409/403 and the domain branch outcomes respectively — both re-run independently, green (`tests="4" ... failures="0"`, `tests="5" ... failures="0"`). |
| 2 | `OrderItemVerifier` is a pure domain POJO (no Spring/JPA imports) | ✓ VERIFIED | `OrderItemVerifier.java` imports only `domain.entity.*`, `domain.exception.*`, `java.util.*` — no `org.springframework`/`jakarta.persistence` import. Same holds for the 3 domain/exception classes (`VerifyOrderItemNotFoundException`, `OrderItemsMultipleOrdersException`, `OrderOwnershipMismatchException`) — grep for Spring/JPA/annotation tokens across these files returned zero hits. `VerifyOrderItemsService` field-initializes it directly (`new OrderItemVerifier()`), confirming no Spring wiring was smuggled in via a `@Bean`. |
| 3 | `POST /v1/orders` uses X-User-Id (`CreateOrderRequest.userId` removed); regression covered | ✓ VERIFIED | `CreateOrderRequest.java` has only an `items` field, no `userId`. `OrderController.create()` reads `@RequestHeader("X-User-Id") long userId` (OrderController.java:29-33). `OrderControllerIT` (2 tests, green) proves owner comes from the header via `ArgumentCaptor`, plus a dedicated regression test sending a spoofed `userId` in the JSON body and asserting it is ignored (unknown-field deserialization). |
| 4 | Gateway exposes secured `POST /v1/orders` (no-token 401, X-User-Id injected) and `/v1/orders/items:verify` is NOT routed (exact path match) | ✓ VERIFIED | `RouteConfig.orderRoute()` uses `path("/v1/orders")` (exact, not `/v1/orders/**`) + `.filter(jwt)` (RouteConfig.java:83-92). `GatewayRoutingIT`: `createOrder_noToken_returns401_downstreamNotCalled` (401 + `TOKEN_MISSING` + 0 downstream calls), `createOrder_validJwt_routesToOrderDownstream_withTrustHeaderInjected` (201, `X-User-Id: 42` injected, forged header stripped), `verifyPath_notRoutedToOrderDownstream` (status != 200, order/payment/user downstreams all 0-called for the verify path). Full class re-run independently: 14 tests, 0 failures, 0 errors. |
| 5 | order NetworkPolicy restricts :8081 ingress to the gateway pod | ✓ VERIFIED | `infra/k8s/networkpolicy/order-ingress.yaml`: `podSelector: app: order`, `policyTypes: [Ingress]`, `ingress.from.podSelector: app: api-gateway`, `port: 8081`. Structural check re-run independently (`grep` for all 3 tokens) — OK. Deploy-time gate, not runtime-tested in CI (consistent with `payment-ingress.yaml` precedent and PLAN's documented precondition). |
| 6 | CANCEL-01: zero payment-service diff vs merge-base; cancel core untouched | ✓ VERIFIED | Independently re-ran `git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/ \| wc -l` → `0`. Full changed-file list vs merge-base confirmed only order-service, api-gateway, infra/k8s/networkpolicy, docs, and `.planning` paths touched — no `payment-service/` path present. Since payment-service has a zero-line diff, existing cancel integration tests are provably unaffected by this phase (no code path they exercise changed). |

**Score:** 6/6 truths verified (0 present-behavior-unverified) — note: roadmap lists 5 success criteria; the domain-POJO purity check (SC embedded in criterion 1's wording and PLAN's must_haves) is broken out as truth #2 above for clarity, giving 6 rows mapping to the 5 roadmap SCs + PLAN must_haves.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `order-service/.../domain/service/OrderItemVerifier.java` | Pure POJO domain judgment, 4 branches | ✓ VERIFIED | Exists, substantive (40 lines of real logic), wired (called from `VerifyOrderItemsService`), no Spring/JPA imports |
| `order-service/.../presentation/controller/GlobalExceptionHandler.java` | `{code,message}` envelope for BusinessException/validation/unexpected | ✓ VERIFIED | `@RestControllerAdvice` with 3 handlers mapping to `ErrorCode.httpStatus`; wired via Spring component scan, exercised by `OrderVerifyControllerIT`'s 404/409/403 assertions |
| `infra/k8s/networkpolicy/order-ingress.yaml` | order:8081 ingress restricted to api-gateway pods | ✓ VERIFIED | Present, correct selectors/port, mirrors `payment-ingress.yaml` pattern |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| gateway `JwtTrustHeaderFilter` | order-service `OrderController` | X-User-Id header injected by gateway, read via `@RequestHeader` in controller | ✓ WIRED | `GatewayRoutingIT.createOrder_validJwt_routesToOrderDownstream_withTrustHeaderInjected` proves injection + forged-header stripping at the gateway edge; `OrderControllerIT` proves the header is what `OrderController` binds to (not body) |
| `RouteConfig.orderRoute` exact `/v1/orders` predicate | internal `/v1/orders/items:verify` | route non-match (no `/**`) | ✓ WIRED | `GatewayRoutingIT.verifyPath_notRoutedToOrderDownstream` — status != 200, zero downstream calls across all 3 WireMock downstreams |
| `OrderItemVerifier` (pure POJO) | `OrderController` | controller maps header/DTO only, `VerifyOrderItemsService` orchestrates | ✓ WIRED | Controller has zero business logic (verified by reading `OrderController.verify()` — single delegation line); `VerifyOrderItemsService` is the only caller of `OrderItemVerifier` |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| order-service verify + create tests pass | `./gradlew :order-service:test --tests '*OrderItemVerifierTest' --tests '*OrderVerifyControllerIT' --tests '*OrderControllerIT'` | BUILD SUCCESSFUL, exit 0 | ✓ PASS |
| Full order-service suite (no regressions) | `./gradlew :order-service:test` | BUILD SUCCESSFUL | ✓ PASS |
| Gateway routing tests pass | `./gradlew :api-gateway:test --tests '*GatewayRoutingIT'` | BUILD SUCCESSFUL, 14 tests/0 failures/0 errors (test-results XML) | ✓ PASS |
| Full api-gateway suite (no regressions) | `./gradlew :api-gateway:test` | BUILD SUCCESSFUL | ✓ PASS |
| CANCEL-01 gate | `git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/ \| wc -l` | `0` | ✓ PASS |
| NetworkPolicy structural check | `grep -q 'app: order' && grep -q 'app: api-gateway' && grep -q '8081'` | `OK` | ✓ PASS |

Note: SUMMARY.md claimed "17 tests total in the class" for `GatewayRoutingIT`; the actual class contains 14 `@Test` methods and the test-results XML confirms 14 ran. Minor SUMMARY inaccuracy — does not affect goal achievement (all 14 pass, including the 3 order-link-specific ones).

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|--------------|--------|----------|
| OVER-01 | 01-PLAN.md | order-service verify API, domain-pure judgment | ✓ SATISFIED | `OrderItemVerifier` + `VerifyOrderItemsService` + `OrderController.verify()`, 9 tests across unit/IT |
| TRUST-02 | 01-PLAN.md | Order creation via X-User-Id | ✓ SATISFIED | `CreateOrderRequest.userId` removed, `OrderController.create()` reads header, 2 IT tests |
| GW-01 | 01-PLAN.md | Gateway secured `/v1/orders`, verify path unexposed | ✓ SATISFIED | `RouteConfig.orderRoute()` exact predicate, 3 dedicated `GatewayRoutingIT` tests |
| GW-02 | 01-PLAN.md | order NetworkPolicy | ✓ SATISFIED | `order-ingress.yaml` present and correctly scoped (deploy gate, not CI-tested — expected) |

No orphaned requirements found for Phase 1 (REQUIREMENTS.md traceability table maps exactly OVER-01/TRUST-02/GW-01/GW-02 to Phase 1, all four claimed in PLAN frontmatter `requirements:`).

### Anti-Patterns Found

None. Grepped all phase-changed files (order-service, api-gateway, infra) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER|placeholder|coming soon|not yet implemented|not available` — zero matches.

### Human Verification Required

None. All 5 success criteria are structurally and behaviorally verifiable via code inspection + automated tests; the NetworkPolicy is explicitly a deploy-time gate per the plan's own precondition (not expected to be CI-verified, and its YAML structure was independently checked).

### Gaps Summary

No gaps. All 5 roadmap Success Criteria for Phase 1 hold:
1. Verify endpoint 200/404/409/403 contract — implemented and tested, domain judgment is a pure POJO.
2. Order creation via X-User-Id, `CreateOrderRequest.userId` removed, regression tested.
3. Gateway exposes secured `POST /v1/orders`, injects X-User-Id, rejects no-token (401), and does not route `/v1/orders/items:verify` (exact-path predicate, test-proven).
4. order-ingress NetworkPolicy restricts :8081 to api-gateway pods.
5. CANCEL-01 gate: zero payment-service file diff vs merge-base, independently re-verified.

All tests re-run independently by the verifier (not just trusted from SUMMARY.md) and confirmed green via test-results XML, not just console output.

---

_Verified: 2026-07-31T19:30:00+09:00_
_Verifier: Claude (gsd-verifier)_
