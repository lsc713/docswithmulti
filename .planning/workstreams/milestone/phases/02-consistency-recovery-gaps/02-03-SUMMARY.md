---
phase: 02-consistency-recovery-gaps
plan: 03
subsystem: payments
tags: [spring-data-jpa, atomic-update, concurrency, testcontainers, scheduler]

# Dependency graph
requires:
  - phase: 02-01
    provides: "ProcessingRecoveryService.recoverOne 자동복구 배선 (retryPgCancel 이 이 위에서 동작)"
provides:
  - "CancelRequestJpaRepository.incrementPgRetryCount(long) — @Modifying(clearAutomatically=true) 원자 UPDATE"
  - "ProcessingRecoveryService.retryPgCancel — 원자 UPDATE + 재조회 기반 MAX_PG_RETRIES 게이트(PG 재호출 전 차단)"
  - "ProcessingRecoveryConcurrencyIT — Testcontainers 동시성 재현(원자 카운터 유실 0, saveTx3 동시 재실행 정확히 1건 COMPLETED)"
affects: [processing-recovery, scheduler-concurrency]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "원자 UPDATE(@Modifying(clearAutomatically=true) 네이티브 쿼리)로 read-modify-write 경쟁 제거 — MerchantCancelUsageJpaRepository.tryDeduct 컨벤션 이식"
    - "원자 UPDATE 직후 로컬 도메인 객체는 stale — 임계값 비교 전 findByPaymentIdAndRequestHash 로 재조회 필수"

key-files:
  created:
    - "payment-service/src/test/java/com/example/payment/integration/ProcessingRecoveryConcurrencyIT.java"
  modified:
    - "payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaRepository.java"
    - "payment-service/src/main/java/com/example/payment/application/interfaces/CancelRequestRepository.java"
    - "payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImpl.java"
    - "payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java"
    - "payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java"

key-decisions:
  - "D-04(locked): pg_retry_count 를 객체 mutation+save 대신 단일 SQL 원자 UPDATE(InnoDB 행 락)로 교정. 레코드 단위 분산락은 추가하지 않음(YAGNI — 스케줄러 Redis 분산락이 이미 단일 실행 보장)."
  - "retryPgCancel 재설계: 원자 UPDATE → 재조회 → 재조회값이 MAX_PG_RETRIES(5) 도달 시 PG 재호출 없이 즉시 보상+FAILED(재시도 폭주 차단, T-02-07). 임계 미만이면 기존처럼 PG 재호출 → 승인 시 TX3, 미승인/예외 시 PROCESSING 유지(다음 주기 재시도). 기존 '호출 후 실패 시에만 임계값 체크'에서 '호출 전에 임계값으로 게이트'로 변경 — RESEARCH.md Pattern 2 예시 코드와 동일한 순서."
  - "ProcessingRecoveryConcurrencyIT 는 payment-service AbstractRepositoryTest 를 상속하지 않음(계획의 read_first 는 이를 참조했으나 실측 후 판단 변경, Rule 3): 그 클래스의 클래스 레벨 @Transactional(자동 롤백)이 메인 스레드 픽스처를 워커 스레드에서 안 보이게 만들어 동시성 재현과 충돌. 대신 ProcessingRecoveryOutboxIT 컨벤션(자체 @Testcontainers/@Container + 외부 포트 MockitoBean, OUTBOX 발행 모드)을 따름."

patterns-established:
  - "스케줄러 카운터 필드 갱신: 객체 mutation 금지, 단일 네이티브 UPDATE + clearAutomatically=true + 호출자 재조회"

requirements-completed: [RESIL-03]

coverage:
  - id: D1
    description: "CancelRequestJpaRepository.incrementPgRetryCount — @Modifying(clearAutomatically=true) 원자 UPDATE, 호출 1회당 DB pg_retry_count 정확히 +1"
    requirement: "RESIL-03"
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/ProcessingRecoveryConcurrencyIT.java#concurrent_increment_never_loses_updates"
        status: pass
    human_judgment: false
  - id: D2
    description: "ProcessingRecoveryService.retryPgCancel — mutation+save 제거, 원자 UPDATE 후 재조회한 값으로 MAX_PG_RETRIES 비교(재조회 >= MAX → PG 재호출 없이 즉시 보상+FAILED)"
    requirement: "RESIL-03"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java#pg_failed_retryable_refetched_count_at_max_compensates_without_pg_call"
        status: pass
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java#pg_failed_retryable_retries_pg_and_succeeds"
        status: pass
    human_judgment: false
  - id: D3
    description: "두 스레드가 같은 CancelRequest 로 saveTx3(TX3 재실행)를 동시 시도해도 정확히 1건 COMPLETED, 이중취소 0 (실 MySQL Testcontainers, D-02 재현 범위)"
    requirement: "RESIL-03"
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/ProcessingRecoveryConcurrencyIT.java#concurrent_tx3_rerun_completes_exactly_once"
        status: pass
    human_judgment: false

# Metrics
duration: ~55min
completed: 2026-07-29
status: complete
---

# Phase 2 Plan 03: ProcessingRecovery 동시성 가드 Summary

**pg_retry_count 를 객체 mutation+save 에서 단일 SQL 원자 UPDATE(D-04)로 교정하고, retryPgCancel 을 재조회-후-게이트 방식으로 재설계해 스케줄러 동시 실행 시 카운터 유실·재시도 폭주를 실 MySQL(Testcontainers) 로 증명**

## Performance

- **Duration:** ~55 min
- **Completed:** 2026-07-29
- **Tasks:** 2
- **Files modified:** 6 (1 created, 5 modified)

## Accomplishments
- `CancelRequestJpaRepository.incrementPgRetryCount(long)` — `MerchantCancelUsageJpaRepository.tryDeduct/tryRestore` 컨벤션을 이식한 `@Modifying(flushAutomatically=true, clearAutomatically=true)` 네이티브 UPDATE로 pg_retry_count 원자 증가
- `CancelRequestRepository`(interface) + `CancelRequestRepositoryImpl` 위임 배선
- `ProcessingRecoveryService.retryPgCancel` 재설계: 원자 UPDATE → 재조회 → `MAX_PG_RETRIES` 임계값을 **재조회한 값**으로 비교. 임계 도달 시 PG 재호출 자체를 생략하고 즉시 보상+FAILED로 전환(재시도 폭주 방지, T-02-07). 임계 미만이면 기존 PG 재호출 경로 유지(승인→TX3, 미승인/예외→PROCESSING 유지)
- `ProcessingRecoveryConcurrencyIT`(신규, Testcontainers): (A) 30 스레드가 `incrementPgRetryCount` 동시 호출 → 최종 카운트 정확히 30(유실 0), (B) 2 스레드가 같은 CancelRequest 로 `saveTx3` 동시 재실행 → 승자 1명·패자 1명(정상 레이스), 최종 cancel_request 정확히 1건 COMPLETED, PaymentItem 이중취소 0

## Task Commits

Each task was committed atomically:

1. **Task 1: pg_retry_count 원자 UPDATE 배선 + retryPgCancel 교정 (재조회 포함)** - `6019f74` (feat)
2. **Task 2: ProcessingRecoveryConcurrencyIT — 동시 스케줄러 실행 Testcontainers 재현** - `5fb0da8` (test)

_Task 1 은 tracer/TDD 태스크였으나 이미 존재하는 `retryPgCancel` 로직을 원자 UPDATE 로 교정하는 리팩터 성격이라 RED(별도 실패 커밋) 없이 구현+테스트 갱신을 단일 feat 커밋으로 묶음 — 갱신된 `ProcessingRecoveryServiceTest` 케이스가 변경 전 코드로는 실패(구 테스트는 `findByPaymentIdAndRequestHash` 미스텁으로 인해 재설계된 구현에서만 통과)하므로 실질적으로 RED→GREEN 검증은 구현 전/후 비교로 확인됨._

## Files Created/Modified
- `infrastructure/persistence/CancelRequestJpaRepository.java` - `incrementPgRetryCount` 원자 UPDATE 추가
- `application/interfaces/CancelRequestRepository.java` - `incrementPgRetryCount(long)` 포트 선언 추가
- `infrastructure/persistence/CancelRequestRepositoryImpl.java` - 위임 메서드 추가
- `application/service/ProcessingRecoveryService.java` - `retryPgCancel` 원자 UPDATE+재조회 기반 재설계
- `application/service/ProcessingRecoveryServiceTest.java` - 재조회 스텁 추가, "재호출 예외 시 보상" 테스트를 "재조회 임계값 도달 시 PG 재호출 없이 즉시 보상"으로 갱신
- `integration/ProcessingRecoveryConcurrencyIT.java` (신규) - 원자 카운터 동시성 + saveTx3 동시 재실행 Testcontainers 증명

## Decisions Made
- **D-04(locked) 적용:** 레코드 단위 분산락 미추가(YAGNI) — 단일 SQL 원자 UPDATE 만으로 "카운터 유실 0" 요구 충족.
- **retryPgCancel 게이트 순서 변경:** 기존은 "PG 호출 후 실패 시에만 임계값 체크"였으나, RESEARCH.md Pattern 2 예시(원자 UPDATE→재조회→즉시 비교)를 그대로 따라 "임계값 먼저 체크, 도달 시 PG 호출 자체를 생략"으로 변경. 위협모델 T-02-07(재시도 폭주 DoS)에 더 부합하는 방향이며, 기존 단위 테스트의 어서션(보상+FAILED 호출 여부)은 그대로 만족.
- **ProcessingRecoveryConcurrencyIT 는 AbstractRepositoryTest 미상속(Rule 3, 실측 기반 조정):** payment-service의 `AbstractRepositoryTest`는 클래스 레벨 `@Transactional`(자동 롤백)을 걸어 테스트 메서드 전체를 하나의 TX로 감싸는데, 이러면 메인 스레드가 커밋한 픽스처를 워커 스레드(별도 DB 커넥션)가 볼 수 없어 동시성 재현이 원천적으로 불가능하다. 계획의 read_first 목록에는 이 파일이 있었지만, 실측 후 `ProcessingRecoveryOutboxIT`(자체 `@Testcontainers`/`@Container` + 외부 포트 MockitoBean, `cancel.publish.mode=OUTBOX`) 컨벤션을 채택 — 같은 소스셋의 기존 IT 패턴이므로 범위 이탈 아님.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] ProcessingRecoveryConcurrencyIT 테스트 기반 클래스 변경**
- **Found during:** Task 2
- **Issue:** 계획이 지시한 `AbstractRepositoryTest`(payment-service) 상속 시, 클래스 레벨 `@Transactional`이 테스트 메서드 전체를 하나의 TX로 감싸 워커 스레드가 메인 스레드의 미커밋 픽스처를 못 봄 — 동시성 시나리오 자체가 성립 불가.
- **Fix:** `ProcessingRecoveryOutboxIT` 컨벤션(자체 Testcontainers `@Container` + MockitoBean 외부 포트, `TransactionTemplate`으로 픽스처 커밋)으로 대체.
- **Files modified:** `payment-service/src/test/java/com/example/payment/integration/ProcessingRecoveryConcurrencyIT.java`
- **Verification:** `./gradlew :payment-service:test --tests "*ProcessingRecoveryConcurrencyIT"` green (2/2, 실 MySQL Testcontainers)
- **Committed in:** `5fb0da8` (Task 2 커밋)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** 테스트 기반 클래스만 변경, 검증 시나리오·assertion은 계획 그대로. 범위 이탈 없음.

## Issues Encountered
- **Pitfall 1(TX3 동시 재실행 패자의 InvalidPaymentItemStatusException 오탐 ERROR 로깅)**: 계획 지시대로 새 방어막을 신설하지 않았고 `ProcessingRecoveryService.recoverOne`의 기존 `catch (BusinessException e)` ERROR 로그도 그대로 두었다(로그 명확화 자체도 이번 플랜 범위에서는 손대지 않음 — 데이터 손상이 아니므로 낮은 우선순위, 02-CONTEXT.md 위협모델 T-02-08이 이미 `accept`로 분류). `ProcessingRecoveryConcurrencyIT`의 saveTx3 동시 재실행 테스트에서 패자 스레드가 이 예외를 던지는 것을 직접 관측·검증했으며, 최종 DB 상태(정확히 1건 COMPLETED)만 단언하도록 설계했다. **후속 발견사항으로만 기록** — 별도 조치 없음.

## User Setup Required
None - 신규 외부 서비스 설정 없음.

## Next Phase Readiness
- RESIL-03 완료: pg_retry_count 원자 UPDATE + retryPgCancel 재조회 게이트 + 동시성 Testcontainers 증명 green.
- Phase 2(RESIL-01/02/03) 3개 플랜 모두 완료 — `/gsd-verify-work` 또는 phase 종료 검증으로 진행 가능.
- **열린 항목(비차단):** Pitfall 1 로그 명확화(동시 레이스 무해 실패 vs 진짜 정합성 위반 구분)는 이번 플랜 범위 밖으로 남음 — 필요 시 후속 개선.

---
*Phase: 02-consistency-recovery-gaps*
*Completed: 2026-07-29*

## Self-Check: PASSED
All created/modified files found on disk. Both task commits (6019f74, 5fb0da8) found in git history.
