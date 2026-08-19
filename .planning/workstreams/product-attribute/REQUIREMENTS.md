---
milestone: v1.0
milestone_name: 속성/변형 정규화
workstream: product-attribute
last_updated: 2026-08-03
---

# Requirements — v1.0 속성/변형 정규화 (product-service)

권위 설계: `docs/superpowers/specs/2026-08-03-product-attribute-variant-design.md`
목표: `product_sku.option_summary` 자유문자열을 **구조화 속성/변형 모델**로 정규화. 전역 속성 사전 + 변형/서술 역할 구분.
불변: 재고 예약·복원·취소 경로 변경 0. facet 검색은 다음 슬라이스(범위 밖).

## v1.0 Requirements

### 전역 속성 사전 (ATTR)
- [ ] **ATTR-01**: 관리자가 전역 속성을 생성한다 — `POST /v1/attributes {name}`, 이름 전역 유일.
- [ ] **ATTR-02**: 관리자가 속성 값을 생성한다 — `POST /v1/attributes/{id}/values {value}`, (attribute, value) 유일.
- [ ] **ATTR-03**: 전역 속성·값 목록을 조회한다 — `GET /v1/attributes` (등록 참조용).

### 상품 속성 선언 + 변형 조합 (VAR)
- [ ] **VAR-01**: 상품 등록 시 쓰는 속성을 변형/서술 역할과 함께 선언한다 — `product_attribute(is_variant)`. 변형·서술 각각 다속성 허용.
- [ ] **VAR-02**: SKU를 변형 속성값의 **완전·유일 조합**으로 정의한다 — 선언된 모든 변형 속성 커버(빠지면 400), 상품 내 조합 유일(중복 409), attribute_value가 그 상품의 변형 속성 소속(아니면 400).
- [ ] **VAR-03**: 상품에 서술 속성값을 다속성·다값으로 붙인다 — `product_descriptive_value`, 서술 속성 소속 검증(아니면 400), 완전성·유일성 강제 안 함.

### 구조화 조회 (VQUERY)
- [ ] **VQUERY-01**: 상품 상세에 `variantOptions`(변형 속성별 가능한 값) + 각 SKU의 `variant` 조합(속성→값)을 노출한다 — `GET /v1/products/{id}`.
- [ ] **VQUERY-02**: 상품 상세에 `specs`(서술 속성·값, 다값) 노출. 기존 `optionSummary`·가격·이미지·카테고리 경로 필드는 하위호환으로 병행(제거·변경 없음).

### 불변 (INV)
- [ ] **INV-01**: 재고 예약·복원·취소 경로(StockService·reserve/release·`payment.cancelled` consumer·`product_stock`/`stock_reservation`/`cancel_restore_dlq`) 변경 0. 마이그레이션 V8만 추가(V1~V7 무변경). 기존 카테고리 브라우징·상세·이미지·가격 조회 무회귀. git diff 게이트 + 통합테스트.

## Future / Out of Scope (다음 또는 별도)

- **facet 검색 / 자유텍스트 검색**: 속성 위 색상·소재 필터 — 다음 슬라이스.
- **자동 변형 매트릭스 생성**: 색상×사이즈 카테시안 자동 SKU 생성 — 이번은 명시 조합만.
- **변형 선택 UI(프론트)** · 다값 서술의 프론트 표현.
- **attribute/value 수정·삭제** · `option_summary` 자동 파싱 백필.

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| ATTR-01 | Phase 1 | Pending |
| ATTR-02 | Phase 1 | Pending |
| ATTR-03 | Phase 1 | Pending |
| VAR-01 | Phase 1 | Pending |
| VAR-02 | Phase 1 | Pending |
| VQUERY-01 | Phase 1 | Pending |
| INV-01 | Phase 1 | Pending |
| VAR-03 | Phase 2 | Pending |
| VQUERY-02 | Phase 2 | Pending |

**Coverage: 9/9 requirements mapped ✓** — no orphans, no duplicates. (INV-01은 Phase 1에 앵커, Phase 2에서 재검증.)
