---
phase: 01-api
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - order-service/src/main/java/com/example/order/domain/service/OrderItemVerifier.java
  - order-service/src/main/java/com/example/order/common/exception/BusinessException.java
  - order-service/src/main/java/com/example/order/common/exception/ErrorCode.java
  - order-service/src/main/java/com/example/order/domain/exception/OrderItemsMultipleOrdersException.java
  - order-service/src/main/java/com/example/order/domain/exception/OrderOwnershipMismatchException.java
  - order-service/src/main/java/com/example/order/domain/exception/VerifyOrderItemNotFoundException.java
  - order-service/src/main/java/com/example/order/application/usecase/VerifyOrderItemsUseCase.java
  - order-service/src/main/java/com/example/order/application/service/VerifyOrderItemsService.java
  - order-service/src/main/java/com/example/order/application/interfaces/OrderRepository.java
  - order-service/src/main/java/com/example/order/infrastructure/persistence/OrderRepositoryImpl.java
  - order-service/src/main/java/com/example/order/presentation/controller/OrderController.java
  - order-service/src/main/java/com/example/order/presentation/controller/GlobalExceptionHandler.java
  - order-service/src/main/java/com/example/order/presentation/dto/VerifyOrderItemsRequest.java
  - order-service/src/main/java/com/example/order/presentation/dto/VerifyOrderItemsResponse.java
  - order-service/src/main/java/com/example/order/presentation/dto/CreateOrderRequest.java
  - api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java
  - api-gateway/src/main/resources/application.yml
  - infra/k8s/networkpolicy/order-ingress.yaml
  - docs/error-catalog.md
autonomous: true
requirements: [OVER-01, TRUST-02, GW-01, GW-02]

must_haves:
  truths:
    - "POST /v1/orders/items:verify with X-User-Id and {orderItemIds[]}: all items exist + single order + order.user_id == X-User-Id → 200 {orderId} (OVER-01)"
    - "verify returns 404 ORDER_ITEM_NOT_FOUND, 409 ORDER_ITEMS_MULTIPLE_ORDERS, 403 ORDER_OWNERSHIP_MISMATCH for the respective failures (OVER-01)"
    - "POST /v1/orders reads owner from @RequestHeader X-User-Id; CreateOrderRequest has no userId field (TRUST-02)"
    - "Gateway exposes POST /v1/orders as a secured route (JwtTrustHeaderFilter injects X-User-Id); no-token → 401 (GW-01)"
    - "Gateway does NOT expose /v1/orders/items:verify — path predicate is exact /v1/orders, not /v1/orders/** (GW-01)"
    - "NetworkPolicy restricts order(:8081) ingress to api-gateway pods only (GW-02)"
    - "No payment-service file changed vs merge-base — cancel core untouched (CANCEL-01 gate)"
  artifacts:
    - order-service/src/main/java/com/example/order/domain/service/OrderItemVerifier.java
    - order-service/src/main/java/com/example/order/presentation/controller/GlobalExceptionHandler.java
    - infra/k8s/networkpolicy/order-ingress.yaml
  key_links:
    - "gateway JwtTrustHeaderFilter → X-User-Id injected → payment forwards it → order verify ownership check (order trusts header only inside the NetworkPolicy boundary)"
    - "GatewayPaths/RouteConfig exact /v1/orders predicate → keeps internal items:verify unreachable from the edge"
    - "OrderItemVerifier (pure POJO) ← controller maps header/DTO only; domain has zero Spring/JPA"
---

<objective>
Phase 1 of order-link: stand up the order-service verification API, move order creation identity onto the X-User-Id trust header, expose a precise gateway route for order creation (without leaking the internal verify path), and lock order ingress to the gateway.

Purpose: give Phase 2 (payment→order verify link) a trustworthy, gateway-guarded verify endpoint to call. Nothing here touches the payment cancel core.

Output: `POST /v1/orders/items:verify` (internal), header-driven `POST /v1/orders`, gateway order route, and an order NetworkPolicy.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/workstreams/order-link/phases/01-api/01-CONTEXT.md
@.planning/workstreams/order-link/ROADMAP.md
@.planning/workstreams/order-link/REQUIREMENTS.md
@docs/superpowers/specs/2026-07-31-order-link-design.md
@docs/conventions/architecture.md
@docs/error-catalog.md

# Canonical patterns to mirror (read before writing):
@api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java
@api-gateway/src/main/java/com/example/gateway/filter/JwtTrustHeaderFilter.java
@api-gateway/src/main/java/com/example/gateway/config/GatewayPaths.java
@api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java
@order-service/src/main/java/com/example/order/presentation/controller/OrderController.java
@order-service/src/main/java/com/example/order/application/interfaces/OrderItemRepository.java
@order-service/src/main/java/com/example/order/application/interfaces/OrderRepository.java
@order-service/src/main/java/com/example/order/domain/entity/Order.java
@order-service/src/main/java/com/example/order/domain/entity/OrderItem.java
@payment-service/src/main/java/com/example/payment/common/exception/BusinessException.java
@payment-service/src/main/java/com/example/payment/presentation/controller/GlobalExceptionHandler.java
@infra/k8s/networkpolicy/payment-ingress.yaml
</context>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: End-to-end verify happy path — one path only (OVER-01)</name>
  <files>
    order-service/src/main/java/com/example/order/domain/service/OrderItemVerifier.java,
    order-service/src/main/java/com/example/order/application/usecase/VerifyOrderItemsUseCase.java,
    order-service/src/main/java/com/example/order/application/service/VerifyOrderItemsService.java,
    order-service/src/main/java/com/example/order/application/interfaces/OrderRepository.java,
    order-service/src/main/java/com/example/order/infrastructure/persistence/OrderRepositoryImpl.java,
    order-service/src/main/java/com/example/order/presentation/controller/OrderController.java,
    order-service/src/main/java/com/example/order/presentation/dto/VerifyOrderItemsRequest.java,
    order-service/src/main/java/com/example/order/presentation/dto/VerifyOrderItemsResponse.java,
    order-service/src/test/java/com/example/order/domain/service/OrderItemVerifierTest.java,
    order-service/src/test/java/com/example/order/presentation/controller/OrderVerifyControllerIT.java
  </files>
  <behavior>
    - OrderItemVerifier.resolveOrderId(requestedIds, foundItems): all requested ids present + all share one orderId → returns that orderId (happy path only in this task).
    - OrderItemVerifier.checkOwnership(order, requesterUserId): order.userId == requesterUserId → returns normally (happy path only).
    - Controller test: POST /v1/orders/items:verify with header X-User-Id and body {orderItemIds:[...]} for a seeded owned single-order set → 200 with body {orderId}.
  </behavior>
  <action>
    Wire ONE happy path through every order-service layer. Add `POST /items:verify` to the existing OrderController (@RequestMapping("/v1/orders")), reading owner from `@RequestHeader("X-User-Id") long userId` and body DTO only — no business logic in the controller (per D-CONTEXT-3). Create VerifyOrderItemsRequest ({orderItemIds:[long]}) and VerifyOrderItemsResponse ({orderId}). Add VerifyOrderItemsUseCase interface + VerifyOrderItemsService (@Service, @Transactional(readOnly=true)) that: loads items via existing OrderItemRepository.findAllByIdIn, calls OrderItemVerifier.resolveOrderId, loads the Order via a new OrderRepository.findById(long) (add plain read alongside findByIdForUpdate; implement in OrderRepositoryImpl using OrderJpaRepository.findById → toDomain), then OrderItemVerifier.checkOwnership. OrderItemVerifier MUST be a pure POJO in domain/service — no Spring/JPA annotations, no repository refs (it receives loaded domain objects). Only implement the success path here; the three failure branches are Task 2. Do NOT touch payment-service. Verify path lives under /v1/orders/... so it is not gateway-exposed by Task 4's exact predicate.
  </action>
  <verify>
    <automated>cd /Users/juho/Documents/docswithmulti-order && ./gradlew :order-service:test --tests '*OrderItemVerifierTest' --tests '*OrderVerifyControllerIT'</automated>
  </verify>
  <done>Domain happy-path unit test + controller IT return 200 {orderId} for an owned single-order set; both green and committed.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Verify failure branches + error envelope (OVER-01)</name>
  <files>
    order-service/src/main/java/com/example/order/common/exception/BusinessException.java,
    order-service/src/main/java/com/example/order/common/exception/ErrorCode.java,
    order-service/src/main/java/com/example/order/domain/exception/VerifyOrderItemNotFoundException.java,
    order-service/src/main/java/com/example/order/domain/exception/OrderItemsMultipleOrdersException.java,
    order-service/src/main/java/com/example/order/domain/exception/OrderOwnershipMismatchException.java,
    order-service/src/main/java/com/example/order/presentation/controller/GlobalExceptionHandler.java,
    order-service/src/test/java/com/example/order/domain/service/OrderItemVerifierTest.java,
    order-service/src/test/java/com/example/order/presentation/controller/OrderVerifyControllerIT.java,
    docs/error-catalog.md
  </files>
  <behavior>
    - resolveOrderId: a requested id has no matching found item → throws VerifyOrderItemNotFoundException (missing ids listed).
    - resolveOrderId: found items span 2+ distinct orderIds → throws OrderItemsMultipleOrdersException.
    - checkOwnership: order.userId != requesterUserId → throws OrderOwnershipMismatchException.
    - Controller IT: each failure maps to 404 / 409 / 403 respectively, body {code,message}.
  </behavior>
  <action>
    Mirror payment-service's exception pattern: add order-service common/exception/BusinessException (abstract, carries ErrorCode) and ErrorCode enum with the three codes and their HTTP statuses — ORDER_ITEM_NOT_FOUND(404), ORDER_ITEMS_MULTIPLE_ORDERS(409), ORDER_OWNERSHIP_MISMATCH(403) — per D-CONTEXT-2. Create the three domain/exception classes extending BusinessException (do NOT reuse the existing application/exception/OrderItemNotFoundException — it is load-bearing in the Kafka consumer/RetryRouter path; keep verify exceptions separate). Extend OrderItemVerifier with the failure branches. Add a RestControllerAdvice GlobalExceptionHandler returning {code,message} at ErrorCode.httpStatus (same envelope shape as payment-service and the gateway 401). Add the three codes to docs/error-catalog.md under an order verify section. Keeps OVER-01 complete: 200 / 404 / 409 / 403.
  </action>
  <verify>
    <automated>cd /Users/juho/Documents/docswithmulti-order && ./gradlew :order-service:test --tests '*OrderItemVerifierTest' --tests '*OrderVerifyControllerIT'</automated>
  </verify>
  <done>Unit tests cover all three failure branches; controller IT asserts 404/409/403 with {code,message}; error-catalog updated; green.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: Order creation via X-User-Id trust header (TRUST-02)</name>
  <files>
    order-service/src/main/java/com/example/order/presentation/dto/CreateOrderRequest.java,
    order-service/src/main/java/com/example/order/presentation/controller/OrderController.java,
    order-service/src/test/java/com/example/order/presentation/controller/OrderControllerIT.java
  </files>
  <behavior>
    - POST /v1/orders with header X-User-Id and body {items:[...]} (NO userId in body) → 201, order.user_id == X-User-Id.
    - Regression: CreateOrderRequest no longer has a userId field; body userId (if sent) is ignored.
  </behavior>
  <action>
    Remove `userId` from CreateOrderRequest (per D-CONTEXT-4). Change OrderController.create to read `@RequestHeader("X-User-Id") long userId` and pass it into CreateOrderCommand (CreateOrderCommand/CreateOrderService already take userId — do not change them). Add/adjust a controller IT proving the owner comes from the header and body carries only items. order-service is behind the gateway (internal) so it reads the header directly via @RequestHeader (no JwtAuthenticationFilter — per CONTEXT canonical refs). Do NOT touch payment-service.
  </action>
  <verify>
    <automated>cd /Users/juho/Documents/docswithmulti-order && ./gradlew :order-service:test --tests '*OrderControllerIT'</automated>
  </verify>
  <done>Order create takes owner from X-User-Id; CreateOrderRequest has no userId; IT green.</done>
</task>

<task type="auto">
  <name>Task 4: Gateway secured route for POST /v1/orders, verify NOT exposed (GW-01)</name>
  <files>
    api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java,
    api-gateway/src/main/resources/application.yml,
    api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java
  </files>
  <action>
    Add an `orderRoute` @Bean mirroring userAuthSecuredRoute: predicate `path("/v1/orders")` (EXACT — per D-CONTEXT-5, NOT `/v1/orders/**`, so the internal `/v1/orders/items:verify` sub-path stays unroutable from the edge), downstream `${gateway.downstream.order-uri}`, `.filter(jwt)` so JwtTrustHeaderFilter strips client-forged headers, verifies the JWT, and injects X-User-Id. Add `order-uri: http://localhost:8081` under gateway.downstream in application.yml (IT overrides via @DynamicPropertySource like the payment/user WireMock downstreams). CSRF needs no change: CsrfFilter already enforces X-CSRF-Token on any non-PUBLIC state-changing request and exempts Bearer-only clients. Do NOT expose GET /v1/orders/{id} — no read handler exists yet (add when a read endpoint lands; exposing it now would 404). Extend GatewayRoutingIT: (a) POST /v1/orders with no token → 401; (b) POST /v1/orders with a valid token → routed to the order WireMock downstream with X-User-Id injected and client-sent X-User-Id stripped; (c) POST /v1/orders/items:verify → NOT routed to the order downstream (no matching route).
  </action>
  <verify>
    <automated>cd /Users/juho/Documents/docswithmulti-order && ./gradlew :api-gateway:test --tests '*GatewayRoutingIT'</automated>
  </verify>
  <done>Gateway routes POST /v1/orders (secured, X-User-Id injected, no-token 401); /v1/orders/items:verify not routed; IT green.</done>
</task>

<task type="auto">
  <name>Task 5: NetworkPolicy — order ingress gateway-only (GW-02)</name>
  <files>
    infra/k8s/networkpolicy/order-ingress.yaml
  </files>
  <precondition>k3s cluster uses flannel + built-in NetworkPolicy controller (same as payment-ingress.yaml assumption) — enforcement is a deploy gate, not runtime-tested in CI.</precondition>
  <action>
    Create order-ingress.yaml mirroring payment-ingress.yaml exactly, retargeted to order: metadata.name `order-allow-gateway`, podSelector `app: order`, policyTypes [Ingress], ingress from podSelector `app: api-gateway` on TCP port 8081. Carry the same rationale comment (blocks direct pod reach that would let X-User-Id be spoofed past the gateway — the trust-boundary mitigation from design §5). Per D-CONTEXT-6 this is a deploy gate.
  </action>
  <verify>
    <automated>cd /Users/juho/Documents/docswithmulti-order && grep -q 'app: order' infra/k8s/networkpolicy/order-ingress.yaml && grep -q 'app: api-gateway' infra/k8s/networkpolicy/order-ingress.yaml && grep -q '8081' infra/k8s/networkpolicy/order-ingress.yaml && echo OK</automated>
  </verify>
  <done>order-ingress.yaml restricts order:8081 ingress to api-gateway pods only.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → gateway | Untrusted client input + client-forged trust headers cross here; gateway strips/injects X-User-Id. |
| gateway → order-service | order-service trusts X-User-Id without re-verifying — only safe if network isolation holds. |
| payment → order verify | (Phase 2 caller) internal-only; items:verify must never be edge-reachable. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-01-01 | Spoofing | order-service X-User-Id trust header | critical | mitigate | NetworkPolicy (Task 5) restricts order:8081 ingress to api-gateway pods; gateway strips client-forged X-User-Id (JwtTrustHeaderFilter) before inject. |
| T-01-02 | Elevation of Privilege | /v1/orders/items:verify exposure | high | mitigate | Exact `path("/v1/orders")` predicate (Task 4, not `/**`) keeps internal verify unroutable from edge; IT asserts it is not routed. |
| T-01-03 | Information Disclosure | verify ownership check | high | mitigate | OrderItemVerifier.checkOwnership rejects order.user_id != X-User-Id → 403 ORDER_OWNERSHIP_MISMATCH (Task 2). |
| T-01-04 | Tampering | CSRF on POST /v1/orders (browser cookie flow) | medium | mitigate | Existing CsrfFilter enforces X-CSRF-Token on non-PUBLIC state-changing requests, exempts Bearer-only (no code change, verified by reuse). |
| T-01-05 | Repudiation | cancel core drift into this phase | high | mitigate | CANCEL-01 gate: zero payment-service file changes vs merge-base (verification section). |
</threat_model>

<verification>
Phase-level checks (run before marking phase complete):

- `cd /Users/juho/Documents/docswithmulti-order && ./gradlew :order-service:test :api-gateway:test` — all order + gateway tests green.
- OVER-01: verify returns 200/404/409/403 (Tasks 1-2 tests).
- TRUST-02: CreateOrderRequest has no userId; create driven by header (Task 3 test).
- GW-01: POST /v1/orders secured + no-token 401 + items:verify not routed (Task 4 IT).
- GW-02: `test -f infra/k8s/networkpolicy/order-ingress.yaml` and it targets app=order / from app=api-gateway / port 8081.
- CANCEL-01 gate (cross-cutting): `git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/ | wc -l` returns `0` — Phase 1 touches no payment-service file at all, so the cancel core (idempotency, TX1/2/3, schedulers, outbox) is provably unchanged. Existing cancel integration tests remain green.
</verification>

<success_criteria>
- [ ] `POST /v1/orders/items:verify` (X-User-Id + {orderItemIds[]}) → 200 {orderId} on exist+single-order+owned; 404 ORDER_ITEM_NOT_FOUND / 409 ORDER_ITEMS_MULTIPLE_ORDERS / 403 ORDER_OWNERSHIP_MISMATCH otherwise (OVER-01).
- [ ] `POST /v1/orders` takes owner from X-User-Id; CreateOrderRequest.userId removed; regression green (TRUST-02).
- [ ] Gateway exposes secured POST /v1/orders (X-User-Id injected), no-token → 401, `/v1/orders/items:verify` not exposed (GW-01).
- [ ] NetworkPolicy restricts order:8081 ingress to api-gateway pods (GW-02).
- [ ] CANCEL-01 gate: zero payment-service changes vs merge-base; cancel integration tests unchanged/green.
</success_criteria>

<output>
Create `.planning/workstreams/order-link/phases/01-api/01-SUMMARY.md` when done.
</output>