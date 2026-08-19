---
phase: 01-product-service
verified: 2026-07-30T22:32:00Z
status: passed
score: 7/7 must-haves verified
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: none
  note: initial verification
---

# Phase 1: 재고 기반 (product-service 구축) Verification Report

**Phase Goal:** product-service가 독립 재고 관리 모듈로 기동 + 멱등·오버셀 방지 reserve/release 엔드포인트 제공. 후속 phase(payment 예약, 취소 복원)의 전제.
**Verified:** 2026-07-30T22:32:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

Goal-backward, code+behavior verified against commits `cee7b7d cfa5772 d15d4bd eb35a96 07268c2` on `feat/sku-stock-lifecycle`. Full suite `./gradlew :product-service:test --rerun-tasks` was run once against real MySQL (Testcontainers) + real Docker: **BUILD SUCCESSFUL, 9/9 test methods across 4 classes, 0 failures/errors.** Every behavior-dependent truth (oversell absence, idempotency, atomic rollback, over-release absence) has a passing behavioral test — not presence-only.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | STOCK-01: 독립 스키마(Flyway V1) 4테이블 + 모듈 내 FK + UK + idx로 기동 | ✓ VERIFIED | `V1__create_product_core.sql`: product/product_sku/product_stock/stock_reservation, FK 3(fk_product_sku_product, fk_product_stock_sku, fk_stock_reservation_sku), `uk_reservation_paymentkey_sku(payment_key,sku_id)`, `idx_product_sku_product`, `idx_reservation_status_created`. spec §4와 정확히 일치. `ddl-auto: validate` + Flyway로 실 MySQL 부팅(모든 Boot 테스트 컨텍스트 기동 성공), port 8084, product_db. |
| 2 | STOCK-02: POST /v1/products seed → product+SKU+초기재고, 이후 reserve 대상 | ✓ VERIFIED | `CatalogService.seed()` @Transactional로 product→sku→stock 저장, `ProductController` POST /v1/products. 모든 통합테스트가 seed로 SKU 생성 후 그 skuId로 reserve — `StockTracerIntegrationTest` seed(5)→reserve(3)→available=2 그린. |
| 3 | STOCK-03: reserve 원자 조건부 UPDATE(available>=qty), 부족 409, 오버셀 없음, 다중아이템 원자 롤백 | ✓ VERIFIED (behavioral) | `ProductStockJpaRepository.tryReserve`: 단일 문장 `UPDATE ... SET available_qty=available_qty-:qty WHERE sku_id=:skuId AND available_qty>=:qty` (read-modify-write 갭 없음). **동시성 증거**: `concurrentDistinctKeyReserveNeverOversells` — 재고1, 서로 다른 키 20건 동시(RANDOM_PORT+JDK HttpClient+CountDownLatch 일제발사) → 정확히 1×200, 19×409(STOCK_001), available=0(음수 아님), RESERVED 행 1개. **다중아이템**: `multiItemReserveRollsBackAtomically` — A(qty2)+B(qty5 부족) → 409, A 차감 롤백(=5), pk 예약행 0. 모두 PASS. |
| 4 | STOCK-04: reserve/release paymentKey 멱등 | ✓ VERIFIED (behavioral) | reserve: INSERT-ON-DUPLICATE 게이트(`upsertReserved`, `ON DUPLICATE KEY UPDATE payment_key=payment_key`) + `useAffectedRows=true`로 winner(1)/loser(0) 판별 → loser는 차감 생략. `reserveIsIdempotentOnSamePaymentKey`(순차 2회, available 불변=7, 예약행 1) + `concurrentSameKeyBurstIsIdempotent`(같은키 20 동시 → 20×200, available=9, 500/uk위반 0, 예약행 1). release: `releaseIfReserved`(WHERE status='RESERVED') affected=1만 `restore` → `concurrentDoubleReleaseRestoresOnce`(동시 이중 release → 둘 다 200, 4를 1회만 복원=10, 14 아님) + no-op 케이스 2건. 모두 PASS. |
| 5 | 레이어 규약: domain 순수 POJO(Spring/JPA/jakarta import 0) | ✓ VERIFIED | `grep -rE "import (org.springframework|jakarta.persistence|javax.persistence|jakarta.validation)" domain/` → 0건. domain 엔티티는 java.* 만 import. JPA는 infrastructure/persistence의 *JpaEntity(from/toDomain)에만. |
| 6 | 격리: product-service 외 변경 0(취소 코어 4서비스·user/gateway 불변) | ✓ VERIFIED | `git diff --name-only cee7b7d~1 07268c2` → product-service/ + docs/superpowers/(spec) 외 파일 없음. 43 files, +1634, 삭제 0. payment/order/merchant-limit/risk/user/gateway 무변경. |
| 7 | 신규 외부 의존성 없음 | ✓ VERIFIED | `product-service/build.gradle`은 flyway 플러그인 설정만(루트 subprojects 블록 재사용). tech-stack.added=[]. |

**Score:** 7/7 truths verified (0 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `db/migration/V1__create_product_core.sql` | 4테이블+FK+UK+idx | ✓ VERIFIED | spec §4 일치, Flyway validate 통과(부팅) |
| `application/service/StockService.java` | reserve/release 멱등·원자 | ✓ VERIFIED | INSERT-우선 게이트 + 조건부 전이, 단일 @Transactional 전-items 원자 |
| `infrastructure/persistence/ProductStockJpaRepository.java` | tryReserve/restore 원자 UPDATE | ✓ VERIFIED | 조건부 차감(available>=qty) + 복원 |
| `infrastructure/persistence/StockReservationJpaRepository.java` | upsertReserved/releaseIfReserved | ✓ VERIFIED | ON DUPLICATE KEY 게이트 + 조건부 상태전이 |
| `application/service/CatalogService.java` | seed | ✓ VERIFIED | product+sku+stock 한 TX |
| `presentation/controller/StockController.java` | POST reserve/release | ✓ VERIFIED | @Valid, /v1/stock/{reserve,release} |
| `presentation/GlobalExceptionHandler.java` | BusinessException→status, Bean Validation→400 | ✓ VERIFIED | STOCK_INSUFFICIENT→409, MethodArgumentNotValid→400 |
| 통합테스트 4클래스 | Testcontainers 회귀 | ✓ VERIFIED | tracer/idempotency/release/concurrency 9메서드 그린 |

### Key Link Verification

| From | To | Via | Status |
|------|----|----|--------|
| StockController | StockService | 생성자 주입 + reserve/release 위임 | ✓ WIRED |
| StockService | ProductStockRepository/StockReservationRepository | 포트 주입, upsertReserved→tryReserve 순서 | ✓ WIRED |
| RepositoryImpl | JpaRepository | @EnableJpaRepositories + PersistenceConfig @Bean 수동 배선 | ✓ WIRED |
| StockInsufficientException | 409 STOCK_001 | ErrorCode.STOCK_INSUFFICIENT(409) → GlobalExceptionHandler | ✓ WIRED (테스트가 body code=STOCK_001·status 409 단언) |

### Behavioral Spot-Checks

Full suite run once (constraint honored). Per-method results parsed from JUnit XML:

| Behavior | Result | Status |
|----------|--------|--------|
| 동시 20 distinct-key reserve → 1×200/19×409/available=0 | 1 PASS | ✓ PASS |
| 동시 20 same-key burst → 20×200/available=9/예약행1/500 없음 | 1 PASS | ✓ PASS |
| 순차 same-key reserve 멱등 | 1 PASS | ✓ PASS |
| 다중아이템 부족 → 전-items 원자 롤백 | 1 PASS | ✓ PASS |
| 동시 이중 release → 복원 1회(over-release 불가) | 1 PASS | ✓ PASS |
| release no-op(이미 RELEASED / 미존재) | 2 PASS | ✓ PASS |
| tracer seed→reserve→오버셀 거부 | 1 PASS | ✓ PASS |
| **합계 9/9, 0 failures/0 errors, BUILD SUCCESSFUL** | | ✓ PASS |

### Boot 테스트 현실성

- 동시성/release 회귀는 `RANDOM_PORT + JDK HttpClient + ExecutorService(N) + CountDownLatch` 일제 발사 — 실 HTTP·별 트랜잭션(Boot 4.0.5 MockMvc 동시성 부적합 회피). 오버셀·멱등 검증에 적합.
- 순차 멱등/원자 롤백은 `webAppContextSetup(MockMvc)` — 상태 단언은 JdbcTemplate 직접 조회. 적합.
- 4클래스 모두 `MySQLContainer("mysql:8.0") + .withUrlParam("useAffectedRows","true")` — INSERT-ON-DUPLICATE winner/loser 판별 전제를 컨테이너에도 주입(테스트/운영 일관).

### Requirements Coverage

| Requirement | Description | Status | Evidence |
|-------------|-------------|--------|----------|
| STOCK-01 | 독립 스키마 Flyway V1 4테이블 | ✓ SATISFIED | V1 SQL + 부팅 |
| STOCK-02 | POST /v1/products seed | ✓ SATISFIED | CatalogService + tracer |
| STOCK-03 | reserve 원자·409·오버셀 방지 | ✓ SATISFIED | concurrency + tracer + multi-item rollback |
| STOCK-04 | reserve/release paymentKey 멱등 | ✓ SATISFIED | idempotency + release + same-key burst |

### Anti-Patterns Found

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| (없음) | debt marker(TODO/FIXME/XXX/TBD/HACK/placeholder) 실 매치 0 (grep 히트는 `toDomain` 오탐) | — | 없음 |
| (없음) | 스텁/빈 구현/하드코딩 렌더 데이터 | — | 없음 |

### Info-level 관찰 (비차단)

- **409 응답 바디 형태**: spec §5는 `409 STOCK_INSUFFICIENT { skuId, available }`를 제안하나 구현은 공통 `{ code:"STOCK_001", message }`. 요구사항(STOCK-03의 "부족하면 409로 거부")은 완전 충족이며 Phase 2 payment reserve hook의 소비 계약(200 reserved / 409 거부)에도 충분. skuId/available 상세 바디가 Phase 2에서 필요하면 확장 여지 — 지금은 gap 아님.

### Human Verification Required

None — 모든 truth가 통과하는 behavioral 통합테스트로 커버됨.

### Gaps Summary

없음. Phase 목표(독립 재고 모듈 기동 + 멱등·오버셀 방지 reserve/release) 코드·동시성 실측 양면에서 달성. 스키마는 spec §4와 정확히 일치하고 후속 phase 계약(reserve/release 엔드포인트·V1 스키마)이 확정됨. 격리 완전(취소 코어·user/gateway 불변), 신규 외부 의존성 없음, domain 순수 POJO 규약 준수.

---

_Verified: 2026-07-30T22:32:00Z_
_Verifier: Claude (gsd-verifier)_
