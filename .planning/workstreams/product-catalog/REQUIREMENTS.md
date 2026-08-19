---
milestone: v1.0
milestone_name: 카테고리 택소노미
workstream: product-catalog
last_updated: 2026-07-31
---

# Requirements — v1.0 카테고리 택소노미 + 카테고리별 조회

권위 설계: `docs/superpowers/specs/2026-07-31-product-category-taxonomy-design.md`
목표: "카테고리(대·중·소)로 상품을 브라우징한다"를 관통하는 첫 수직 슬라이스.

## v1.0 Requirements

### 카테고리 택소노미 (CAT)
- [ ] **CAT-01**: 관리자가 카테고리 노드를 생성한다 — `POST /v1/categories {parentId?, name}`. parentId 없으면 대분류(level 1), 있으면 `parent.level+1`. level>3 생성은 400 거부.
- [ ] **CAT-02**: 같은 부모 아래 이름이 중복된 카테고리 생성은 거부된다 (형제간 이름 유일, UK).
- [ ] **CAT-03**: 사용자가 카테고리 트리를 조회한다 — `GET /v1/categories`, 대→중→소 중첩 반환.

### 상품–카테고리 연결 (PLINK)
- [ ] **PLINK-01**: 상품 등록 시 소분류(leaf, level 3) 카테고리에 소속시킨다 — `POST /v1/products`에 `categoryId` 필수. leaf 아님/미존재 시 400.
- [ ] **PLINK-02**: 기존 product 행이 `category_id NOT NULL` 제약을 위반 없이 백필된다 — 마이그레이션 V3(컬럼 추가 → '미분류' 대>중>소 백필 → NOT NULL+FK 확정).

### 카테고리별 조회 (BROWSE)
- [ ] **BROWSE-01**: 사용자가 카테고리(대/중/소 어느 노드로든)별 상품 목록을 조회한다 — `GET /v1/categories/{id}/products?page=&size=`. 대/중 노드면 하위 leaf 상품 전부 취합, 최신순, 페이징.
- [ ] **BROWSE-02**: 사용자가 상품 상세를 조회한다 — `GET /v1/products/{id}`. 카테고리 경로(대/중/소) + SKU 목록(skuCode, optionSummary) + `availableQty`(product_stock read-only join).

### 재고 경로 불변 (INV)
- [ ] **INV-01**: 재고 예약·복원 경로가 변경되지 않는다 — StockService / ProcessCancelledStockService / PaymentCancelledStockConsumer / OrphanReservationRecoveryService / stock_reservation·product_stock 로직 변경 0. git diff(merge-base) 게이트 + 기존 재고·취소복원 통합테스트 무회귀.

## Future Requirements (다음 슬라이스 — 각 별도 마일스톤)

- 속성/변형 시스템: `option_summary` 자유문자열 → 색상·사이즈 attribute/variant 정규화
- 이미지: product/sku 이미지 메타(URL·순서·대표)
- 자유텍스트 검색(키워드)
- 카테고리 수정/삭제/이동
- 상품–카테고리 M:N (한 상품 다중 카테고리 노출)

## Out of Scope (명시적 제외)

- **정렬 옵션**: 목록은 최신순 고정. 가격/이름 정렬은 조회 슬라이스로 미룸.
- **카테고리 CRUD 전체**: 이번엔 생성+조회만. 수정/삭제/이동 제외(트리 구성엔 충분).
- **깊이 4단계+**: level 1~3 고정, level>3 생성 거부.

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| CAT-01 | Phase 1 | Pending |
| CAT-02 | Phase 1 | Pending |
| CAT-03 | Phase 1 | Pending |
| PLINK-01 | Phase 2 | Pending |
| PLINK-02 | Phase 2 | Pending |
| BROWSE-01 | Phase 2 | Pending |
| BROWSE-02 | Phase 2 | Pending |
| INV-01 | Phase 2 (cross-cutting — 게이트로 Phase 1에도 적용) | Pending |

**Coverage:** 8/8 v1.0 requirements mapped ✓ (no orphans, no duplicates)

INV-01은 재고 경로 불변을 강제하는 cross-cutting 게이트다. product-service를 건드리는 모든 페이즈의 success criterion으로 등장하며(Phase 1·2), 실제 무회귀 검증의 무게는 재고 read-only join과 product 테이블 마이그레이션이 발생하는 Phase 2에 있어 primary 소유를 Phase 2로 둔다.
