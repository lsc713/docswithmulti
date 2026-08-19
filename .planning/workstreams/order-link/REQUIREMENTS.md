---
milestone: v1.0
milestone_name: 주문-결제 링크
workstream: order-link
last_updated: 2026-07-31
---

# Requirements — v1.0 주문(order) → 결제 라이프사이클 링크

권위 설계: `docs/superpowers/specs/2026-07-31-order-link-design.md`
목표: 결제 생성 시 orderItemId를 order-service에 존재·소유 검증하고 payment에 order_id를 강하게 링크한다. 취소 코어 불변, 주문→결제 디커플링 유지(자동 트리거 없음).

## v1.0 Requirements

### 주문 검증 API (OVER)
- [ ] **OVER-01**: order-service가 orderItemId 배열의 존재 + 단일 order 소속 + 소유(order.user_id == X-User-Id)를 검증하고 order_id를 반환한다 — `POST /v1/orders/items:verify`(내부), Header `X-User-Id`, Body `{orderItemIds[]}` → `200 {orderId}` / `404 ORDER_ITEM_NOT_FOUND` / `409 ORDER_ITEMS_MULTIPLE_ORDERS` / `403 ORDER_OWNERSHIP_MISMATCH`. 판정은 도메인 순수(`OrderItemVerifier`).

### 신뢰 신원 전환 (TRUST)
- [ ] **TRUST-01**: 결제 생성이 user_id를 `X-User-Id` 신뢰헤더에서 취득한다 — `CreatePaymentRequest.userId` 제거, PaymentController가 `@RequestHeader X-User-Id`로 매핑.
- [ ] **TRUST-02**: 주문 생성(`POST /v1/orders`)이 user_id를 `X-User-Id` 신뢰헤더에서 취득한다 — `CreateOrderRequest.userId` 제거, OrderController가 `@RequestHeader X-User-Id`로 매핑.

### 게이트웨이 · 경계 (GW)
- [ ] **GW-01**: 게이트웨이가 `POST /v1/orders`를 secured 라우트로 노출한다(JwtTrustHeaderFilter → X-User-Id 주입). `/v1/orders/items:verify`는 노출하지 않는다(내부 전용, 경로 정확 매칭 `/**` 금지). `GET /v1/orders/{id}`는 읽기 핸들러가 없어 **이번엔 descope**(라우팅 시 404 유발) — 조회 유스케이스가 생기는 슬라이스에서 추가(spec §3 "필요 시"와 일치).
- [ ] **GW-02**: order(:8081) ingress를 게이트웨이 파드로만 제한하는 NetworkPolicy를 둔다(배포 게이트) — 없으면 X-User-Id 스푸핑으로 소유 검증 우회.

### 결제–주문 검증 링크 (PLINK)
- [ ] **PLINK-01**: payment가 결제 생성 시 order-service 검증을 호출하고(`OrderVerifyHttpClient`, product reserve와 동일 **fail-closed**), order-service 장애/타임아웃/비200 시 결제를 거부한다.
- [ ] **PLINK-02**: payment에 `order_id BIGINT NOT NULL` 강한 링크 + 인덱스가 저장된다 — Flyway `V18`(데이터 유무에 따라 NOT NULL 직행 또는 nullable→backfill→NOT NULL 2단계, plan 단계 확정).
- [ ] **PLINK-03**: `CreatePaymentService`가 order 검증을 흐름 최전방(재고 예약 전)에 수행한다 — 검증 실패 시 재고 예약·persist가 발생하지 않는다(부작용 전 차단).

### 취소 코어 불변 (CANCEL)
- [ ] **CANCEL-01**: 취소 코어(멱등·TX1/TX2/TX3·스케줄러 3종·outbox)가 변경되지 않는다 — order_id는 신규 컬럼일 뿐. merge-base git diff 게이트 + 기존 취소 통합테스트 무회귀.

## Future Requirements (다음 슬라이스 — 별도 마일스톤)

- 주문 생성이 결제/예약을 자동 트리거하는 오케스트레이션(order→payment 결합, saga/상태머신).
- merchant_id 신뢰헤더화(결제 생성 body 유지 → X-Merchant-Id 전환).
- 한 결제가 여러 order에 걸치는 카디널리티(1:N, order_id를 payment_item 단위로).

## Out of Scope (명시적 제외)

- **주문→결제 자동 트리거**: 디커플링 유지. 클라이언트가 주문→결제를 순서대로 호출.
- **취소 코어 로직 일체**: order_id 컬럼 추가 외 무변경(CANCEL-01 게이트).
- **merchant_id 신뢰헤더화**: 이번 스코프는 user_id만. merchant_id는 결제 생성 body 유지.

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| OVER-01 | Phase 1 | Pending |
| TRUST-02 | Phase 1 | Pending |
| GW-01 | Phase 1 | Pending |
| GW-02 | Phase 1 | Pending |
| PLINK-01 | Phase 2 | Pending |
| PLINK-02 | Phase 2 | Pending |
| PLINK-03 | Phase 2 | Pending |
| TRUST-01 | Phase 2 | Pending |
| CANCEL-01 | Phase 2 | Pending |
