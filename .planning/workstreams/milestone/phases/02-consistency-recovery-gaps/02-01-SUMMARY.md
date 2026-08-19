---
phase: 02-consistency-recovery-gaps
plan: 01
subsystem: payments
tags: [resilience4j, circuit-breaker, http-client, processing-recovery, scheduler, testcontainers-adjacent]

# Dependency graph
requires:
  - phase: 01
    provides: 실측 기준선(복구/레이스 수정의 회귀를 검증할 baseline)
provides:
  - "PgCancelHttpClient.getStatus() 실 구현 (ASSUMED PG cancel-status 계약)"
  - "RiskManagementHttpClient.isCharged() 배선 (기존 GET /internal/cancel-limit/check)"
  - "CheckChargeResponseDto record"
  - "ProcessingRecovery recoverOne 자동복구 배선 (getStatus 결과 기반 상태머신) — stale PROCESSING 수동개입 없이 수렴"
affects: [02-03, processing-recovery, scheduler-concurrency]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "외부 HTTP 상태조회 어댑터: circuitBreaker.executeCheckedSupplier + 2xx/null 검증 → XxxServiceException, 기존 cancel()/validateAndReserve() 미러링 (신규 CircuitBreaker/RestTemplate 빈 생성 없음, 기존 pgCancelCircuitBreaker/riskManagementCircuitBreaker 공유)"

key-files:
  created:
    - "payment-service/src/main/java/com/example/payment/application/dto/CheckChargeResponseDto.java"
  modified:
    - "payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java"
    - "payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java"
    - "payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java"
    - "payment-service/src/test/java/com/example/payment/infrastructure/http/RiskManagementHttpClientTest.java"

key-decisions:
  - "D-01 (costly, ASSUMED): getStatus() 를 ASSUMED PG 계약 GET /v1/payments/{paymentKey}/cancel/status → PgCancelResult 매핑으로 구현. 사용자 blocking-human 체크포인트에서 'ASSUMED 계약으로 진행' 승인. 실 PG 계약 문서는 여전히 미확보 — 추후 검증 필요."
  - "D-05: isCharged() 는 신규 API 가 아니라 기존 risk-management GET /internal/cancel-limit/check 배선. CheckChargeResponseDto 로 응답 매핑."
  - "getStatus 스텁(UnsupportedOperationException) 제거로 recoverOne 의 catch 가 이제 진짜 조회 실패(PgServiceException)만 PROCESSING 유지로 걸러 — 무동작 회귀 방지(prohibition 충족)."

patterns-established:
  - "PG 상태조회: 기존 cancel() POST 어댑터를 미러링한 GET 어댑터 (restTemplate.getForEntity + 동일 서킷브레이커 공유)"

requirements-completed: [RESIL-01]

coverage:
  - id: D1
    description: "PgCancelHttpClient.getStatus() 실 구현 — APPROVED/FAILED(retryable)/PENDING 응답을 PgCancelResult 로 매핑, 비-2xx/null/네트워크 예외는 PgServiceException 전파"
    requirement: "RESIL-01"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "RiskManagementHttpClient.isCharged() 배선 + CheckChargeResponseDto — charged=true/false 반환, 비-2xx 는 RiskServiceException"
    requirement: "RESIL-01"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/infrastructure/http/RiskManagementHttpClientTest.java"
        status: pass
    human_judgment: false
  - id: D3
    description: "ProcessingRecovery recoverOne 자동복구 — PROCESSING 5분 초과 건이 getStatus 결과로 APPROVED→TX3 재실행 / 조회실패→PROCESSING 유지 / FAILED→보상 / PENDING→유지·타임아웃보상 분기, 수동개입 없이 수렴"
    requirement: "RESIL-01"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java"
        status: pass
    human_judgment: false
  - id: D4
    description: "ASSUMED PG cancel-status REST 계약(경로/응답 스키마/인증)이 운영 PG 실제 계약과 일치하는지"
    requirement: "RESIL-01"
    verification: []
    human_judgment: true
    rationale: "D-01 은 근거 문서 없는 ASSUMED 계약 — 사용자가 체크포인트에서 진행은 승인했으나 실 PG 문서/스테이징 대조는 미완. 테스트는 가정된 계약에 대해서만 green. 운영 배포 전 실 PG 계약 검증 필요."

# Metrics
duration: ~15min
completed: 2026-07-29
status: complete
---

# Phase 2 Plan 01: ProcessingRecovery 복구 경로 완결 Summary

**getStatus()/isCharged() 스텁 제거 — ASSUMED PG cancel-status 계약 + 기존 risk 차감조회 배선으로 stale PROCESSING 이 스케줄러로 수동개입 없이 수렴**

## Performance

- **Duration:** ~15 min (구현 커밋 01:08–01:09 KST)
- **Completed:** 2026-07-29
- **Tasks:** 3 (Task 1 = blocking-human 체크포인트 승인, Task 2/3 = 구현)
- **Files modified:** 5 (1 created, 4 modified)

## Accomplishments
- `PgCancelHttpClient.getStatus()` 실 구현 — 스텁 `UnsupportedOperationException` 제거, ASSUMED 계약 `GET /v1/payments/{paymentKey}/cancel/status` → `PgCancelResult` 매핑
- `RiskManagementHttpClient.isCharged()` 배선 — 기존 `GET /internal/cancel-limit/check` 호출 + `CheckChargeResponseDto` 응답 매핑
- `ProcessingRecoveryService.recoverOne` 이 getStatus 결과로 APPROVED→TX3 재실행 / 조회실패→PROCESSING 유지 / FAILED→보상 / PENDING→유지·타임아웃보상 으로 분기 — stale PROCESSING 자동 수렴 (수동 개입 불필요)

## Task Commits

1. **Task 1: PG 취소 상태조회 REST 계약 승인 (blocking-human)** — 코드 없음, 사용자 "approved (ASSUMED 진행)" 승인
2. **Task 2: getStatus() 실 구현 + recoverOne 배선** — `97d66d2` (feat)
3. **Task 3: isCharged() 배선 + CheckChargeResponseDto** — `83615ea` (test/RED) → `e216f87` (feat/GREEN)

_TDD: Task 3 는 RED(테스트+DTO) → GREEN(구현) 2 커밋._

## Files Created/Modified
- `application/dto/CheckChargeResponseDto.java` (신규) - risk 차감조회 응답 record (charged/merchantId/cancelAmount)
- `infrastructure/http/PgCancelHttpClient.java` - getStatus() 실 구현 (기존 cancel() 미러링)
- `infrastructure/http/RiskManagementHttpClient.java` - isCharged() 배선
- `infrastructure/http/PgCancelHttpClientTest.java` - getStatus 매핑/예외 케이스
- `infrastructure/http/RiskManagementHttpClientTest.java` - isCharged 매핑/예외 케이스

## Decisions Made
- **D-01 (costly, ASSUMED):** getStatus 를 ASSUMED PG 계약으로 구현. blocking-human 체크포인트에서 사용자가 ASSUMED 진행을 명시 승인. 실 PG 계약 문서는 미확보 → Next Phase Readiness 의 열린 항목.
- **D-05:** isCharged 는 기존 엔드포인트 배선(신규 API 아님).
- getStatus 스텁 제거로 recoverOne 의 예외 삼킴이 이제 진짜 조회 실패만 PROCESSING 유지로 걸러냄(무동작 회귀 방지).

## Deviations from Plan
None - plan executed as specified. (실행이 여러 세션/재개에 걸쳐 진행됐고 구현·테스트는 계획대로 커밋됨; recoverOne 상태머신 배선은 이미 존재해 getStatus 구현으로 활성화됨.)

## Issues Encountered
- 실행 도중 executor 가 gradle 테스트를 백그라운드로 남기고 조기 반환 → 오케스트레이터가 파일시스템/git 스팟체크로 완료 확인(SUMMARY 부재 감지), 테스트 재실행으로 green 확인 후 클로즈아웃. 구현 커밋은 유실 없이 HEAD 에 존재.

## User Setup Required
None - 신규 외부 서비스 설정 없음. (단, ASSUMED PG cancel-status 계약은 운영 배포 전 실 PG 문서/스테이징으로 검증할 것.)

## Next Phase Readiness
- RESIL-01 완료: getStatus/isCharged 실 응답 매핑 + recoverOne 자동복구 green.
- **열린 항목(D4/human_judgment):** ASSUMED PG cancel-status 계약의 실 PG 계약 대조는 미완 — 운영 검증 필요.
- 02-03(RESIL-03)은 `ProcessingRecoveryService.java` 를 공유하므로 이 플랜(02-01) 완료에 의존(Wave 2).

---
*Phase: 02-consistency-recovery-gaps*
*Completed: 2026-07-29*
