# Roadmap: 카테고리 택소노미 + 카테고리별 조회 (product-catalog v1.0)

## Overview

product-service는 v3.0에서 재고에 필요한 최소 카탈로그만 지었고 읽기 API가 0개다. 이 마일스톤은 "카테고리(대·중·소)로 상품을 브라우징한다"는 사용자 목표를 관통하는 첫 수직 슬라이스다. 먼저 순수 추가형 카테고리 트리(생성→조회)로 얇은 end-to-end 경로를 깔고(Phase 1), 그 위에 상품을 leaf 카테고리에 연결하고 카테고리별 목록·상세로 브라우징을 완성한다(Phase 2). 기존 재고 예약·복원 경로는 한 줄도 바꾸지 않는다(INV-01, 전 구간 게이트).

권위 설계: `docs/superpowers/specs/2026-07-31-product-category-taxonomy-design.md`

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [ ] **Phase 1: 카테고리 택소노미** - 대·중·소 카테고리 트리 생성 + 조회 (얇은 end-to-end 추적 슬라이스, 순수 추가형)
- [ ] **Phase 2: 상품–카테고리 연결 + 카테고리별 브라우징** - leaf 소속 + 카테고리별 목록·상세 조회 (재고 read-only join)

## Phase Details

### Phase 1: 카테고리 택소노미
**Goal**: 관리자가 대·중·소 3단계 카테고리 트리를 구성하고 누구나 트리를 조회할 수 있다.
**Depends on**: Nothing (first phase)
**Requirements**: CAT-01, CAT-02, CAT-03
**Success Criteria** (what must be TRUE):
  1. 관리자가 `POST /v1/categories`로 대분류(parentId 없음 → level 1)와 자식(parentId 있음 → parent.level+1)을 생성할 수 있다.
  2. level 3 노드 아래(4단계) 카테고리 생성은 400으로 거부된다.
  3. 같은 부모 아래 이름이 중복된 카테고리 생성은 거부된다(형제 이름 유일, UK).
  4. 누구나 `GET /v1/categories`로 대→중→소 중첩 트리 전체를 조회할 수 있다.
  5. (INV-01 게이트) 기존 재고 예약·복원 코드와 통합테스트가 변경·회귀 없이 그대로다 — 이 페이즈는 category 신규 파일만 추가하며 product 재고 경로를 건드리지 않는다.
**Plans**: 1 plan
- [ ] 01-PLAN.md — category V3 + 도메인/앱/인프라/프레젠테이션 (생성·트리조회, level 유도·depth>3 400·형제이름 409) + INV-01 무회귀 게이트

### Phase 2: 상품–카테고리 연결 + 카테고리별 브라우징
**Goal**: 사용자가 어느 카테고리 노드(대/중/소)로든 상품을 브라우징하고 상품 상세(카테고리 경로·SKU·재고)를 조회할 수 있다.
**Depends on**: Phase 1
**Requirements**: PLINK-01, PLINK-02, BROWSE-01, BROWSE-02, INV-01
**Success Criteria** (what must be TRUE):
  1. 관리자가 `POST /v1/products`에 `categoryId`(소분류 leaf 필수)를 넣어 상품을 등록할 수 있고, leaf가 아니거나 존재하지 않는 categoryId는 400으로 거부된다.
  2. 마이그레이션(컬럼 추가 → '미분류' 대>중>소 백필 → NOT NULL+FK 확정) 후 기존 product 행이 제약 위반 없이 백필되고 앱이 정상 기동한다.
  3. 사용자가 `GET /v1/categories/{id}/products?page=&size=`로 어느 노드로든 상품 목록을 조회하며, 대/중 노드면 하위 leaf 상품이 전부 취합되고 최신순·페이징으로 반환된다.
  4. 사용자가 `GET /v1/products/{id}`로 카테고리 경로(대/중/소) + SKU 목록(skuCode, optionSummary) + `availableQty`(product_stock read-only join)를 조회할 수 있다.
  5. (INV-01 게이트) StockService·ProcessCancelledStockService·PaymentCancelledStockConsumer·OrphanReservationRecoveryService·stock_reservation/product_stock 로직 변경 0 — merge-base git diff로 증명, 기존 재고·취소복원 통합테스트 전부 통과.
**Plans**: 1 plan
- [ ] 02-PLAN.md — V4 마이그레이션(컬럼추가→미분류 백필→NOT NULL+FK) + product↔leaf 연결(PLINK-01) + 상품상세(BROWSE-02, 카테고리경로·SKU·재고 read-only join) 트레이서 → 카테고리별 목록(BROWSE-01, 재귀 CTE 취합·페이징) → INV-01 무회귀 게이트

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 카테고리 택소노미 | 0/1 | Not started | - |
| 2. 상품–카테고리 연결 + 브라우징 | 0/1 | Not started | - |
