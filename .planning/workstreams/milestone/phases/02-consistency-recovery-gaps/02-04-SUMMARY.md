---
phase: 02-consistency-recovery-gaps
plan: 04
subsystem: payments
tags: [pg-integration, toss-payments, http-client, processing-recovery, flyway, contract-correction]

# Dependency graph
requires:
  - phase: 02-01
    provides: "PgCancelHttpClient.getStatus() 최초 구현(ASSUMED PG cancel-status 계약) + ProcessingRecoveryService.recoverOne 배선"
provides:
  - "PgCancelHttpClient.cancel()/getStatus() — 실 Toss Payments 계약(GET/POST /v1/payments/{paymentKey}[/cancel] → Payment{status,cancels[]}) 매핑"
  - "PgCancelPort.getStatus(paymentKey, cancelAmount) — cancels[] 금액 매칭 시그니처"
  - "TossPaymentResponse/TossCancel DTO (infrastructure/http/dto, ignoreUnknown)"
  - "CancelRequest.pgTransactionKey — Toss transactionKey 저장(감사 + 부분취소 동일금액 tiebreaker), assignPgTransactionKey()"
  - "Flyway V13 — cancel_request.pg_transaction_key 컬럼"
affects: [02-01, processing-recovery, cancel-flow, pg-integration]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@JsonIgnoreProperties(ignoreUnknown=true) record DTO로 PG 응답 중 필요한 필드만 매핑(Toss 응답은 필드가 훨씬 많음)"
    - "getStatus(paymentKey, cancelAmount) — PG가 취소건 단위 조회 엔드포인트를 제공하지 않을 때, 결제 전체 조회 응답의 cancels[]를 금액으로 매칭해 '우리 취소가 반영됐는지' 판별"
    - "mutable 도메인 객체(CancelRequest)에 saveTx3 호출 직전 assign 메서드로 세팅 → saveTx3가 CancelRequest를 재조회하지 않는 한 TX 경계/재조회 불변식 훼손 없이 배선 가능"

key-files:
  created:
    - "payment-service/src/main/java/com/example/payment/infrastructure/http/dto/TossPaymentResponse.java"
    - "payment-service/src/main/java/com/example/payment/infrastructure/http/dto/TossCancel.java"
    - "payment-service/src/main/resources/db/migration/V13__add_pg_transaction_key_to_cancel_request.sql"
  modified:
    - "payment-service/src/main/java/com/example/payment/application/interfaces/PgCancelPort.java"
    - "payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java"
    - "payment-service/src/main/java/com/example/payment/infrastructure/http/MockPgCancelClient.java"
    - "payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java"
    - "payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java"
    - "payment-service/src/main/java/com/example/payment/domain/entity/CancelRequest.java"
    - "payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaEntity.java"
    - "payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java"
    - "payment-service/src/test/java/com/example/payment/infrastructure/http/MockPgCancelClientTest.java"
    - "payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientCardinalityTest.java"
    - "payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java"
    - "payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryOutboxIT.java"
    - "payment-service/src/test/java/com/example/payment/application/service/CancelTxWriterTest.java"
    - "payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java"
    - "payment-service/src/test/java/com/example/payment/domain/entity/CancelRequestTest.java"
    - "payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImplTest.java"
    - "payment-service/src/test/java/com/example/payment/fixture/CancelRequestFixture.java"
    - "payment-service/src/test/java/com/example/payment/integration/ProcessingRecoveryConcurrencyIT.java"

key-decisions:
  - "D-01 정정(사용자 승인): 02-01에서 구현한 PgCancelHttpClient.getStatus()/cancel()은 존재하지 않는 ASSUMED 엔드포인트(GET .../cancel/status)와 {pgTransactionId,status,retryable} 직접 역직렬화를 가정했다 — 실 Toss Payments 계약(GET/POST /v1/payments/{paymentKey}[/cancel] → Payment{status,cancels[]})으로 교체."
  - "PgCancelPort.getStatus(paymentKey) → getStatus(paymentKey, cancelAmount): Toss에는 취소건 단위 상태 조회 엔드포인트가 없어 결제 전체 조회의 cancels[] 배열에서 금액으로 우리 취소를 매칭해야 함. 호출부(ProcessingRecoveryService.recoverOne) + MockPgCancelClient 동시 갱신."
  - "getStatus 매핑 규칙 1~7을 우선순위 순서로 구현: (1) cancelStatus=DONE+금액일치 cancel 존재 → approved, (2) status=CANCELED(매칭 없어도 전액취소) → approved, (3) status=DONE(활성, 미취소) → retryableFailed, (4) IN_PROGRESS/WAITING_FOR_DEPOSIT → pending, (5) ABORTED/EXPIRED → 재시도불가 failed, (6) PARTIAL_CANCELED(매칭 DONE cancel 없음) → retryableFailed, (7) 그 외 알 수 없는 status(READY 포함) → PgServiceException(조용히 무시하지 않고 예외로 PROCESSING 유지 + 다음 주기 재시도)."
  - "transactionKey 저장은 사용자 선택 A(감사 + 부분취소 동일금액 tiebreaker) 채택: domain CancelRequest에 pgTransactionKey 필드 + assignPgTransactionKey() 추가, reconstruct()에 13번째 파라미터로 확장(모든 호출부 19개소 갱신, 기존 동작 불변 — 대부분 null 전달)."
  - "TX3 배선 불변식 확인(설계 문서의 '위험 신호' 우려 해소): CancelTxWriter.saveTx3는 PaymentItem만 findAllByPaymentIdForUpdate()로 재조회하고, CancelRequest 자체는 전달받은 객체를 그대로 toCompleted() 후 저장한다(재조회 없음) — 따라서 saveTx3 호출 '직전'에 cancelRequest.assignPgTransactionKey()로 세팅하는 것만으로 TX3의 재조회/원자성/Kafka 인라인 발행을 전혀 건드리지 않고 안전하게 배선 가능. 메인 흐름(CancelPaymentService.proceedFromRisk)과 복구 흐름(ProcessingRecoveryService.runTx3/retryPgCancel) 모두 이 지점에서 세팅."

requirements-completed: []

coverage:
  - id: D1
    description: "PgCancelHttpClient.getStatus(paymentKey, cancelAmount) — Toss cancels[] 매핑 규칙 1~7 (DONE+금액매칭/전액취소/활성/진행중/만료·중단/부분취소미반영/알수없음)"
    requirement: "RESIL-01"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java#getStatus_rule1_matching_done_cancel_by_amount_returns_approved..getStatus_rule7_unknown_status_throws_pg_service_exception"
        status: pass
    human_judgment: false
  - id: D2
    description: "PgCancelHttpClient.cancel() — Toss 취소 실행 응답(CANCELED/PARTIAL_CANCELED/WAITING_FOR_DEPOSIT/IN_PROGRESS) → approved(transactionKey)/pending(transactionKey) 매핑"
    requirement: "RESIL-01"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java#cancel_maps_toss_response_to_approved_with_transactionKey"
        status: pass
    human_judgment: false
  - id: D3
    description: "CancelRequest.pgTransactionKey — assignPgTransactionKey/getPgTransactionKey + reconstruct 복원, JPA V13 컬럼 왕복 저장"
    requirement: "RESIL-01"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/domain/entity/CancelRequestTest.java#assignPgTransactionKey_setsValue"
        status: pass
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImplTest.java#should_persist_pg_transaction_key"
        status: pass
    human_judgment: false

# Metrics
duration: ~90min
completed: 2026-07-29
status: complete
---

# Phase 2 Plan 04: PgCancelHttpClient 실 Toss 계약 정정 Summary

**PgCancelHttpClient.getStatus()/cancel()이 가정했던 존재하지 않는 PG 엔드포인트·응답 계약을 실 Toss Payments 계약(GET/POST /v1/payments/{paymentKey}[/cancel] → Payment{status,cancels[]})으로 교체하고, 취소 transactionKey를 cancel_request에 저장 배선**

## Performance

- **Duration:** ~90 min
- **Completed:** 2026-07-29
- **Tasks:** 3 (RED→GREEN Toss 매핑, RED→GREEN transactionKey 저장, cardinality 테스트 정정)
- **Files modified:** 20 (3 created, 17 modified)

## Accomplishments
- `TossPaymentResponse`/`TossCancel` DTO(신규, `@JsonIgnoreProperties(ignoreUnknown=true)`) — Toss 응답 중 필요한 필드만 파싱
- `PgCancelPort.getStatus(String paymentKey)` → `getStatus(String paymentKey, BigDecimal cancelAmount)`로 시그니처 변경 — cancels[]에서 우리 취소를 금액으로 매칭
- `PgCancelHttpClient`: `mapStatus()`(getStatus 매핑 규칙 1~7) / `mapCancelResponse()`(cancel() 응답 매핑) 신규 구현. URL도 `GET /v1/payments/{paymentKey}`(기존 ASSUMED `.../cancel/status` 제거)로 정정
- `MockPgCancelClient`, `ProcessingRecoveryService.recoverOne` 호출부 시그니처 반영
- `CancelRequest` 도메인: `pgTransactionKey` 필드 + `getPgTransactionKey()`/`assignPgTransactionKey(String)` + `reconstruct()` 파라미터 확장(호출부 19개소 갱신)
- Flyway `V13__add_pg_transaction_key_to_cancel_request.sql`(신규) + `CancelRequestJpaEntity` 컬럼 매핑 왕복
- 메인 취소 흐름(`CancelPaymentService.proceedFromRisk`)과 복구 흐름(`ProcessingRecoveryService.runTx3`/`retryPgCancel`) 모두 `saveTx3` 호출 직전에 `assignPgTransactionKey()` 세팅 — TX3 재조회 불변식 미훼손

## Task Commits

Each task was committed atomically:

1. **RED: PgCancelHttpClient Toss 계약 매핑 실패 테스트** - `9e53ad2` (test)
2. **GREEN: PgCancelHttpClient/Mock/Port 실 Toss 계약 매핑 구현** - `0915594` (feat)
3. **RED: CancelRequest pgTransactionKey 저장 실패 테스트** - `b5d6b88` (test)
4. **GREEN: CancelRequest pg_transaction_key 저장 배선** - `021d8ad` (feat)
5. **fix: PgCancelHttpClientCardinalityTest 실 Toss 응답 형태로 정정** - `6fa62a4` (fix)

## TDD Gate Compliance

RED 확인 방식에 대한 참고: 이 플랜의 RED 단계는 (통상적인 "컴파일은 되지만 어서션이 실패") 대신 **컴파일 실패**로 RED를 증명했다 — `PgCancelPort.getStatus`/`CancelRequest.reconstruct` 시그니처 자체가 이 플랜의 핵심 변경 대상이라, 새 시그니처를 호출하는 테스트는 인터페이스가 아직 안 바뀐 상태에서 필연적으로 컴파일되지 않는다(정적 타입 언어에서 통용되는 RED 증거). 두 RED 커밋 모두 `./gradlew :payment-service:compileTestJava` 실행 → 컴파일 에러(각 14건/5건) 확인 후 커밋했고, 이어지는 GREEN 커밋에서 프로덕션 코드 + 관련 테스트 시그니처를 함께 갱신해 green 전환을 검증했다.

## Files Created/Modified
- `infrastructure/http/dto/TossPaymentResponse.java`(신규), `TossCancel.java`(신규) — Toss 응답 DTO
- `application/interfaces/PgCancelPort.java` — `getStatus` 시그니처 변경
- `infrastructure/http/PgCancelHttpClient.java` — Toss URL + `mapStatus`/`mapCancelResponse` 매핑 로직
- `infrastructure/http/MockPgCancelClient.java` — 시그니처 반영
- `application/service/ProcessingRecoveryService.java` — `getStatus` 호출부 + `runTx3`/`retryPgCancel` transactionKey 세팅
- `application/service/CancelPaymentService.java` — `proceedFromRisk` transactionKey 세팅
- `domain/entity/CancelRequest.java` — `pgTransactionKey` 필드/getter/assign/`reconstruct` 확장
- `infrastructure/persistence/CancelRequestJpaEntity.java` — 컬럼 매핑 왕복
- `db/migration/V13__add_pg_transaction_key_to_cancel_request.sql`(신규)
- 테스트 15개 파일 — 시그니처 반영(`reconstruct` 19개소 null 전달, `getStatus` 2-arg), `PgCancelHttpClientTest`(Toss 규칙 1~7 20개 케이스), `CancelRequestTest`(assign/reconstruct 복원), `CancelRequestRepositoryImplTest`(JPA 왕복), `PgCancelHttpClientCardinalityTest`(응답 바디 Toss 형태로 정정)

## Decisions Made
`key-decisions` 프론트매터 참조(D-01 정정, getStatus 시그니처 변경, 매핑 규칙 1~7, transactionKey 저장 선택 A, TX3 불변식 확인).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] PgCancelHttpClientCardinalityTest가 레거시 응답 형태를 stub — D-01 GREEN 커밋 직후 전체 테스트 실행에서 발견**
- **Found during:** 전체 `./gradlew :payment-service:test` 실행 (필수 마지막 검증 단계)
- **Issue:** `read_first` 목록에 없던 이 테스트가 `MockRestServiceServer`로 레거시 `PgCancelResult`(`{pgTransactionId,status,retryable}`) JSON을 stub하고 있었는데, `cancel()`이 이제 `TossPaymentResponse`로 파싱하면서 `status="APPROVED"`가 Toss의 알 수 없는 status로 해석돼 `PgServiceException`이 던져짐.
- **Fix:** stub 응답 바디를 Toss 계약 형태(`status=CANCELED, cancels[]={cancelAmount,transactionKey,cancelStatus=DONE}`)로 교체. 이 테스트의 본래 검증 목적(uri 태그 cardinality — paymentKey가 확장되지 않고 템플릿으로 기록되는지)은 변경 없음.
- **Files modified:** `payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientCardinalityTest.java`
- **Verification:** `./gradlew :payment-service:test --tests "*PgCancelHttpClientCardinalityTest"` green, 이후 전체 `./gradlew :payment-service:test` 230 tests 0 failures
- **Committed in:** `6fa62a4`

---

**Total deviations:** 1 auto-fixed (bug)
**Impact on plan:** `read_first` 목록에 없던 파일이었으나 계약 변경의 직접 파급 범위(cancel() 응답 타입 변경)에 속하므로 Rule 1 범위 내. 매핑 로직 자체는 변경 없음.

## Config Note (User Setup / Open Items — 배포 전 필요, 이번 작업 범위 밖)

`payment-service/src/main/resources/application.yml`의 `external.pg.url: http://pg-gateway:443`는 실 Toss(`https://api.tosspayments.com`)가 아닌 mock 게이트웨이이며 Basic 인증이 설정돼 있지 않다. 코드 계약(요청/응답 형태)은 이번 작업으로 실 Toss에 맞췄으나, 실 Toss 연동 시 배포 전 다음 두 가지가 별도로 필요하다:

1. `external.pg.url`을 `https://api.tosspayments.com`으로 변경
2. `RestTemplate`에 Basic 인증(시크릿 키) 헤더 추가 — 이번 작업 범위 밖(인증 헤더 구현 미포함)

## Issues Encountered
None beyond the cardinality test deviation above.

## User Setup Required
없음 — Config Note의 두 항목(Toss URL 전환, Basic 인증 헤더)은 실 Toss 연동 배포 시점의 별도 작업이며 이번 플랜 범위 밖.

## Next Phase Readiness
- D-01(getStatus/cancel Toss 계약 정정) 완료 — `./gradlew :payment-service:test` 전체 230 tests green (Testcontainers ITs 포함, Docker up).
- transactionKey 저장 배선 완료(TX3 불변식 미훼손 확인) — 추가 후속 조치 불필요.
- STATE.md/ROADMAP.md는 이 정정이 기존 RESIL-01 범위 보정이므로 갱신하지 않음(계획 success_criteria 명시). REQUIREMENTS.md도 미변경.

---
*Phase: 02-consistency-recovery-gaps*
*Completed: 2026-07-29*

## Self-Check: PASSED
All created files found on disk (TossPaymentResponse.java, TossCancel.java, V13 migration, SUMMARY.md) and modified key files confirmed present. All 5 task commits (9e53ad2, 0915594, b5d6b88, 021d8ad, 6fa62a4) found in git history.
