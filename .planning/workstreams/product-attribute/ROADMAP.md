---
milestone: v1.0
milestone_name: 속성/변형 정규화
workstream: product-attribute
granularity: standard
last_updated: 2026-08-03
---

# Roadmap — v1.0 속성/변형 정규화 (product-service)

권위 설계: `docs/superpowers/specs/2026-08-03-product-attribute-variant-design.md`
요구사항: `.planning/workstreams/product-attribute/REQUIREMENTS.md`

목표: `product_sku.option_summary` 자유문자열을 **구조화 속성/변형 모델**로 정규화. 전역 속성 사전 위에서 변형(SKU 정의)/서술(상품 태그) 역할을 구분. 재고 예약·복원·취소 경로 변경 0.

전략: **tracer-first 수직 슬라이스**. Phase 1이 전역 사전→변형 선언→SKU 조합→상세 노출을 얇게 관통하며 불변식(INV-01)을 증명한다. Phase 2가 서술 속성 레이어를 얹는다.

## Phases

- [ ] **Phase 1: 전역 속성 사전 + 변형 조합 CORE** - 전역 사전 세우고 SKU를 변형값 조합으로 정의·노출, 재고·취소 경로 무변경 증명
- [ ] **Phase 2: 서술 속성 + specs 노출** - 상품에 서술 속성값을 다속성·다값으로 붙이고 상세 specs로 하위호환 노출

## Phase Details

### Phase 1: 전역 속성 사전 + 변형 조합 CORE
**Goal**: 관리자가 전역 속성 사전을 세우고, 상품이 변형 속성으로 SKU를 완전·유일 조합으로 정의하며, 상세가 그 조합을 구조화해 노출한다 — Flyway V8만 추가, 재고·취소 경로 변경 0.
**Depends on**: Nothing (first phase)
**Requirements**: ATTR-01, ATTR-02, ATTR-03, VAR-01, VAR-02, VQUERY-01, INV-01
**Success Criteria** (what must be TRUE):
  1. 관리자가 `POST /v1/attributes`로 전역 속성을 생성하고(이름 중복 시 거부), `POST /v1/attributes/{id}/values`로 값을 추가하고(같은 속성 내 값 중복 시 거부), `GET /v1/attributes`로 전체 사전을 조회할 수 있다.
  2. 상품 등록 시 변형 속성을 선언하고 각 SKU를 변형값 조합으로 정의할 수 있다 — 선언된 변형 속성이 하나라도 빠지면 400, 상품 내 조합이 중복이면 409, 값이 그 상품의 변형 속성 소속이 아니면 400.
  3. `GET /v1/products/{id}`가 `variantOptions`(변형 속성별 가능한 값 집합)와 각 SKU의 `variant` 조합(속성→값)을 노출한다.
  4. Flyway V8이 5개 테이블을 추가하고 V1~V7은 무변경이며, 재고 예약·복원·취소 복원 통합테스트가 무회귀로 통과한다 — git diff(merge-base) 게이트가 StockService·reserve/release·`payment.cancelled` consumer·`product_stock`/`stock_reservation`/`cancel_restore_dlq` 변경 0을 증명한다.
**Plans**: 2 plans
- [ ] 01-01-PLAN.md — 전역 사전 + 변형 조합 CORE (tracer→사전 하드닝→변형 검증 3분기, ATTR/VAR/VQUERY)
- [ ] 01-02-PLAN.md — INV-01 불변식 게이트 (재고·취소 diff 0 + V8 only + 전체 무회귀)

### Phase 2: 서술 속성 + specs 노출
**Goal**: 상품에 서술 속성값을 다속성·다값으로 붙이고 상세에 `specs`로 노출한다 — 기존 응답 필드는 하위호환으로 병행 유지.
**Depends on**: Phase 1
**Requirements**: VAR-03, VQUERY-02
**Success Criteria** (what must be TRUE):
  1. 상품 등록 시 `descriptiveValueIds`로 서술 속성값을 다속성·다값으로 붙일 수 있고, 값이 서술 속성 소속이 아니면 400 (완전성·유일성은 강제하지 않음).
  2. `GET /v1/products/{id}`가 `specs`(서술 속성별 값 배열, 한 속성 다값 포함)를 정확히 노출한다.
  3. 기존 `optionSummary`·가격·이미지·카테고리 경로 필드가 제거·변경 없이 병행 유지된다(하위호환 무회귀). Phase 1의 INV-01 재고·취소 불변식이 재검증된다.
**Plans**: TBD

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 전역 속성 사전 + 변형 조합 CORE | 0/2 | Not started | - |
| 2. 서술 속성 + specs 노출 | 0/? | Not started | - |
