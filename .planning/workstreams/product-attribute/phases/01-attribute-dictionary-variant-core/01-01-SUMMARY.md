---
phase: 01-attribute-dictionary-variant-core
plan: 01
subsystem: product-service
tags: [attribute, variant, catalog, hexagonal, flyway]
requires:
  - product-catalog (category/product/product_sku/product_stock, Flyway V1~V7)
provides:
  - 전역 속성 사전 (attribute/attribute_value) + POST/GET /v1/attributes
  - 상품 변형 선언(product_attribute.is_variant) + SKU 변형 조합(sku_attribute_value)
  - GET /v1/products/{id} variantOptions + per-SKU variant 구조화 노출
affects:
  - product-service CatalogService.seed (변형 배선·검증 확장, 하위호환 오버로드)
  - product-service ProductQueryService.detail / ProductDetailResponse (필드 추가)
tech-stack:
  added: []
  patterns:
    - "@IdClass 복합키 JPA 엔티티(product_attribute PK(product_id,attribute_id) / sku_attribute_value PK(sku_id,attribute_value_id))"
    - "saveAndFlush + DataIntegrityViolationException catch → 도메인 예외 번역(UK 원자 강제, TOCTOU 없음)"
    - "앱 레벨 정렬 value-id 집합 비교로 조합 유일성 검증(combo_hash 컬럼 없음)"
    - "네이티브 인터페이스 프로젝션(VariantRow) 상세 조립 조인"
key-files:
  created:
    - product-service/src/main/resources/db/migration/V8__create_attribute_variant.sql
    - product-service/src/main/java/com/example/product/domain/entity/Attribute.java
    - product-service/src/main/java/com/example/product/domain/entity/AttributeValue.java
    - product-service/src/main/java/com/example/product/domain/entity/ProductAttribute.java
    - product-service/src/main/java/com/example/product/application/interfaces/AttributeRepository.java
    - product-service/src/main/java/com/example/product/application/interfaces/ProductVariantRepository.java
    - product-service/src/main/java/com/example/product/application/service/AttributeService.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/AttributeJpaEntity.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/AttributeValueJpaEntity.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/AttributeRepositoryImpl.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductAttributeJpaEntity.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/SkuAttributeValueJpaEntity.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductVariantRepositoryImpl.java
    - product-service/src/main/java/com/example/product/presentation/controller/AttributeController.java
    - product-service/src/main/java/com/example/product/presentation/dto/AttributeDictionaryResponse.java
    - product-service/src/test/java/com/example/product/integration/AttributeVariantTracerIntegrationTest.java
    - product-service/src/test/java/com/example/product/integration/AttributeDictionaryIntegrationTest.java
    - product-service/src/test/java/com/example/product/integration/ProductVariantIntegrationTest.java
  modified:
    - product-service/src/main/java/com/example/product/application/service/CatalogService.java
    - product-service/src/main/java/com/example/product/application/service/ProductQueryService.java
    - product-service/src/main/java/com/example/product/common/exception/ErrorCode.java
    - product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java
    - product-service/src/main/java/com/example/product/presentation/controller/ProductController.java
    - product-service/src/main/java/com/example/product/presentation/dto/SeedRequest.java
    - product-service/src/main/java/com/example/product/presentation/dto/ProductDetailResponse.java
    - product-service/src/test/java/com/example/product/presentation/controller/ProductQueryControllerTest.java
decisions:
  - "조합 유일성은 앱 레벨 정렬 집합 비교만(combo_hash 컬럼·UK 없음) — seed 가 유일 SKU 생성 경로라 TOCTOU 없음"
  - "완전성 검증은 attributeId 그룹핑 후 (그룹 집합==선언 변형 집합) AND (모든 그룹 크기==1) — 집합 동등성만으론 [화이트,블랙] 통과하므로 그룹 크기까지"
  - "V8 5 테이블 한 번에 생성(product_descriptive_value 포함)하되 서술 경로는 Phase 2"
  - "SeedRequest/CatalogService.seed 하위호환 오버로드 유지 — 기존 무변형 등록 테스트 무회귀"
metrics:
  duration_min: 32
  completed: 2026-08-03
  tasks: 3
  files: 46
  tests_total: 74
  tests_failed: 0
status: complete
---

# Phase 01 Plan 01: 속성 사전 · 변형 코어 Summary

전역 속성 사전(attribute/attribute_value) 위에서 상품이 변형 속성을 선언(product_attribute.is_variant)하고 SKU를 변형값의 완전·유일 조합(sku_attribute_value)으로 정의하며, 상세 조회가 variantOptions + per-SKU variant로 구조화 노출한다. Flyway V8만 추가, 기존 재고·취소 경로 무변경.

## Tasks

| Task | Name | Type | Commit | 결과 |
|------|------|------|--------|------|
| 1 | 사전→선언→SKU조합→상세 얇은 tracer (변형 1개) | tracer | 0fb5755 | AttributeVariantTracerIntegrationTest 통과 |
| 2 | 전역 사전 하드닝 — 이름/값 중복·없는 속성 거부 + GET | auto | 6dbc12a | AttributeDictionaryIntegrationTest 통과 (5 케이스) |
| 3 | 변형 조합 검증 3분기 + 2속성 완전조합 상세 | auto | a4a51c1 | ProductVariantIntegrationTest 통과 (5 시나리오) |

## What was built

- **V8 마이그레이션**: attribute · attribute_value · product_attribute(is_variant) · sku_attribute_value · product_descriptive_value 5 테이블. V1~V7 무변경. product_descriptive_value는 스키마만(Phase 2 서술).
- **전역 사전**: Attribute/AttributeValue 도메인 POJO + AttributeRepository 포트 + JPA 어댑터(단일 IDENTITY 키). AttributeService(createAttribute/addValue/getDictionary). AttributeController POST /v1/attributes · POST /{id}/values → {id}, GET /v1/attributes → 사전 전체(페이징 없음). UK 위반은 saveAndFlush + DataIntegrityViolationException catch로 409 번역, 없는 속성에 값 추가는 404.
- **변형 배선**: ProductAttribute 도메인 + ProductVariantRepository 포트 + @IdClass 복합키 엔티티(product_attribute / sku_attribute_value). CatalogService.seed에 attributes(선언) + SkuSeed.variantValueIds 확장(하위호환 오버로드 유지). 저장 순서(product → product_attribute → 각 SKU+stock+sku_attribute_value)로 재고 경로 무변경.
- **변형 검증(persist 전 fail-fast)**: 선언 속성 존재 → 소속(VARIANT_003 400) → 완전성(VARIANT_001 400, 그룹 크기>1 검출) → 유일성(VARIANT_002 409, 정렬 집합 비교). 무변형 등록은 스킵 가드로 하위호환.
- **상세 노출**: ProductQueryService.detail이 findVariantRows(네이티브 조인, ORDER BY sku.id, attribute.id)로 variantOptions(속성별 값 집합, LinkedHashMap) + per-SKU variant(속성명→값) 조립. ProductDetailResponse에 variantOptions + Sku.variant 추가, 기존 optionSummary/price/availableQty/category/images 병존(하위호환).

## Requirements covered

- ATTR-01/02/03: 속성 생성(이름 중복 409) · 값 생성((attr,value) 중복 409, 없는 속성 404) · GET 사전 조회.
- VAR-01/02: 변형 속성 선언 + SKU 완전·유일 조합(빠짐/그룹>1 → 400, 중복 → 409, 소속 아님 → 400).
- VQUERY-01: variantOptions + per-SKU variant 노출, 기존 필드 병존.

## Deviations from Plan

**1. [Rule 1 - Bug] 기존 ProductQueryControllerTest ProductDetail 생성자 arity 갱신**
- **Found during:** Task 1 (ProductDetail record에 variantOptions 필드 추가)
- **Issue:** ProductDetail record 확장이 기존 단위테스트 2개 생성자 호출을 깨뜨림.
- **Fix:** 두 호출에 `List.of()`(빈 variantOptions) 추가. 계획 files 목록 밖이지만 내 변경이 직접 유발한 회귀라 수정.
- **Files modified:** product-service/src/test/java/.../ProductQueryControllerTest.java
- **Commit:** 0fb5755

**2. [Rule 3 - Blocking] 클라우드 동기화 중복 클래스 파일 제거**
- **Found during:** Task 3 전체 스위트 실행
- **Issue:** `build/`에 `* [0-9].class` 중복(예: `ProductQueryControllerTest 2.class`)이 "wrong name" 컴파일 오류 유발(계획이 경고한 SQL 중복과 동종 클라우드-싱크 산물).
- **Fix:** `find product-service/build -name '* [0-9].class' -delete` (생성 산물만, src·추적 파일 무변경). 이후 74 tests green.
- **Commit:** 없음(빌드 산물 정리, 소스 무변경).

**3. [계획 내 최적화] ErrorCode VARIANT_001/002/003를 Task 2 커밋에서 선반영**
- ErrorCode.java가 Task 2·3 양쪽 files에 포함되어 enum 편집을 1회로 합침. VARIANT 예외 클래스·사용은 Task 3. 동작 영향 없음(Task 3 전까지 미사용).

## Design decisions honored

- 조합 유일성 = 앱 레벨 정렬 value-id 집합 비교(combo_hash 없음). seed가 유일 SKU 생성 경로.
- 인라인 속성 생성 불허(id 참조만). GET 사전 페이징 없음. Phase 1은 변형만 검증/노출(서술은 Phase 2).

## INV-01 (재고·취소 경로 불변)

3 커밋 전 파일 목록 전수 확인 — StockService/reserve/release/consumer/product_stock/stock_reservation/cancel_restore_dlq/V1~V7 무변경. 재고 저장 로직·순서 CatalogService에서 그대로. 최종 diff-0 게이트는 02-01(wave 2).

## Known Stubs

없음. product_descriptive_value 테이블은 의도적 미사용(Phase 2 서술 경로) — 설계 결정이며 스키마만 생성.

## Threat surface

계획 threat_model(T-01-01~03) 이내. 신규 트러스트 경계·엔드포인트 없음(속성/상품 등록 입력은 소속·완전·유일·UK 검증으로 mitigate). 신규 의존성 없음.

## Verification

- AttributeVariantTracerIntegrationTest · AttributeDictionaryIntegrationTest · ProductVariantIntegrationTest 각 통과.
- `./gradlew :product-service:test` 전체 74 tests, 0 failures.
- Docker/Testcontainers MySQL 8.0 사용.

## Self-Check: PASSED
