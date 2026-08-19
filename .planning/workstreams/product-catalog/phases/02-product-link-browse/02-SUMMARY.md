---
phase: 02-product-link-browse
plan: 02
subsystem: product-service
tags: [product, category, browse, flyway, recursive-cte, hexagonal]
status: complete

requires:
  - phase: 01-category-taxonomy
    provides: category table (V3) + Category domain/port/adapter + POST/GET /v1/categories
provides:
  - V4 migration linking product → leaf category (nullable → 미분류 backfill → NOT NULL+FK+idx)
  - Product.categoryId + ProductJpaEntity mapping
  - POST /v1/products leaf-category validation (PLINK-01)
  - GET /v1/products/{id} — 대/중/소 path + SKU + availableQty (BROWSE-02)
  - GET /v1/categories/{id}/products — recursive descendant aggregation + paging (BROWSE-01)
  - read-only ProductQuery stack (port/adapter/service/controller) separate from write side
affects:
  - product-service (browse vertical; stock write path untouched — INV-01)

tech-stack:
  added: []
  patterns:
    - "읽기/쓰기 분리: ProductQueryController/Service/Repository(GET) vs ProductController/CatalogService(POST)"
    - "MySQL 8 WITH RECURSIVE 네이티브 CTE로 하위 카테고리 id 집계 (QueryDSL 불가) + 파생 쿼리로 상품 페이징"
    - "product_stock 읽기 전용 조인은 ProductSkuJpaRepository 네이티브 인터페이스 프로젝션 — write 경로(tryReserve/restore) 불가침"
    - "V4 backfill: 세 INSERT 모두 EXISTS(product…)+NOT EXISTS 가드 → 빈 DB 무발화, 채워진 DB 1회 (empty-vs-populated 충돌 회피)"

key-files:
  created:
    - product-service/src/main/resources/db/migration/V4__link_product_category.sql
    - product-service/src/main/java/com/example/product/application/interfaces/ProductQueryRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductQueryRepositoryImpl.java
    - product-service/src/main/java/com/example/product/application/service/ProductQueryService.java
    - product-service/src/main/java/com/example/product/presentation/controller/ProductQueryController.java
    - product-service/src/main/java/com/example/product/presentation/dto/ProductDetailResponse.java
    - product-service/src/main/java/com/example/product/presentation/dto/ProductListResponse.java
    - product-service/src/main/java/com/example/product/common/exception/application/ProductCategoryInvalidException.java
    - product-service/src/main/java/com/example/product/common/exception/application/ProductNotFoundException.java
    - product-service/src/test/java/com/example/product/integration/ProductBrowseIntegrationTest.java
    - product-service/src/test/java/com/example/product/integration/ProductCategoryMigrationTest.java
    - product-service/src/test/java/com/example/product/integration/CategoryFixtures.java
  modified:
    - product-service/src/main/java/com/example/product/domain/entity/Product.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaEntity.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductSkuJpaRepository.java
    - product-service/src/main/java/com/example/product/application/service/CatalogService.java
    - product-service/src/main/java/com/example/product/presentation/dto/SeedRequest.java
    - product-service/src/main/java/com/example/product/presentation/controller/ProductController.java
    - product-service/src/main/java/com/example/product/common/exception/ErrorCode.java
    - product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java

key-decisions:
  - "PRODUCT_001(400) for missing/non-leaf/non-existent categoryId — NOT reused CATEGORY_NOT_FOUND(404) (spec §5)"
  - "카테고리 경로는 leaf→parent 도메인 findById 3회 walk (CatalogService 트리 조립 대신 상세 전용 상향 탐색)"
  - "findByCategoryIdInOrderByCreatedAtDescIdDesc — id desc 2차키로 동시각 created_at 동률 결정화 (flaky 방지)"
  - "기존 stock 테스트 seed 는 CategoryFixtures.leafId(jdbc) 픽스처로 leaf categoryId 주입 — write 경로 소스 불변 유지"

requirements-completed: [PLINK-01, PLINK-02, BROWSE-01, BROWSE-02, INV-01]

duration: 100min
completed: 2026-07-31
---

# Phase 2 Plan 02: Product ↔ Category Link + Browse Summary

**상품을 소분류(leaf) 카테고리에 묶고, 카테고리 노드 기준 재귀 집계 목록 + 상품 상세(경로+SKU+재고)를 읽기 전용 스택으로 완성 — 재고 write 경로는 한 줄도 건드리지 않았다(INV-01).**

## Performance

- **Duration:** ~100 min
- **Tasks:** 3 (tracer + expansion + gate)
- **Files:** 12 created, 10 modified (source) + 8 test files touched
- **Commits:** 04e3d5e, 36061f8, 790eb41

## Accomplishments
- V4 마이그레이션: `product.category_id` nullable 추가 → 미분류 대>중>소 idempotent backfill → NOT NULL+FK+idx. 빈 DB(모든 Testcontainers 부팅)에서는 세 가드로 아무것도 발화하지 않아 충돌 없이 통과.
- PLINK-01: POST /v1/products 가 존재하는 leaf(level 3) categoryId 요구 — 비-leaf/미존재 → 400 PRODUCT_001, 누락 → 400 INVALID_REQUEST(@NotNull).
- BROWSE-02: GET /v1/products/{id} → 대/중/소 3노드 경로 + SKU(코드·옵션·availableQty, product_stock 읽기 전용 조인). 미존재 → 404 PRODUCT_002.
- BROWSE-01: GET /v1/categories/{id}/products → MySQL 8 재귀 CTE로 하위 leaf 상품 집계, 최신순 페이징. 미존재 카테고리 → 404 CATEGORY_003, valid-but-empty → 200 빈 페이지.
- PLINK-02 populated-backfill: `ProductCategoryMigrationTest`(raw MySQLContainer + programmatic Flyway target 3→orphan→4)가 레거시 orphan 2행을 level-3 '미분류' leaf 에 안착시키고 체인이 정확히 1회 생성됨을 증명.

## Tasks

| Task | Name | Commit | Result |
| ---- | ---- | ------ | ------ |
| 1 (tracer) | product↔leaf-category link + 상세 조회 end-to-end (PLINK-01/02, BROWSE-02) | 04e3d5e | PASS — Browse 5 + Migration 1 green, 트레이서 검증 후 확장 |
| 2 (auto) | GET /v1/categories/{id}/products 재귀 집계 + 페이징 (BROWSE-01) | 36061f8 | PASS — Browse 10/10 (집계·브랜치·페이징·404·empty) |
| 3 (auto) | INV-01 불변 게이트 + 무회귀 | 790eb41 (테스트 픽스처 fix) | PASS — 아래 게이트 출력 |

## INV-01 Gate Output

merge-base: `b3d0f7362cb476858ff50b9cb52758920f6f2654`

- **GATE A** — 스톡 write 경로 **소스**(`src/main`) diff: **empty** (StockService/ProcessCancelledStockService/PaymentCancelledStock*(Consumer+RetryConsumer)/OrphanReservationRecovery*(Service+Scheduler)/StockReservation*/ProductStock* 전부 무변경). `ProductStock*` 전체 diff 등장 횟수 **0** — availableQty 조인은 ProductSkuJpaRepository/ProductQueryRepositoryImpl 에만 존재.
- **GATE B** — 이 플랜 커밋(`04e3d5e^..HEAD`) db/migration diff: `V4__link_product_category.sql` **단일 신규 파일** (+41). V1/V2/V3 변경 **0**. (merge-base 대비로는 Phase 1 의 V3 도 추가로 보이나 이는 선행 phase 산출물이며 이 플랜이 건드린 것이 아님.)
- **GATE C** — `./gradlew :product-service:test`: **BUILD SUCCESSFUL, 37 tests, 0 failures, 0 errors** (11 classes). 기존 stock reserve/release·orphan recovery·cancel-restore idempotency + 신규 browse 10 + migration 1.

주의: 플랜의 GATE A glob(`product-service/**/OrphanReservationRecovery*.java`)은 greedy 하여 통합 **테스트** 파일까지 매칭한다. 소스로 스코프(`src/main/**`)하면 empty — INV-01 이 보호하는 write 경로 로직은 불변. 매칭된 유일 파일은 아래 Deviation 의 테스트 픽스처 수정.

## Requirements

- PLINK-01: leaf categoryId 등록 / 비-leaf·미존재 → 400 PRODUCT_001 / 누락 → 400 INVALID_REQUEST — 검증 ✅
- PLINK-02: V4 add→backfill→NOT NULL+FK 적용, app boots(Hibernate validate), populated backfill 레거시 행 level-3 leaf 안착·체인 1회 — 검증 ✅
- BROWSE-01: 대/중 노드 하위 leaf 상품 재귀 집계, 최신순 페이징 / 404 / empty — 검증 ✅
- BROWSE-02: 상세 경로 + SKU + availableQty(읽기 전용 조인) / 404 PRODUCT_002 — 검증 ✅
- INV-01: 스톡 write 경로 소스 변경 0, V4 만 추가, 전체 스위트 green — 게이트 통과 ✅

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] 기존 stock 계열 테스트 seed 에 leaf categoryId 주입**
- **Found during:** Task 3 (GATE C 전체 스위트) — 16 failures.
- **Issue:** V4 가 `product.category_id` 를 NOT NULL + leaf 필수(PLINK-01)로 만들면서, 기존 7개 통합테스트(StockTracer/StockIdempotency/OrphanReservationRecovery/StockConcurrency/StockRelease 는 POST /v1/products, CancelRestoreTracer/CancelRestoreIdempotency 는 raw `INSERT INTO product(name)`)가 categoryId 없이 seed 하여 실패.
- **Fix:** 신규 테스트 유틸 `CategoryFixtures.leafId(jdbc)`(멱등 대>중>소 fixture) 추가, 각 seed body/raw insert 에 leaf categoryId 주입. 스톡 write 경로 **소스**는 무변경(GATE A src/main empty) — 픽스처 변경은 테스트 파일 한정.
- **Files modified:** CategoryFixtures.java(신규) + 7 기존 통합테스트.
- **Commit:** 790eb41

### Plan glob note (not a code deviation)
- 플랜 verify 의 GATE A glob 이 test 파일까지 잡아 `OrphanReservationRecoveryIntegrationTest.java` 가 매칭됨. 소스 스코프로 재확인 시 empty — INV-01 불변 유지. 자세한 내용 위 Gate Output 참고.

## Known Stubs

None — 모든 경로가 실 데이터로 연결됨(availableQty 실 조인, 카테고리 경로 실 walk, 재귀 집계 실 CTE). 스텁 없음.

## Self-Check: PASSED
