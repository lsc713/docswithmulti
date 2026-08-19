# Roadmap: SKU 재고 수명주기 (product-service) — Milestone v3.0

## Overview

빈 껍데기 product-service를 최소 재고 모듈로 세우고(reserve/release), 결제 생성 흐름에
동기 예약을 배선한 뒤, 취소 이벤트로 재고를 복원하는 수직 슬라이스. risk-management의
reserve/compensate 패턴을 미러링하며 취소 코어(멱등성·TX1/2/3·스케줄러·outbox)는 불변.
빌드순 의존을 따라 STOCK(독립 기반) → RSV(payment 예약 통합) → RST(취소 복원)로 진행.

권위 입력: `docs/superpowers/specs/2026-07-30-sku-stock-lifecycle-design.md`.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [x] **Phase 1: 재고 기반 (product-service 구축)** - 독립 스키마 + 멱등·오버셀 방지 reserve/release 엔드포인트 (completed 2026-07-30)
- [x] **Phase 2: 결제 예약 통합 (payment ↔ product)** - 결제 생성 시 동기 재고 예약 + sku/quantity 배선 + 보상 (2 plans) (completed 2026-07-31)
- [x] **Phase 3: 취소 복원 (이벤트 소비)** - payload 확장 + product 취소 consumer + orphan 복구 (completed 2026-07-31)

## Phase Details

### Phase 1: 재고 기반 (product-service 구축)
**Goal**: product-service가 독립 재고 관리 모듈로 기동하고, 멱등·오버셀 방지 reserve/release 엔드포인트를 제공한다. 후속 phase의 전제.
**Depends on**: Nothing (first phase)
**Requirements**: STOCK-01, STOCK-02, STOCK-03, STOCK-04
**Success Criteria** (what must be TRUE):
  1. product-service가 독립 MySQL + Flyway V1(`product`/`product_sku`/`product_stock`/`stock_reservation`)으로 기동한다.
  2. `POST /v1/products`로 product+SKU+초기 재고를 등록하면 재고가 seed되고 이후 reserve의 대상이 된다.
  3. `POST /v1/stock/reserve`는 `available_qty >= qty`일 때만 성공하고 부족하면 409(STOCK_INSUFFICIENT)로 거부된다 — 동시 요청에도 오버셀 없음(Testcontainers 원자 UPDATE 검증).
  4. 같은 paymentKey로 reserve/release를 재요청해도 재차감/재복원되지 않는다(paymentKey+sku UK 멱등, release는 RESERVED만 해제).
**Plans**: 2 plans
- [ ] 01-01-PLAN.md — 모듈 스캐폴드 + Flyway V1 + reserve/seed 수직 슬라이스(tracer) [STOCK-01/02/03]
- [ ] 01-02-PLAN.md — 동시 오버셀 부재 + reserve/release 멱등 + release + 다중아이템 원자 롤백 [STOCK-03/04]

### Phase 2: 결제 예약 통합 (payment ↔ product)
**Goal**: 결제 생성이 product 재고를 동기 예약한 뒤에만 커밋되고, 예약 실패·생성 TX 실패가 오버셀 없이 처리된다.
**Depends on**: Phase 1 (reserve/release 엔드포인트)
**Requirements**: RSV-01, RSV-02, RSV-03
**Success Criteria** (what must be TRUE):
  1. 결제 생성 시 payment가 product로 SKU 재고를 동기 예약(TX 앞)하고, 예약 성공 후에만 payment TX가 커밋된다.
  2. 재고 부족·product 장애(CircuitBreaker OPEN)면 결제 생성이 거부된다(fail-closed).
  3. 결제 요청과 payment_item에 sku_id·quantity가 영속화된다(Flyway V16, 신규 생성 시 필수 검증).
  4. 예약 성공 후 payment TX가 실패하면 release 보상이 호출되고, 보상 실패 시 재시도 스케줄러(compensation-retry 동형)가 정리한다.
**Plans**: 2 plans
- [ ] 02-01-PLAN.md — tracer: ProductStockPort/HttpClient + CreatePaymentService 재구조화(paymentKey→reserve→persist) + V16 배선 + reserve 200/409/CB OPEN e2e [RSV-01/02]
- [ ] 02-02-PLAN.md — persist 실패 보상(release best-effort) + V17 재시도 테이블 + StockReleaseRetryService/Scheduler [RSV-03]

### Phase 3: 취소 복원 (이벤트 소비)
**Goal**: 결제 취소가 예약된 SKU 재고를 정확히 복원하고, 유실된 예약이 복구 스케줄러로 정리된다. 취소 코어 로직은 불변.
**Depends on**: Phase 1 (product 재고), Phase 2 (skuId/quantity가 payload에 실림)
**Requirements**: RST-01, RST-02, RST-03
**Success Criteria** (what must be TRUE):
  1. `payment.cancelled` payload의 `cancelledItems[]`에 취소된 아이템의 skuId·quantity가 실린다(하위호환 필드 추가, 취소 코어 로직·TX 경계 불변 — git diff 코어 게이트 통과).
  2. product-service가 `payment.cancelled`를 신규 consumer(group=product-service)로 구독해 취소된 SKU 재고를 정확히 복원한다(부분취소 인지 — 취소된 items만 해제).
  3. 중복 `payment.cancelled` 이벤트는 cancelRequestId 멱등으로 no-op 처리된다.
  4. orphan 예약(예약 성공 후 결제 미생성된 오래된 RESERVED)이 복구 스케줄러로 payment 조회 후 release된다(processing-recovery 패턴, Redis 분산락).
**Plans**: 5 plans
- [ ] 03-01-PLAN.md — tracer: payload skuId/quantity 추가(RST-01, 코어 불변) + product 최초 Kafka consumer + release-on-cancel e2e [RST-01/02]
- [ ] 03-02-PLAN.md — processed_cancel_event V2 + cancelRequestId 멱등 게이트 + 부분취소 [RST-02]
- [ ] 03-03-PLAN.md — RetryRouter + retry consumer + DLQ 라우팅(order 복제) [RST-02]
- [ ] 03-04-PLAN.md — payment GET /v1/payments/{paymentKey}/exists 조회 엔드포인트(코어 불변) [RST-03]
- [ ] 03-05-PLAN.md — orphan 예약 복구 스케줄러(Redisson 분산락 + payment 조회 fail-safe) [RST-03]

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 재고 기반 (product-service 구축) | 0/2 | Planned | - |
| 2. 결제 예약 통합 (payment ↔ product) | 0/2 | Planned | - |
| 3. 취소 복원 (이벤트 소비) | 0/5 | Planned | - |
