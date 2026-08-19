# Roadmap: 주문(order) → 결제 링크 (order-link v1.0)

## Overview

지금 payment는 `orderItemId`를 검증 없이 신뢰하고 payment↔order 링크가 없다. 이 마일스톤은 결제를 상류 주문에 신뢰 가능하게 연결한다. 먼저 order-service 측에 검증 API를 세우고 주문 생성 신원을 신뢰헤더로 전환하며 게이트웨이 경계를 정리한다(Phase 1). 그 위에서 결제 생성이 상류 주문을 검증하고 `order_id`로 강하게 링크한다(Phase 2). 취소 코어(멱등·TX·스케줄러·outbox)는 한 줄도 바꾸지 않으며(CANCEL-01, 전 구간 게이트), 주문→결제는 디커플링을 유지한다(자동 트리거 없음).

권위 설계: `docs/superpowers/specs/2026-07-31-order-link-design.md`

## Phases

**Phase Numbering:**
- Integer phases (1, 2): Planned milestone work
- Decimal phases (1.1): Urgent insertions (marked with INSERTED)

- [x] **Phase 1: 주문 검증 API + 주문 생성 신뢰헤더 + 게이트웨이 경계** - order-service verify 엔드포인트 + 주문 생성 X-User-Id 전환 + 게이트웨이 order 라우트·NetworkPolicy ✓ verified
- [x] **Phase 2: 결제–주문 검증 링크** - 결제 생성이 상류 주문 검증(fail-closed) + payment.order_id 강한 링크 + 결제 신뢰헤더, 취소 코어 불변 ✓ verified

## Phase Details

### Phase 1: 주문 검증 API + 주문 생성 신뢰헤더 + 게이트웨이 경계
**Goal**: order-service가 orderItemId 존재·소유 검증 API를 제공하고, 주문 생성이 게이트웨이 경유 신뢰헤더(X-User-Id)로 이뤄지며, order 경계가 게이트웨이로만 접근되도록 잠긴다.
**Depends on**: Nothing (first phase)
**Requirements**: OVER-01, TRUST-02, GW-01, GW-02
**Success Criteria** (what must be TRUE):
  1. `POST /v1/orders/items:verify`(내부)가 X-User-Id 헤더와 `{orderItemIds[]}`를 받아, 전부 존재+단일 order 소속+`order.user_id == X-User-Id`이면 `200 {orderId}`, 아니면 각각 404/409/403을 반환한다.
  2. `POST /v1/orders`가 body `userId` 없이 `X-User-Id` 신뢰헤더로 주문 소유자를 정한다(`CreateOrderRequest.userId` 제거, 회귀 테스트 통과).
  3. 게이트웨이가 `POST /v1/orders`를 secured 라우트로 노출해 X-User-Id를 주입하고, 무토큰 요청은 401이며, `/v1/orders/items:verify`는 게이트웨이로 노출되지 않는다. (`GET /v1/orders/{id}`는 읽기 핸들러 부재로 descope — 조회 슬라이스에서 추가.)
  4. order(:8081) ingress를 게이트웨이 파드로만 제한하는 NetworkPolicy가 존재한다(배포 게이트).
  5. (CANCEL-01 게이트) 취소 코어 파일 변경 0 — merge-base git diff + 기존 취소 통합테스트 무회귀.
**Plans**: 1 plan
- [x] 01-PLAN.md — order verify 엔드포인트(OVER-01) + 주문 생성 X-User-Id(TRUST-02) + 게이트웨이 order 라우트(GW-01) + order NetworkPolicy(GW-02); tracer-first, CANCEL-01 게이트 ✓ 실행+검증 완료

### Phase 2: 결제–주문 검증 링크
**Goal**: 결제 생성이 상류 주문을 검증(fail-closed)하고 payment.order_id로 강하게 링크하며, 결제 신원을 X-User-Id 신뢰헤더에서 취득한다. 취소 코어는 불변.
**Depends on**: Phase 1 (verify 엔드포인트 필요)
**Requirements**: PLINK-01, PLINK-02, PLINK-03, TRUST-01, CANCEL-01
**Success Criteria** (what must be TRUE):
  1. `CreatePaymentService`가 흐름 최전방에서 order-service `items:verify`를 호출(X-User-Id 포워딩)하고, 검증 실패 시 재고 예약·persist가 발생하지 않는다.
  2. `OrderVerifyHttpClient`가 fail-closed다 — order-service 장애/타임아웃/비200 시 결제가 거부된다(product reserve 동일 스타일).
  3. 결제 성공 시 payment 행에 검증된 `order_id`(NOT NULL)가 저장되고, 모든 payment_item의 orderItemId가 그 order 소속이다.
  4. 결제 생성이 body `userId` 없이 `X-User-Id`로 소유자를 정한다(`CreatePaymentRequest.userId` 제거).
  5. Flyway V18로 `payment.order_id`(NOT NULL) + 인덱스가 적용되고 앱이 정상 기동한다(데이터 유무 처리 방식은 plan 확정).
  6. (CANCEL-01 게이트) 취소 코어(멱등·TX1/2/3·스케줄러·outbox) 변경 0 — merge-base git diff로 증명, 기존 취소 통합테스트 전부 통과.
**Plans**: 1 plan
- [x] 02-PLAN.md — OrderVerifyPort/HttpClient fail-closed(PLINK-01) + payment.order_id V18 NOT NULL(PLINK-02) + CreatePaymentService verify-first(PLINK-03) + PaymentController X-User-Id(TRUST-01); tracer-first, CANCEL-01 게이트 ✓ 실행+검증 완료

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 주문 검증 API + 신뢰헤더 + 경계 | 1/1 | Verified ✓ | 2026-07-31 |
| 2. 결제–주문 검증 링크 | 1/1 | Verified ✓ | 2026-08-01 |
