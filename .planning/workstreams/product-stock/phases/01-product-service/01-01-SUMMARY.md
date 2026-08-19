---
phase: 01-product-service
plan: 01
subsystem: inventory
tags: [product-service, spring-boot-4, hexagonal, flyway, mysql, testcontainers, stock-reservation, oversell-prevention]

# Dependency graph
requires: []
provides:
  - "product-service 헥사고날 스캐폴드 (Boot 4.0.5, presentation→application→domain←infrastructure)"
  - "독립 product_db + Flyway V1: product/product_sku/product_stock/stock_reservation (4테이블, 후속 phase 계약)"
  - "POST /v1/products (seed: product+SKU+초기재고), POST /v1/stock/reserve (원자 오버셀 방지)"
  - "ProductStockJpaRepository.tryReserve 원자 조건부 UPDATE (available_qty>=:qty) — payment reserve hook가 재사용"
  - "stock_reservation.uk_reservation_paymentkey_sku (멱등 키, Plan 02가 활용)"
affects: [product-stock-plan-02, phase-2-payment-reserve-integration, phase-3-cancel-restore]

# Tech tracking
tech-stack:
  added: []  # 신규 외부 의존성 없음 — 루트 build.gradle subprojects 블록 재사용
  patterns:
    - "헥사고날: domain 순수 POJO, JPA는 infrastructure/persistence의 *JpaEntity(from/toDomain)에만"
    - "원자 조건부 UPDATE로 오버셀/lost-update 방지 (risk tryDeduct 미러)"
    - "PersistenceConfig @Bean 수동 배선 + @EnableJpaRepositories (user-service 미러)"
    - "Boot 4.0.5 통합테스트: MockMvcBuilders.webAppContextSetup + Testcontainers MySQL + @DynamicPropertySource"

key-files:
  created:
    - product-service/src/main/resources/db/migration/V1__create_product_core.sql
    - product-service/src/main/resources/application.yml
    - product-service/src/main/java/com/example/product/domain/entity/ (5 POJO)
    - product-service/src/main/java/com/example/product/application/ (interfaces + CatalogService/StockService)
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ (4 aggregate)
    - product-service/src/main/java/com/example/product/presentation/ (controllers + dto + GlobalExceptionHandler)
    - product-service/src/test/java/com/example/product/integration/StockTracerIntegrationTest.java
  modified: []

key-decisions:
  - "V1 스키마 spec §4 그대로 잠금 + 모듈 내 FK 3개 (checkpoint:decision approve-spec 승인)"
  - "server.port=8084 (CLAUDE.md 모듈표 일치, Phase 2 external.product-service.url 참조)"
  - "reserve 차감-우선(decrement-first) 채택; W1 멱등 재사용(INSERT ON DUPLICATE KEY 앞세우기)은 Plan 02 이월"

patterns-established:
  - "원자 조건부 UPDATE 게이트: WHERE available_qty >= :qty, affected 0 → 부족 → 롤백/409"
  - "BusinessException → ErrorCode → HTTP status/{code,message} 매핑 (GlobalExceptionHandler)"

requirements-completed: [STOCK-01, STOCK-02, STOCK-03]

coverage:
  - id: D1
    description: "product-service가 독립 product_db + Flyway V1(4테이블)로 기동 (STOCK-01)"
    requirement: STOCK-01
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockTracerIntegrationTest.java#seedThenReserveThenOversellRejected"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST /v1/products가 product+SKU+초기재고를 seed하고 그 SKU가 reserve 대상이 됨 (STOCK-02)"
    requirement: STOCK-02
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockTracerIntegrationTest.java#seedThenReserveThenOversellRejected"
        status: pass
    human_judgment: false
  - id: D3
    description: "reserve가 available_qty>=qty에서만 원자 차감, 부족 시 409 STOCK_INSUFFICIENT + 미차감 (STOCK-03, D-P1-3 오버셀 방지)"
    requirement: STOCK-03
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockTracerIntegrationTest.java#seedThenReserveThenOversellRejected"
        status: pass
    human_judgment: false

# Metrics
duration: ~35 min (checkpoint 승인 대기 포함)
completed: 2026-07-30
status: complete
---

# Phase 01 Plan 01: product-service 스캐폴드 + 재고 예약 수직 슬라이스 Summary

**빈 껍데기 product-service를 Boot 4.0.5 헥사고날 모듈로 세우고, Flyway V1(4테이블) + seed→원자 reserve→오버셀 거부(409)를 실제 MySQL(Testcontainers)로 end-to-end 그린 처리.**

## Performance

- **Duration:** ~35 min (checkpoint:decision 승인 대기 포함)
- **Completed:** 2026-07-30
- **Tasks:** 2 (+ 1 checkpoint:decision 승인)
- **Files created:** 33 (main 30 + test 1 + config 2)

## Accomplishments
- product-service 헥사고날 스캐폴드: 설정(application.yml, port 8084, product_db 3310)·예외 플럼빙(ErrorCode/BusinessException/GlobalExceptionHandler) — user-service 최신 템플릿 미러
- Flyway V1 스키마 잠금: product/product_sku/product_stock/stock_reservation + 모듈 내 FK 3개 + uk_reservation_paymentkey_sku + idx 2개 (spec §4, 후속 phase 계약)
- 오버셀 방지 원자 reserve: `UPDATE product_stock SET available_qty=available_qty-:qty WHERE sku_id=:skuId AND available_qty>=:qty` (단일 문장, read-modify-write 갭 없음; risk tryDeduct 미러) — affected 0이면 @Transactional 롤백 + 409
- StockTracerIntegrationTest 그린: seed(재고5) → reserve qty3 (200, available 5→2) → 다른 paymentKey reserve qty5 (409 STOCK_001, available 여전히 2=미차감)

## Task Commits

1. **Task 1: 모듈 스캐폴드 + 설정 + 예외 플럼빙** - `cee7b7d` (feat)
2. **Checkpoint:decision (approve-spec)** - V1 스키마 4테이블+FK+UK 승인 (커밋 없음)
3. **Task 2: Flyway V1 + reserve/seed 수직 슬라이스 (tracer)** - `cfa5772` (feat)

_Note: build.gradle은 spec 커밋(eb8dc3e)에 이미 flyway-only로 존재 — 변경 불필요._
_Note: SUMMARY/STATE/ROADMAP는 커밋하지 않음 (.planning gitignore, 실행 제약)._

## Files Created/Modified
- `V1__create_product_core.sql` - 4테이블 + FK 3(product_sku→product, product_stock→product_sku, stock_reservation→product_sku) + uk_reservation_paymentkey_sku + idx(product_id / status,created_at)
- `domain/entity/*.java` - Product/ProductSku/ProductStock/StockReservation/ReservationStatus 순수 POJO (D-P1-2, Spring/JPA import 0건 grep 확인)
- `application/interfaces/*.java` - 포트 4개 (ProductStockRepository.tryReserve 포함)
- `application/service/CatalogService.java` - seed @Transactional
- `application/service/StockService.java` - reserve @Transactional 전-items 원자
- `infrastructure/persistence/*.java` - 4 aggregate JpaEntity+JpaRepository+RepositoryImpl; ProductStockJpaRepository.tryReserve 원자 UPDATE
- `infrastructure/config/PersistenceConfig.java` - @EnableJpaRepositories + @Bean 수동 배선 + JpaTransactionManager
- `presentation/controller/{ProductController,StockController}.java` - POST /v1/products, /v1/stock/reserve
- `presentation/dto/*.java` - SeedRequest/Response, ReserveRequest/Response (@NotBlank/@Positive/@PositiveOrZero Bean Validation)
- `presentation/GlobalExceptionHandler.java` - BusinessException→status/{code,message} + MethodArgumentNotValidException→400
- `common/exception/application/StockInsufficientException.java` - ErrorCode.STOCK_INSUFFICIENT

## Decisions Made
- **V1 스키마 approve-spec 승인:** spec §4 그대로 + 모듈 내 FK 3개. §9 "모듈 격리 non-FK"는 cross-module(payment↔product)에만 적용 — 모듈 내 관계엔 FK 부여로 참조 무결성 확보.
- **DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE** 관행 채택.
- **reserve 차감-우선 채택:** 플랜 Task 2 설계대로 tryReserve(원자 조건부 차감) 후 예약 INSERT. 오버셀 방지(#1 가드레일)는 완전 충족. W1 멱등 재사용(예약 INSERT를 ON DUPLICATE KEY로 차감보다 앞세워 loser=200 재사용)은 Plan 02로 이월 — 아래 "Deviations" 참조.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] GlobalExceptionHandler에 Bean Validation 핸들러 추가**
- **Found during:** Task 1 (예외 플럼빙)
- **Issue:** 위협모델 T-01-02(음수/무제한 qty)를 DTO Bean Validation으로 막지만, 위반 시 MethodArgumentNotValidException을 처리하는 핸들러가 없으면 500으로 샘.
- **Fix:** `@ExceptionHandler(MethodArgumentNotValidException.class)` → 첫 필드 에러를 INVALID_REQUEST(400) {code,message}로 매핑. Task 2 DTO(@Positive qty 등)가 이 핸들러에 의존.
- **Files modified:** product-service/.../presentation/GlobalExceptionHandler.java
- **Verification:** compileJava 그린; DTO 검증 경로가 400으로 매핑됨.
- **Committed in:** `cee7b7d`

---

**Total deviations:** 1 auto-fixed (1 missing critical). **Impact:** T-01-02 완화의 응답 계약을 완성. 스코프 크립 없음.

## Issues Encountered
None — 계획대로 진행. compileJava·통합테스트 모두 첫 실행 그린.

## Known Stubs / 이월 (Plan 02)
플랜이 명시적으로 Plan 02로 스코프한 항목 (stub 아님 — 계획된 확장):
- **W1 멱등 재사용:** 같은 paymentKey 재요청 시 예약 재사용(200, 중복차감 방지). 현재 reserve는 차감-우선이라 동일 paymentKey+sku 재요청은 UK 위반 발생. **이유:** `INSERT ... ON DUPLICATE KEY`의 winner/loser 판별이 Connector/J `CLIENT_FOUND_ROWS`(useAffectedRows 기본 false)에 따라 no-op 갱신을 1로 반환 → affected-rows 기반 판별이 미묘. Plan 02의 전용 동시성/멱등 회귀 테스트로 검증 필요. 반쯤 검증된 멱등 경로를 재고(money-adjacent)에 넣는 위험 회피.
- **W2 release:** `POST /v1/stock/release` 원자 조건부 상태전이(status=RESERVED→RELEASED, affected=1일 때만 복원). 플랜 Task 2 파일 목록·스코프 밖.
- **동시성 회귀:** 동시 reserve → 원자 UPDATE로 하나만 성공하는 오버셀 회귀 테스트.

## Next Phase Readiness
- **Plan 02 준비 완료:** V1 스키마·reserve 원자 게이트·통합테스트 하네스(Testcontainers) 확립. Plan 02는 멱등(W1)·release(W2)·동시성 회귀를 이 기반 위에 확장.
- **Phase 2(payment reserve 통합) 계약 확정:** `POST /v1/stock/reserve`, `sku_id/qty/payment_key` 키, port 8084. payment reserve hook가 tryReserve를 그대로 호출 가능.
- **Blocker 없음.**

## Self-Check
- [x] `V1__create_product_core.sql` 존재 + CREATE TABLE 4개 확인
- [x] domain 레이어 Spring/JPA import 0건 (grep -rl 결과 없음, D-P1-2)
- [x] `./gradlew :product-service:compileJava` + `compileTestJava` BUILD SUCCESSFUL
- [x] `StockTracerIntegrationTest` BUILD SUCCESSFUL (실 MySQL)
- [x] 커밋 `cee7b7d`, `cfa5772` 존재 (git log 확인)

## Self-Check: PASSED

---
*Phase: 01-product-service*
*Completed: 2026-07-30*
