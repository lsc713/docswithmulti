---
phase: 01-category-taxonomy
plan: 01
subsystem: product-service
tags: [category, taxonomy, hexagonal, flyway]
status: complete
requires: []
provides:
  - category table (V3) + Category domain/app/infra/presentation stack
  - POST /v1/categories (create root/child)
  - GET /v1/categories (nested 대→중→소 tree)
affects:
  - product-service (pure-additive; no stock/reservation change)
tech-stack:
  added: []
  patterns:
    - "생성 컬럼 parent_key=IFNULL(parent_id,0) STORED + UK로 루트 형제 이름 유일 (NULL-distinct 회피)"
    - "어댑터가 DataIntegrityViolationException → 도메인 예외(409) 번역 (pre-SELECT 없는 원자 UK)"
    - "도메인이 level 유도·깊이(>3) 거부 규칙 보유 (POJO)"
key-files:
  created:
    - product-service/src/main/resources/db/migration/V3__create_category.sql
    - product-service/src/main/java/com/example/product/domain/entity/Category.java
    - product-service/src/main/java/com/example/product/application/interfaces/CategoryRepository.java
    - product-service/src/main/java/com/example/product/application/service/CategoryService.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaEntity.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryRepositoryImpl.java
    - product-service/src/main/java/com/example/product/presentation/controller/CategoryController.java
    - product-service/src/main/java/com/example/product/presentation/dto/CreateCategoryRequest.java
    - product-service/src/main/java/com/example/product/presentation/dto/CategoryResponse.java
    - product-service/src/main/java/com/example/product/common/exception/application/CategoryDepthExceededException.java
    - product-service/src/main/java/com/example/product/common/exception/application/CategoryNameDuplicateException.java
    - product-service/src/main/java/com/example/product/common/exception/application/CategoryNotFoundException.java
    - product-service/src/test/java/com/example/product/integration/CategoryTaxonomyIntegrationTest.java
  modified:
    - product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java
    - product-service/src/main/java/com/example/product/common/exception/ErrorCode.java
decisions:
  - "level 컬럼(TINYINT)과 도메인 int 불일치 → JPA entity에 columnDefinition=\"TINYINT\"로 Hibernate validate 통과 (배포 시 신규 정보)"
  - "UK 위반을 잡으려면 flush가 try 안에서 나야 하므로 save가 아닌 saveAndFlush 사용"
  - "부모 부재는 spec §5 미지정 → 404 CATEGORY_NOT_FOUND로 결정 (plan 재량)"
metrics:
  duration: ~10m
  completed: 2026-07-31
  tasks: 3
  files: 16
requirements: [CAT-01, CAT-02, CAT-03, INV-01]
---

# Phase 1 Plan 01: Category Taxonomy Summary

카테고리 대·중·소 3단계 트리(adjacency list) 생성+조회를 기존 Product* 헥사고날 스택을 그대로 답습해 순수 추가로 구현했다. 재고/예약 경로는 한 줄도 건드리지 않았고(INV-01), 형제 이름 유일성은 생성 컬럼 `parent_key=IFNULL(parent_id,0)` + UK로 루트까지 원자 강제한다.

## Tasks

| Task | Name | Commit | Result |
| ---- | ---- | ------ | ------ |
| 1 (tracer) | 대분류 생성→트리 조회 end-to-end (전 레이어) | 514518d | PASS — Testcontainers MySQL로 관통, 트레이서 검증 후 확장 |
| 2 (tdd) | 생성 규칙 완성 (level 유도·깊이>3·형제유일·부모부재) | 43ebdda | PASS — RED 6케이스 실패 확인 후 GREEN 구현, 7/7 |
| 3 (auto) | INV-01 불변 게이트 + 무회귀 | (검증 전용, 커밋 없음) | PASS — 아래 게이트 출력 |

## INV-01 Gate Output

merge-base: `b3d0f7362cb476858ff50b9cb52758920f6f2654`

- 가드 스톡경로 파일 변경 수(StockService/ProcessCancelledStockService/PaymentCancelledStockConsumer/OrphanReservationRecoveryService/StockReservation*/ProductStock*): **0**
- migration/ 하위 V3 외 변경 수: **0** (V1/V2 불변)
- 변경 파일 전체: 신규 Category 파일 14 + `PersistenceConfig`(bean 1개 추가) + `ErrorCode`(enum 3개 추가) — 스톡 경로 무관
- 전체 `:product-service:test`: **26 tests, 0 failures, 0 errors** (기존 stock reserve/release, orphan recovery, cancel-restore idempotency + 신규 category 7)

## Requirements

- CAT-01: 루트 level 1 / 자식 parent.level+1 / 4단계 400 CATEGORY_001 — 통합테스트 검증 ✅
- CAT-02: 형제 이름 중복 409 CATEGORY_002 (루트끼리 포함) / 타 부모 동명 200 — 검증 ✅
- CAT-03: GET /v1/categories 중첩 트리 반환 — 검증 ✅
- INV-01: 스톡 경로 변경 0, 전체 스위트 green — 게이트 통과 ✅

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `-x checkstyleMain` 태스크 부재**
- **Found during:** Task 1 verify
- **Issue:** plan의 verify 커맨드가 `-x checkstyleMain`을 포함하나 이 프로젝트에 checkstyle 태스크 없음 → 빌드 실패.
- **Fix:** 해당 플래그 제외하고 `./gradlew :product-service:test --tests ...` 실행.

**2. [Rule 1 - Bug] Hibernate schema-validation: level 타입 불일치**
- **Found during:** Task 1 verify (첫 실행 실패)
- **Issue:** DDL `level TINYINT` vs entity `int` → Hibernate validate가 TINYINT≠INTEGER로 SessionFactory 빌드 거부.
- **Fix:** `CategoryJpaEntity.level`에 `@Column(columnDefinition = "TINYINT")` 지정. 도메인 int는 유지.
- **Commit:** 514518d

**3. [Rule 1 - Bug] UK 위반이 catch를 우회**
- **Found during:** Task 2 GREEN
- **Issue:** `jpa.save`는 flush를 TX 커밋 시점(try 밖)으로 미뤄 DataIntegrityViolationException이 어댑터 catch를 벗어남.
- **Fix:** `saveAndFlush` 사용해 제약 위반이 try 내부에서 발생하도록 함 → 409 매핑 정상 동작.
- **Commit:** 43ebdda

## TDD Gate Compliance

Task 2(tdd=true): RED 단계에서 신규 6케이스 전부 실패(tracer 1건은 통과) 확인 → GREEN 구현 후 7/7 통과.
RED/GREEN을 별도 커밋으로 분리하지 않고 GREEN feat 커밋(43ebdda) 하나로 묶음 — RED 테스트 코드와 구현이 동일 커밋에 포함됨(게이트 순서는 로컬 실행 로그로 준수).

## Known Stubs

None. 모든 경로가 실제 DB(Testcontainers)로 관통 검증됨.

## Threat Flags

None — threat_model의 T-01-01~04 표면과 일치, 신규 표면 없음.

## Self-Check: PASSED

- 신규 파일 14 + 수정 2 전부 디스크 존재 확인.
- 커밋 514518d, 43ebdda git log 존재 확인.
