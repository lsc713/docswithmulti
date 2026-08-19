# Phase 1 Context — 주문 검증 API + 주문 생성 신뢰헤더 + 게이트웨이 경계

**Source:** `docs/superpowers/specs/2026-07-31-order-link-design.md` (권위 설계)
**Requirements:** OVER-01, TRUST-02, GW-01, GW-02
**Goal:** order-service가 orderItemId 존재·소유 검증 API를 제공하고, 주문 생성이 게이트웨이 경유 신뢰헤더(X-User-Id)로 이뤄지며, order 경계가 게이트웨이로만 접근되도록 잠긴다.

## Locked Decisions (설계 확정 — 변경 금지)

1. **검증 엔드포인트**: `POST /v1/orders/items:verify` (내부 전용, 게이트웨이 미노출). Header `X-User-Id`, Body `{ orderItemIds: [long...] }`. 판정: 모든 orderItemId 존재 && 전부 **단일 order** 소속 && 그 `order.user_id == X-User-Id`. 성공 `200 { orderId }`(해석된 단일 order_id 반환).
2. **에러 계약**: `404 ORDER_ITEM_NOT_FOUND` / `409 ORDER_ITEMS_MULTIPLE_ORDERS` / `403 ORDER_OWNERSHIP_MISMATCH`. error-catalog.md에 추가.
3. **소유 판정 로직은 도메인 순수** (예: `OrderItemVerifier` POJO — Spring/JPA 어노테이션 금지). 컨트롤러는 헤더/DTO 매핑만.
4. **주문 생성 신뢰헤더 전환**: `POST /v1/orders`가 body `userId` 대신 `@RequestHeader("X-User-Id")`로 소유자 결정. `CreateOrderRequest.userId` 제거.
5. **게이트웨이 라우트**: `POST /v1/orders`(및 `GET /v1/orders/{id}`)를 secured 라우트로 노출(JwtTrustHeaderFilter → 검증·strip·X-User-Id 주입). **`/v1/orders/items:verify`는 노출 금지** — 경로를 정확히 `/v1/orders`(및 `/v1/orders/{id}`)로 한정, `/v1/orders/**` 금지(내부 verify가 새어나가지 않도록).
6. **NetworkPolicy**: order(:8081) ingress를 게이트웨이 파드로만 제한 (payment의 domain-rules §8-3와 동형). 배포 게이트.
7. **취소 코어 불변 (CANCEL-01, cross-cutting 게이트)**: 이 페이즈는 order-service·gateway만 건드리며 payment 취소 코어(멱등·TX·스케줄러·outbox)를 건드리지 않는다. merge-base git diff + 기존 취소 통합테스트 무회귀.
8. **디커플링 유지**: 주문 생성이 결제/예약을 트리거하지 않는다 (자동 오케스트레이션 없음).

## Canonical References (미러링 대상 — 기존 패턴 따를 것)

- **게이트웨이 라우트/필터**: `api-gateway/.../config/RouteConfig.java`(userAuthSecuredRoute 패턴), `api-gateway/.../filter/JwtTrustHeaderFilter.java`(X-User-Id 주입/strip), `api-gateway/.../filter/CsrfFilter.java`(상태변경 CSRF — POST /v1/orders 대상), `api-gateway/.../config/GatewayPaths.java`(공개경로 단일 상수).
- **order-service 컨트롤러/유스케이스**: `order-service/.../presentation/controller/OrderController.java`, `application/.../CreateOrderUseCase`, `application/interfaces/*Repository`, 도메인 `Order`/`OrderItem` 엔티티. 신뢰헤더 읽기는 user-service `JwtAuthenticationFilter`가 아니라 컨트롤러 `@RequestHeader`로(order-service는 게이트웨이 뒤 내부).
- **NetworkPolicy**: `infra/k8s/networkpolicy/payment-ingress*.yaml`(payment ingress 제한 예시) 미러.
- **레이어/예외 규약**: `docs/conventions/architecture.md`.

## Claude's Discretion (구현 재량)

- verify 판정의 리포지토리 조회 방식(단건 IN 조회 vs 배치), DTO 네이밍, order_id 해석 쿼리.
- 게이트웨이 라우트 predicate 조합 방식(GatewayPaths 패턴 재사용 권장).
- 테스트 구조(단위/통합 분리).

## Scope Fence

- **In**: order-service verify 엔드포인트 + 도메인 판정, order 생성 X-User-Id 전환, 게이트웨이 order 라우트, order NetworkPolicy.
- **Out**: payment 측 검증/링크(Phase 2), payment.order_id 스키마(Phase 2), merchant_id 신뢰헤더화, 주문→결제 오케스트레이션.
