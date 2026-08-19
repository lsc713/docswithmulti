---
phase: 01-product-service
plan: 02
subsystem: inventory
tags: [product-service, stock-reservation, idempotency, oversell-prevention, insert-on-duplicate, testcontainers, concurrency]

# Dependency graph
requires:
  - phase: 01-01
    provides: "product_stock 원자 조건부 차감(tryReserve), stock_reservation + uk_reservation_paymentkey_sku, Testcontainers 하네스"
provides:
  - "reserve 멱등: INSERT-ON-DUPLICATE 예약 게이트를 차감보다 앞세워 winner(affected=1)만 차감, loser 재사용 (W1, D-P1-4)"
  - "POST /v1/stock/release: 원자 조건부 전이(RESERVED→RELEASED) affected=1일 때만 재고 복원 (W2, D-P1-4/5)"
  - "다중아이템 reserve 전-items 원자 롤백 (D-P1-3)"
  - "동시 reserve 오버셀 부재 실측 회귀 (STOCK-03 결정적 증거)"
  - "JDBC useAffectedRows=true — winner/loser 판별 전제 확정"
affects: [phase-2-payment-reserve-integration, phase-3-cancel-restore, orphan-scheduler]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "멱등 게이트: 예약 INSERT-ON-DUPLICATE를 차감보다 앞세워 UK가 동시 요청을 winner/loser로 직렬화 (risk ensureRow 미러)"
    - "원자 조건부 상태전이: UPDATE ... WHERE status='RESERVED' affected=1이 재고 복원의 유일 트리거 (over-release 불가)"
    - "동시성 실측: RANDOM_PORT + JDK HttpClient + ExecutorService + CountDownLatch (Boot 4.0.5 MockMvc 동시성 부적합 회피, D-P1-6)"
    - "Testcontainers .withUrlParam(\"useAffectedRows\",\"true\")로 affected-rows 판별 전제 주입"

key-files:
  created:
    - product-service/src/main/java/com/example/product/presentation/dto/ReleaseRequest.java
    - product-service/src/main/java/com/example/product/presentation/dto/ReleaseResponse.java
    - product-service/src/test/java/com/example/product/integration/StockIdempotencyIntegrationTest.java
    - product-service/src/test/java/com/example/product/integration/StockReleaseIntegrationTest.java
    - product-service/src/test/java/com/example/product/integration/StockConcurrencyIntegrationTest.java
  modified:
    - product-service/src/main/java/com/example/product/application/service/StockService.java
    - product-service/src/main/java/com/example/product/application/interfaces/StockReservationRepository.java
    - product-service/src/main/java/com/example/product/application/interfaces/ProductStockRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/StockReservationJpaRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/StockReservationRepositoryImpl.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductStockJpaRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductStockRepositoryImpl.java
    - product-service/src/main/java/com/example/product/presentation/controller/StockController.java
    - product-service/src/main/resources/application.yml

key-decisions:
  - "reserve 차감-우선(Plan 01) → INSERT-우선 멱등 게이트로 전환: 예약 INSERT-ON-DUPLICATE를 tryReserve보다 앞세워 UK가 winner/loser를 직렬화. loser(affected=0)는 차감 생략·재사용 → 동시 same-key도 500 없이 200."
  - "JDBC useAffectedRows=true를 application.yml + 3개 테스트 컨테이너에 추가. reserve/release 원자 UPDATE는 항상 값을 바꾸므로 영향 없음(양 모드 동일), INSERT-ON-DUPLICATE no-op 판별에만 필요."
  - "release 동시성 회귀는 RANDOM_PORT+HttpClient로 실 HTTP 동시 발사(별 트랜잭션) — 직접 서비스 호출보다 Phase 3 실 동시 release에 근접."

patterns-established:
  - "멱등 게이트(INSERT-ON-DUPLICATE 앞세우기)와 원자 조건부 전이(WHERE status=... affected=1)로 동시성/멱등을 DB 직렬화에 위임 — 앱 락 없음"
  - "동시성 회귀 하네스: RANDOM_PORT + JDK HttpClient + ExecutorService(N) + CountDownLatch 일제 발사 + jdbc 사후 단언"

requirements-completed: [STOCK-03, STOCK-04]

coverage:
  - id: D1
    description: "재고1에 서로 다른 키 동시 20건 reserve → 정확히 1건 200·19건 409·available=0(음수 불가) — 오버셀 방지 (STOCK-03, D-P1-3, T-02-01)"
    requirement: STOCK-03
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockConcurrencyIntegrationTest.java#concurrentDistinctKeyReserveNeverOversells"
        status: pass
    human_judgment: false
  - id: D2
    description: "같은 paymentKey+sku reserve 재요청(순차·동시 burst) 멱등 — 재차감 없음·uk위반 500 없음, 예약행 1개 (STOCK-04, D-P1-4, T-02-03)"
    requirement: STOCK-04
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockIdempotencyIntegrationTest.java#reserveIsIdempotentOnSamePaymentKey"
        status: pass
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockConcurrencyIntegrationTest.java#concurrentSameKeyBurstIsIdempotent"
        status: pass
    human_judgment: false
  - id: D3
    description: "release 원자 조건부 전이(RESERVED→RELEASED) affected=1일 때만 복원 — 동시 이중 release 복원 1회, 이미 RELEASED/미존재 no-op 200 (STOCK-04, D-P1-4/5, T-02-02)"
    requirement: STOCK-04
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockReleaseIntegrationTest.java#concurrentDoubleReleaseRestoresOnce"
        status: pass
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockReleaseIntegrationTest.java#releaseIsIdempotentNoOp"
        status: pass
    human_judgment: false
  - id: D4
    description: "다중아이템 reserve 중 하나라도 부족 시 전-items 원자 롤백(부분 예약 없음) (D-P1-3)"
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/StockIdempotencyIntegrationTest.java#multiItemReserveRollsBackAtomically"
        status: pass
    human_judgment: false

# Metrics
duration: ~20 min
completed: 2026-07-30
status: complete
---

# Phase 01 Plan 02: reserve 멱등 + release 원자 + 동시 오버셀 회귀 Summary

**예약 INSERT-ON-DUPLICATE 게이트를 차감보다 앞세워 reserve를 멱등화하고, release를 원자 조건부 전이(RESERVED→RELEASED)로 잠그고, 동시 20건 부하로 오버셀 부재를 실 MySQL로 실측 고정.**

## Performance

- **Duration:** ~20 min
- **Completed:** 2026-07-30
- **Tasks:** 3 (모두 auto)
- **Files created:** 5 (dto 2 + test 3) / **modified:** 9

## Accomplishments
- **reserve 멱등 게이트(W1):** 예약 INSERT(upsertReserved)를 tryReserve 차감보다 앞세움 → uk_reservation_paymentkey_sku가 winner(affected=1)/loser(0)를 직렬화. winner만 차감, loser는 재사용. 동시 same-key burst 20건도 500/uk위반 없이 전부 200·1회만 차감.
- **release 원자 전이(W2):** `UPDATE stock_reservation SET status='RELEASED' WHERE ... AND status='RESERVED'` affected=1일 때만 `product_stock` 복원(risk tryRestore 미러). 동시 이중 release라도 DB 직렬화로 복원 1회(over-release 불가). 이미 RELEASED/미존재는 no-op 200.
- **동시 오버셀 부재(핵심):** 재고1에 서로 다른 키 20건 동시 → 정확히 1건 200·19건 409(STOCK_001)·available=0(음수 아님)·RESERVED 행 1개. STOCK-03의 결정적 증거.
- **다중아이템 전-items 원자 롤백:** skuA(qty2 충분)+skuB(qty5 부족) → 409, A 차감 롤백(available 여전히 5), pk 예약행 0개.
- **useAffectedRows=true** 확정: application.yml + 3개 테스트 컨테이너. reserve/release 원자 UPDATE엔 무영향(항상 값 변경), INSERT-ON-DUPLICATE no-op 판별에만 필요.

## Task Commits

각 task 원자 커밋:

1. **Task 1: reserve 멱등 게이트 + 다중아이템 원자 롤백** - `d15d4bd` (feat)
2. **Task 2: POST /v1/stock/release 원자 조건부 전이** - `eb35a96` (feat)
3. **Task 3: 동시 reserve 오버셀 부재 + 같은 키 멱등 burst 회귀** - `07268c2` (test)

_Note: SUMMARY는 커밋하지 않음 (.planning gitignore/커밋 금지, 실행 제약). push 없음(로컬만)._

## Files Created/Modified
- `StockReservationJpaRepository` - upsertReserved(INSERT-ON-DUPLICATE 멱등 게이트) + releaseIfReserved(조건부 전이) native 추가
- `ProductStockJpaRepository` - restore(원자 복원) native 추가 (기존 tryReserve 훼손 없음)
- `StockService` - reserve를 INSERT-우선 게이트로 재작성 + release() 추가
- `*Repository`(interface)/`*RepositoryImpl` - 포트 3개 메서드 배선
- `StockController` - POST /v1/stock/release 추가
- `dto/ReleaseRequest,ReleaseResponse` - 신규
- `application.yml` - JDBC URL에 useAffectedRows=true
- `StockIdempotencyIntegrationTest`(순차 멱등·다중아이템 롤백, MockMvc) / `StockReleaseIntegrationTest`(복원·no-op·동시 이중 release, RANDOM_PORT) / `StockConcurrencyIntegrationTest`(서로 다른 키 오버셀·같은 키 burst, RANDOM_PORT)

## Decisions Made
- **차감-우선 → INSERT-우선 멱등 게이트 전환:** Plan 01이 이월한 W1. SELECT-then-INSERT의 loser 500(uk위반)을 근본 차단하려 예약 INSERT를 차감보다 앞세워 UK를 멱등 게이트로 사용. affected=0(loser)은 차감 생략 → 중복차감/오버셀 불가.
- **useAffectedRows=true 안전성 확인:** reserve(available_qty-=qty)·release 전이·restore는 매칭 시 항상 값이 바뀌므로 affected-rows 판별이 두 모드에서 동일. no-op이 되는 것은 INSERT-ON-DUPLICATE(payment_key=payment_key)뿐 → 여기만 useAffectedRows 필요. 기존 tryReserve 회귀(StockTracer) 그린 유지로 확인.
- **release 동시성은 실 HTTP로:** RANDOM_PORT+JDK HttpClient 2스레드 동시 발사(각자 별 트랜잭션)로 Phase 3 실 동시 release에 근접한 회귀.

## Deviations from Plan

None - plan executed exactly as written.

_설계상 예정된 조정 하나: Task 2의 조건부 전이 쿼리(releaseIfReserved)를 Task 1의 StockReservationJpaRepository 편집에 함께 배치(플랜 Task 2 <files>엔 이 파일 미기재였으나 action 본문이 지시). Task 1 커밋 시점엔 미사용이었고 Task 2에서 배선됨 — 컴파일/동작 무영향._

## Issues Encountered
- Task 3 최초 작성 시 `ObjIntConsumer<String>` 콜백 파라미터 순서(=(String body, int status))를 (code, body)로 뒤집어 타입 불일치 소지 → (body, code)로 정정하고 `readCode`를 unchecked로 래핑. 커밋 전 정정, 전 테스트 그린.

## User Setup Required
None - 외부 서비스 설정 불필요 (로컬 Docker/Testcontainers만).

## Next Phase Readiness
- **Phase 2(payment reserve 통합) 준비 완료:** `POST /v1/stock/reserve`가 이제 멱등(같은 paymentKey 재시도 안전). payment reserve hook가 재시도해도 재차감 없음.
- **Phase 3(cancel restore) 준비 완료:** `POST /v1/stock/release` 원자·멱등. cancel consumer/orphan 스케줄러가 동시·중복 release해도 복원 1회 보장.
- **Blocker 없음.** `./gradlew :product-service:build` 그린(통합테스트 4종: tracer + idempotency + release + concurrency). 취소 코어·user/gateway 불변.

## Self-Check
- [x] 생성 파일 5개 존재 (dto 2 + test 3) — 아래 검증
- [x] 커밋 `d15d4bd`, `eb35a96`, `07268c2` 존재 (git log 확인)
- [x] `./gradlew :product-service:build` BUILD SUCCESSFUL (전 통합테스트 그린, 실 MySQL)
- [x] 각 task <verify> 개별 그린 (idempotency / release / concurrency)

## Self-Check: PASSED

---
*Phase: 01-product-service*
*Completed: 2026-07-30*
