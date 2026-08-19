---
phase: 03-cancel-restore
plan: 05
subsystem: product-stock
tags: [spring-boot, scheduler, redisson, resttemplate, jpa, testcontainers, orphan-recovery]

requires:
  - phase: 03-cancel-restore
    provides: "GET /v1/payments/{paymentKey}/exists (03-04) — orphan 커밋여부 조회 계약"
  - phase: 01
    provides: "StockService.release 원자 조건부 전이(멱등) + stock_reservation.idx_reservation_status_created"
provides:
  - "OrphanReservationRecoveryService.recoverAll — stale RESERVED 스캔 → payment exists 조회 → orphan release (RST-03)"
  - "OrphanReservationRecoveryScheduler — Redisson 분산락 60s 스케줄러 (payment ProcessingRecoveryScheduler 동형)"
  - "PaymentQueryPort/PaymentQueryHttpClient — product→payment exists 조회 (plain RestTemplate, best-effort)"
  - "StockReservationRepository.findStaleReserved — idx_reservation_status_created 배치 스캔"
affects: []

tech-stack:
  added:
    - "org.redisson:redisson-spring-boot-starter:4.3.1 (payment 좌표 정렬 — 신규 패키지 아님)"
  patterns:
    - "best-effort 배경 조회: plain RestTemplate + fail-safe(조회 예외는 skip, release 금지)"
    - "payment 스케줄러 패턴 미러: Redisson tryLock(0,55,SECONDS) + isHeldByCurrentThread finally unlock"
    - "redisson 의존 추가 시 test autoconfigure.exclude + 컴포넌트스캔 mock RedissonClient로 실 Redis 없이 IT 기동"

key-files:
  created:
    - product-service/src/main/java/com/example/product/application/interfaces/PaymentQueryPort.java
    - product-service/src/main/java/com/example/product/infrastructure/http/PaymentQueryHttpClient.java
    - product-service/src/main/java/com/example/product/infrastructure/config/HttpClientConfig.java
    - product-service/src/main/java/com/example/product/application/service/OrphanReservationRecoveryService.java
    - product-service/src/main/java/com/example/product/infrastructure/scheduler/OrphanReservationRecoveryScheduler.java
    - product-service/src/test/java/com/example/product/integration/OrphanReservationRecoveryIntegrationTest.java
    - product-service/src/test/resources/application.yml
    - product-service/src/test/java/com/example/product/config/TestRedissonConfig.java
  modified:
    - product-service/build.gradle
    - product-service/src/main/resources/application.yml
    - product-service/src/main/java/com/example/product/application/interfaces/StockReservationRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/StockReservationJpaRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/StockReservationRepositoryImpl.java
    - product-service/src/main/java/com/example/product/ProductServiceApplication.java

key-decisions:
  - "RedissonConfig.java 미생성 — redisson-spring-boot-starter 자동설정(spring.data.redis)으로 충분(payment도 별도 설정 파일 없음). plan이 허용한 생략 옵션 채택."
  - "PaymentQueryHttpClient는 CircuitBreaker 없는 plain RestTemplate — best-effort 배경 스케줄러. 조회 실패는 예외 전파 → 해당 건 skip(fail-safe). false 강등 금지(미존재 오인 → 조기 release 방지)."
  - "test에서 RedissonAutoConfigurationV4/RedisAutoConfiguration 배제 + 컴포넌트스캔 @Configuration mock RedissonClient — 6개 기존 @SpringBootTest 무변경으로 무회귀(payment @MockitoBean 패턴 대비 per-test 수정 0)."

requirements-completed: [RST-03]

coverage:
  - id: D1
    description: "오래된(5분+) RESERVED를 payment exists 조회해 커밋 payment 없으면(exists=false) release + 재고복원, 있으면(exists=true) RESERVED 유지 (RST-03, D-P3-3)"
    requirement: RST-03
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/OrphanReservationRecoveryIntegrationTest.java#orphanReleasedAndStockRestored"
        status: pass
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/OrphanReservationRecoveryIntegrationTest.java#committedPaymentKeptReserved"
        status: pass
    human_judgment: false
  - id: D2
    description: "fail-safe: payment 조회 예외 시 해당 건 skip — release 안 함(조기 복원 방지) (T-03-09)"
    requirement: RST-03
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/OrphanReservationRecoveryIntegrationTest.java#queryExceptionSkipsRelease"
        status: pass
    human_judgment: false
  - id: D3
    description: "threshold(5분) 경계 — 최근 예약은 exists=false여도 스캔 대상 아님(미복원)"
    requirement: RST-03
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/OrphanReservationRecoveryIntegrationTest.java#recentReservationNotScanned"
        status: pass
    human_judgment: false
  - id: D4
    description: "Redisson 분산락 단일 실행 + release 멱등(원자 전이)으로 이중 실행/over-release 없음 (T-03-10, D-P3-4)"
    requirement: RST-03
    verification:
      - kind: code
        ref: "OrphanReservationRecoveryScheduler tryLock(0,55,SECONDS) + StockService.release releaseIfReserved 조건부 전이(affected=1만 복원)"
        status: pass
    human_judgment: true
    rationale: "락+멱등 조합은 ProcessingRecoveryScheduler/StockService 기검증 패턴 미러 — 다중 인스턴스 동시성은 통합테스트로 재현하지 않고 코드 동형성으로 담보."
  - id: D5
    description: "redisson 의존 추가가 기존 product IT 무회귀 — 전체 :product-service:test 그린"
    verification:
      - kind: integration
        ref: "./gradlew :product-service:test (19 tests, 0 failures/errors/skipped)"
        status: pass
    human_judgment: false

duration: 13min
completed: 2026-07-31
status: complete
---

# Phase 3 Plan 5: Orphan 예약 복구 스케줄러 Summary

**reserve 성공 후 결제 미커밋으로 유실된 orphan 예약을 정리하는 backstop — 오래된 RESERVED를 payment exists 조회(권위)해 exists=false일 때만 release. Redisson 분산락 + fail-safe(조회 실패는 절대 release 안 함).**

## Performance
- **Duration:** 13 min
- **Started:** 2026-07-31T03:23:10Z
- **Completed:** 2026-07-31T03:36:58Z
- **Tasks:** 3
- **Files:** 14 (8 created, 6 modified)

## Accomplishments
- `OrphanReservationRecoveryService.recoverAll`: `findStaleReserved(now-5m)` 스캔 → paymentKey별 그룹 → `PaymentQueryPort.exists` 조회 → **exists=false만** `StockService.release`(멱등). exists=true/조회예외는 skip.
- `OrphanReservationRecoveryScheduler`: `@Scheduled(fixedDelay=60s)` + Redisson `tryLock(0,55,SECONDS)` (payment `ProcessingRecoveryScheduler` 동형), `ProductServiceApplication` `@EnableScheduling`.
- `PaymentQueryPort` + `PaymentQueryHttpClient`: plain RestTemplate로 `GET /v1/payments/{paymentKey}/exists`(03-04) 호출. CircuitBreaker 없음 — 조회 실패는 예외 전파로 skip(fail-safe).
- `StockReservationRepository.findStaleReserved`: `idx_reservation_status_created` 활용 `status='RESERVED' AND created_at < threshold` LIMIT 500 배치 스캔.
- 통합테스트 4케이스(orphan release / 정상 유지 / threshold 경계 / 조회예외 skip) 그린 + 기존 6개 IT 무회귀(전체 19 tests green).

## Task Commits
1. **Task 1: Redisson + payment 조회 클라이언트 + orphan 스캔 쿼리** — `2a7fc0a` (feat)
2. **Task 2: orphan 복구 서비스 + Redisson 스케줄러 + 테스트 인프라** — `30af72e` (feat)
3. **Task 3: orphan 복구 통합테스트 4케이스** — `29dcfc5` (test)

## Decisions Made
- **RedissonConfig 생략** — redisson-spring-boot-starter 자동설정(`spring.data.redis`)으로 `RedissonClient` 빈 충분(payment도 별도 설정 파일 없음). plan Task1-3이 명시적으로 허용한 옵션.
- **plain RestTemplate + fail-safe** — orphan 조회는 best-effort 배경 작업. 조회 실패를 `false`로 강등하지 않고 예외 전파해 skip → 조기 release(재고 오류) 원천 차단. release는 오직 exists=false 확정 시만.
- **test redisson autoconfig 배제 + mock RedissonClient** — redisson 의존 추가로 인한 실 Redis 연결 요구를, `src/test/resources/application.yml` autoconfigure.exclude + 컴포넌트스캔 `@Configuration` mock 빈으로 흡수. 기존 6개 통합테스트 **per-test 수정 0**으로 무회귀(payment의 per-test `@MockitoBean RedissonClient` 대비 lazy).

## Deviations from Plan

### [Rule 3 - Blocking] redisson 의존 추가에 따른 테스트 인프라 신설
- **Found during:** Task 2 (스케줄러 빈이 RedissonClient 요구 + starter가 기동 시 실 Redis 연결)
- **Issue:** redisson-spring-boot-starter를 build.gradle에 추가하면 `RedissonAutoConfigurationV4`가 기동 시 localhost:6379로 즉시 연결 시도 → 실 Redis 없는 기존 6개 `@SpringBootTest`(StockTracer 등) 컨텍스트 로드 실패. 스케줄러 `@Component`도 `RedissonClient` 빈을 강제.
- **Fix:** `src/test/resources/application.yml` 신설(main 설정 복제 + `spring.autoconfigure.exclude`로 Redisson/Redis autoconfig 배제) + `TestRedissonConfig`(@Configuration, 컴포넌트스캔되는 mock `RedissonClient` 빈, `getLock().tryLock()→false` 스텁). payment 패턴(test yml 배제)을 미러하되 per-test `@MockitoBean` 대신 전역 mock 빈으로 기존 테스트 무수정.
- **Files:** product-service/src/test/resources/application.yml, product-service/src/test/java/com/example/product/config/TestRedissonConfig.java
- **Verification:** `./gradlew :product-service:test` 19 tests, 0 failures — 기존 6개 IT 무회귀.
- **Commit:** 30af72e

**Total deviations:** 1 auto-fixed (Rule 3 blocking — 테스트 인프라). **Impact:** 프로덕션 코드 계약 무변경. 테스트 전용 파일 2개 추가로 redisson 의존 도입을 무회귀로 흡수. plan `files_modified`에 없던 파일이나 plan Task1이 redisson 도입을 지시했고 그 필연적 test-side 결과.

## Threat Mitigations Applied
- **T-03-09 (premature release, high):** threshold(5분) + payment 권위 exists 조회 + fail-safe(조회 예외 skip, release 금지). `queryExceptionSkipsRelease` + `recentReservationNotScanned` 케이스로 검증.
- **T-03-10 (이중 release, medium):** Redisson 분산락(단일 실행) + `StockService.release` 멱등 원자 전이(`releaseIfReserved` affected=1만 복원). 이중 안전.
- **T-03-SC (redisson install, low/accept):** payment 기사용 좌표(4.3.1) 정렬 — 신규 패키지 아님.

## Issues Encountered
None.

## User Setup Required
None — Redis는 프로덕션 스케줄러 락에만 필요(테스트는 mock). `external.payment-service.url`/`spring.data.redis`는 env 오버라이드 가능한 기본값 등록.

## Next Phase Readiness
- RST-03(orphan 복구) 완료. reserve→생성실패보상(Phase 2)마저 유실된 경우의 backstop 확보.
- 취소 코어·payment(exists 외)·order 완전 무변경 — 이 plan은 product-service만 수정.

## Self-Check: PASSED
- 파일 확인: 8 created + 6 modified 전부 디스크 존재.
- 커밋 확인: `2a7fc0a`(feat), `30af72e`(feat), `29dcfc5`(test) git log 존재.
- verify 확인: OrphanReservationRecoveryIntegrationTest 4/4 green + 전체 `:product-service:test` 19 tests 0 failures.
- 브랜치 확인: feat/sku-stock-lifecycle (요구 브랜치 일치). .planning/ 미커밋, push 없음(BRANCH_SAFETY 준수).

---
*Phase: 03-cancel-restore*
*Completed: 2026-07-31*
