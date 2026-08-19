---
phase: 02-consistency-recovery-gaps
verified: 2026-07-29T14:40:00Z
status: passed
score: 10/10 must-haves verified
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 10/10
  gaps_closed:
    - "ASSUMED PG cancel-status 계약(D-01) 운영 대조 — 사용자가 실 Toss Payments 공식 문서를 제공, ASSUMED 계약이 실 계약과 불일치함을 확인, PgCancelHttpClient.getStatus()/cancel()을 실 Toss 계약(GET/POST /v1/payments/{paymentKey}[/cancel] → Payment{status,cancels[]})으로 정정 완료(commits 9e53ad2, 0915594, b5d6b88, 021d8ad, 6fa62a4). 02-UAT.md status:resolved."
  gaps_remaining: []
  regressions: []
---

# Phase 2: 정합성 & 복구 갭 마감 Verification Report (재검증)

**Phase Goal:** 단일 인스턴스에서는 안 드러나고 멀티파드/스케일아웃에서만 드러난 정합성·복구 결함을 제거한다. stale PROCESSING 이 수동 개입 없이 수렴하고, 동시 취소 레이스가 멱등하게 응답한다.
**Verified:** 2026-07-29T14:40:00Z
**Status:** passed
**Re-verification:** Yes — D-01 PG 계약 정정 이후 (이전 검증의 유일한 human_needed 항목이 코드 정정 + 사용자 확인으로 RESOLVED됨)

## Re-verification Context

이전 검증(2026-07-29T13:45:00Z)은 10/10 truths verified 였으나 상태가 `human_needed`였다. 유일한 이유는 D-01(ASSUMED PG 계약)이 실 PG 문서와 대조되지 않았다는 것이었다. 이번 세션에서:

1. 사용자가 실 Toss Payments 공식 문서를 제공했다.
2. ASSUMED 계약(`GET /v1/payments/{paymentKey}/cancel/status` + `{pgTransactionId,status,retryable}`)이 존재하지 않는 엔드포인트/응답 스키마였음을 확인했다.
3. 02-04 플랜(5 커밋: 9e53ad2, 0915594, b5d6b88, 021d8ad, 6fa62a4)에서 `PgCancelHttpClient.getStatus()/cancel()`을 실 Toss 계약(`GET/POST /v1/payments/{paymentKey}[/cancel]` → `Payment{status, cancels[]}`)으로 교체했다.
4. `02-UAT.md`가 `status: resolved`로 갱신됐다.

이번 검증은 SUMMARY/UAT의 "resolved" 주장을 신뢰하지 않고, 정정된 소스를 직접 읽고 전체 테스트를 재실행해 RESIL-01/02/03 목표가 여전히 성립하는지, 그리고 D-01 정정이 회귀를 일으키지 않았는지를 확인했다.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | PROCESSING 5분 초과 건이 PG사 조회 → TX3 재실행/보상으로 자동 복구된다 (SC1) | ✓ VERIFIED | `ProcessingRecoveryService.recoverOne`이 `pgCancelPort.getStatus(paymentKey, cancelAmount)` 결과(`isApproved`→`runTx3`, `isFailed`→`handleFailed`(retryable 재호출/비retryable 보상+FAILED), `isPending`→`handlePgPending`)로 분기하는 소스를 직접 읽고 확인. `ProcessingRecoveryServiceTest` 13/13 pass(본 세션 직접 재실행, `--rerun`으로 캐시 무시) |
| 2 | `getStatus()`/`isCharged()` 가 `UnsupportedOperationException` 을 던지지 않는다 (SC1) | ✓ VERIFIED | 소스 직접 읽음: `PgCancelHttpClient.getStatus()`는 실 Toss `GET /v1/payments/{paymentKey}` 호출 + `mapStatus()`(규칙 1~7) 구현만 존재. `RiskManagementHttpClient.isCharged()`는 `/internal/cancel-limit/check` 실 호출. 27개 phase-touched 소스/테스트 파일 전체에 `UnsupportedOperationException`/`TODO`/`FIXME`/`XXX`/`TBD`/`HACK`/`PLACEHOLDER` grep 0건(본 세션 직접 실행). `PgCancelHttpClientTest` 20/20, `RiskManagementHttpClientTest` 11/11 pass |
| 3 | getStatus 조회 실패는 예외로 전파되고 `recoverOne` 은 PROCESSING 을 유지, 무동작 회귀가 아니다 | ✓ VERIFIED | `recoverOne`의 `catch (Exception e) { log.warn(...); return; }` — 상태 변경 없이 조기 반환(소스 확인). D-01 정정 후에도 이 catch 블록은 변경되지 않음 |
| 4 | 동일 취소를 두 payment 파드에 동시 착지시키면 패자가 500 이 아니라 멱등 응답(200+status)을 반환한다 (SC2) | ✓ VERIFIED | `CancelPaymentService.executeCancel`의 `saveTx1` `DataIntegrityViolationException` catch → `findByPaymentIdAndRequestHash` 재조회 → `handleExistingRequest` 위임(소스 확인, D-01 정정과 무관한 경로 — 변경 없음). `CancelRaceIdempotencyIT` 를 본 세션에서 직접 재실행(Testcontainers 실 MySQL) — 1/1 pass, 0 failures |
| 5 | DB 는 (payment_id, request_hash) 정확히 1건 COMPLETED, 이중취소 0 (SC2) | ✓ VERIFIED | 동일 IT assertion(a)(b) 재실행 확인: `cancel_request` 1 row COMPLETED, PaymentItem 정확히 1회 CANCELLED |
| 6 | pg_retry_count 원자 UPDATE 를 N번 호출하면 정확히 N 증가한다 (SC3) | ✓ VERIFIED | `CancelRequestJpaRepository.incrementPgRetryCount` — `@Modifying(clearAutomatically=true)` native UPDATE(소스 확인, 변경 없음). `ProcessingRecoveryConcurrencyIT#concurrent_increment_never_loses_updates`(30스레드) 본 세션 직접 재실행 — pass |
| 7 | 두 스케줄러 인스턴스가 같은 CancelRequest 를 동시 복구해도 pg_retry_count 유실·중복 복구 없이 정확히 한 번 처리된다 (SC3) | ✓ VERIFIED | `ProcessingRecoveryConcurrencyIT#concurrent_tx3_rerun_completes_exactly_once`(2스레드) + `#concurrent_compare_and_set_failed_has_exactly_one_winner`(10스레드) 본 세션 직접 재실행 — 3/3 pass, 승자 1/패자 1, 최종 1건 COMPLETED |
| 8 | `getStatus(paymentKey, cancelAmount)` 가 실 Toss Payments 계약(`GET /v1/payments/{paymentKey}` → `Payment{status, cancels[]}`)과 일치하고 존재하지 않는 ASSUMED 엔드포인트를 더 이상 호출하지 않는다 (D-01 정정 확인) | ✓ VERIFIED | 소스 직접 대조: URL이 `baseUrl + "/v1/payments/{paymentKey}"`(구 `.../cancel/status` 완전 제거), 응답을 `TossPaymentResponse{status, balanceAmount, cancels[]}`로 파싱, `cancels[]`를 `cancelAmount`로 매칭하는 규칙 1~7 구현. `TossPaymentResponse`/`TossCancel` DTO에 `@JsonIgnoreProperties(ignoreUnknown=true)` 확인. `PgCancelHttpClientTest`(규칙 1~7 케이스 포함 20개) + `PgCancelHttpClientCardinalityTest`(Toss 응답 형태로 정정됨) 본 세션 직접 재실행 — 20/20, 1/1 pass |
| 9 | `cancel()` 이 Toss 취소 실행 응답을 파싱해 `transactionKey`를 추출하고, 이를 `CancelRequest.pgTransactionKey`로 저장한다(TX3 재조회 불변식 미훼손) | ✓ VERIFIED | `PgCancelHttpClient.mapCancelResponse()`가 `TossPaymentResponse`에서 매칭 cancel의 `transactionKey` 추출(소스 확인). `CancelPaymentService.proceedFromRisk`/`ProcessingRecoveryService.runTx3` 모두 `saveTx3` 호출 **직전**에 `cancelRequest.assignPgTransactionKey(pgResult.pgTransactionId())` 세팅(소스 확인) — `CancelTxWriter.saveTx3`는 `CancelRequest`를 재조회하지 않고 전달받은 객체를 그대로 저장하므로 TX3 재조회 불변식(`findAllByPaymentIdForUpdate`는 PaymentItem에만 적용) 미훼손. Flyway `V13__add_pg_transaction_key_to_cancel_request.sql` 존재 확인. `CancelRequestTest`(21/21), `CancelRequestRepositoryImplTest`(7/7, JPA 왕복) 본 세션 직접 재실행 pass |
| 10 | (D-02) 동시성 재현은 Testcontainers(실 MySQL) + 같은 JVM 스레드로 하며 실제 2 인스턴스는 구동하지 않는다 | ✓ VERIFIED | `CancelRaceIdempotencyIT`/`ProcessingRecoveryConcurrencyIT` 모두 `@Testcontainers` + `MySQLContainer` + `ExecutorService`/`CountDownLatch` 골격(소스 확인, D-01 정정과 무관 — PgCancelPort는 두 IT 모두 `@MockitoBean`으로 모킹돼 실 PgCancelHttpClient 계약 변경의 영향을 받지 않음) |

**Score:** 10/10 truths verified (0 present-but-behavior-unverified)

### D-01 정정 회귀 점검

D-01 정정(02-04)이 RESIL-01/02/03 이전 검증 결과를 퇴행시키지 않았는지 직접 소스 대조 + 재실행으로 확인:

| 항목 | 정정 전(ASSUMED) | 정정 후(실 Toss) | 회귀 위험 | 확인 결과 |
|------|------------------|-------------------|-----------|-----------|
| `PgCancelPort.getStatus` 시그니처 | `getStatus(paymentKey)` | `getStatus(paymentKey, cancelAmount)` | 호출부(`ProcessingRecoveryService.recoverOne`, `MockPgCancelClient`) 누락 시 컴파일 실패 | 두 호출부 모두 2-arg로 갱신 확인, 컴파일/테스트 green |
| 응답 매핑 | `{pgTransactionId,status,retryable}` 직접 역직렬화 | `Payment{status,cancels[]}` → 규칙 1~7 매핑 | `PgCancelResult`(APPROVED/FAILED/PENDING) 반환 계약이 바뀌면 `recoverOne`의 분기 로직이 깨질 수 있음 | `PgCancelResult` 반환 타입/의미(`isApproved/isFailed/isPending/isRetryable`)는 불변 — `mapStatus()`가 이 계약으로 변환만 담당. `ProcessingRecoveryServiceTest`(recoverOne 분기 테스트) 13/13 pass 유지 |
| `CancelRequest.reconstruct` 시그니처 | 12 파라미터 | 13 파라미터(`pgTransactionKey` 추가) | 19개 호출부 중 누락 시 컴파일 실패 | 전체 빌드 green(`./gradlew :payment-service:test` BUILD SUCCESSFUL) — 누락 호출부 없음 |
| RESIL-02(레이스 멱등)/RESIL-03(동시성 가드) 로직 | — | — | 코드 변경 없음(D-01은 PG HTTP 계약에만 국한) | `CancelPaymentService.executeCancel`의 UK catch, `CancelRequestJpaRepository.incrementPgRetryCount`/`compareAndSetFailed` 소스 diff 없음 확인. `CancelRaceIdempotencyIT`/`ProcessingRecoveryConcurrencyIT` 둘 다 `PgCancelPort`를 `@MockitoBean`으로 모킹하므로 D-01 정정의 영향 자체를 받지 않음(구조적으로 격리됨) |

회귀 없음.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `PgCancelHttpClient.getStatus()/cancel()` | 실 Toss Payments 계약 구현 | ✓ VERIFIED | `GET/POST /v1/payments/{paymentKey}[/cancel]` → `mapStatus()`/`mapCancelResponse()` |
| `PgCancelPort.getStatus(paymentKey, cancelAmount)` | 2-arg 시그니처(cancels[] 금액 매칭용) | ✓ VERIFIED | 인터페이스 + 두 구현체(`PgCancelHttpClient`, `MockPgCancelClient`) 일치 확인 |
| `TossPaymentResponse`/`TossCancel` (record DTO) | Toss 응답 매핑, ignoreUnknown | ✓ VERIFIED | `infrastructure/http/dto/` 존재, `@JsonIgnoreProperties(ignoreUnknown=true)` 확인 |
| `CancelRequest.pgTransactionKey` + `assignPgTransactionKey()` | domain 필드 + assign 메서드 | ✓ VERIFIED | 소스 확인, `saveTx3` 직전 세팅 지점 2곳(`CancelPaymentService`, `ProcessingRecoveryService`) 확인 |
| Flyway `V13__add_pg_transaction_key_to_cancel_request.sql` | `pg_transaction_key` 컬럼 추가 | ✓ VERIFIED | 파일 존재, `ALTER TABLE cancel_request ADD COLUMN pg_transaction_key VARCHAR(64) NULL` |
| `RiskManagementHttpClient.isCharged()` | `/internal/cancel-limit/check` 실 구현 | ✓ VERIFIED | `CheckChargeResponseDto.charged()` 매핑, D-01과 무관 — 변경 없음 |
| `CancelRaceIdempotencyIT`/`ProcessingRecoveryConcurrencyIT` | Testcontainers 동시성 IT | ✓ VERIFIED | 1/1, 3/3 pass(본 세션 직접 재실행) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `PgCancelHttpClient.getStatus` 응답 | `ProcessingRecoveryService.recoverOne` 분기 | `PgCancelResult.isApproved/isFailed/isPending` | ✓ WIRED | D-01 정정 후에도 `PgCancelResult` 계약 불변, 분기 로직 그대로 |
| `PgCancelHttpClient.cancel/getStatus` `transactionKey` | `CancelRequest.assignPgTransactionKey` → `saveTx3` | 호출 직전 세팅(재조회 없음) | ✓ WIRED | 소스+테스트 확인, TX3 불변식 미훼손 |
| `saveTx1` `DataIntegrityViolationException` | `handleExistingRequest` | catch → 재조회 → 상태 스위치 | ✓ WIRED | D-01과 무관, 변경 없음, IT 재실행으로 확인 |
| `incrementPgRetryCount`/`compareAndSetFailed` 원자 UPDATE | `retryPgCancel`/`compensateAndFail` | 재조회 후 비교 | ✓ WIRED | D-01과 무관, 변경 없음, IT 재실행으로 확인 |

### Behavioral Spot-Checks (본 세션에서 직접 실행, `--rerun`으로 캐시 무시)

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| RESIL-01 단위 테스트(D-01 정정 반영본) | `./gradlew :payment-service:test --tests "*PgCancelHttpClientTest" --tests "*RiskManagementHttpClientTest" --tests "*ProcessingRecoveryServiceTest" --tests "*CancelPaymentServiceTest" --tests "*CancelRequestTest" --tests "*CancelRequestRepositoryImplTest" --tests "*PgCancelHttpClientCardinalityTest" --rerun` | 20/20, 11/11, 13/13, 17/17, 21/21, 7/7, 1/1 — 0 failures/errors (XML 리포트 직접 파싱해 확인) | ✓ PASS |
| RESIL-02/03 동시성 IT | `./gradlew :payment-service:test --tests "*CancelRaceIdempotencyIT" --tests "*ProcessingRecoveryConcurrencyIT" --rerun` | 1/1, 3/3 — 0 failures/errors | ✓ PASS |
| 전체 회귀(풀스위트, 1회만 실행) | `./gradlew :payment-service:test` | BUILD SUCCESSFUL — 230 tests, 0 skipped, 0 failures, 0 errors (test-results XML 전수 집계) | ✓ PASS |
| 스텁/디버트 마커 부재 확인 | 27개 phase-touched 파일 전체에 `xargs grep -nE "TBD\|FIXME\|XXX\|TODO\|HACK\|PLACEHOLDER\|UnsupportedOperationException"` | 0건 (grep exit 1) | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| RESIL-01 | 02-01-PLAN.md, 02-04-SUMMARY.md | getStatus/isCharged 스텁 제거 + stale PROCESSING 자동복구 + D-01 실 PG 계약 정정 | ✓ SATISFIED | Truths 1-3, 8-9 |
| RESIL-02 | 02-02-PLAN.md | 멀티파드 동시 취소 멱등 응답 | ✓ SATISFIED | Truths 4-5 |
| RESIL-03 | 02-03-PLAN.md | ProcessingRecovery 동시성 가드 | ✓ SATISFIED | Truths 6-7 |

REQUIREMENTS.md Traceability 표(line 103-105): RESIL-01/02/03 모두 `Complete`로 갱신 확인(이전 검증에서 지적된 RESIL-01 "Pending" 문서 불일치는 해소됨). No orphaned requirements.

### Anti-Patterns Found

없음. 27개 phase-touched 소스/테스트 파일(02-01~02-04 SUMMARY의 key-files 합집합) 전체에 `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER`/`UnsupportedOperationException` grep 0건.

### Human Verification Required

없음. 이전 검증의 유일한 human_needed 항목(D-01 ASSUMED PG 계약 대조)은 사용자가 실 Toss Payments 문서를 제공하고 코드가 정정됨으로써 RESOLVED(02-UAT.md status: resolved). 잔여 항목은 배포 설정(운영 `external.pg.url` 전환 + Basic 인증 헤더 추가, 02-04-SUMMARY "Config Note")뿐이며, 이는 코드 검증 갭이 아니라 실 Toss 연동 배포 시점의 인프라/설정 작업이므로 phase 코드 목표 달성 여부와 무관하다.

### Gaps Summary

없음. RESIL-01/02/03 3개 요구사항 모두 소스에서 실 구현 확인, 각 must-have truth를 뒷받침하는 단위/통합 테스트를 본 검증 세션에서 `--rerun`(캐시 무시)으로 직접 재실행해 green을 재확인했다. 전체 스위트(230 tests, 0 failures/errors)도 본 세션에서 1회 직접 실행해 확인했다. D-01(PG 계약) 정정은 RESIL-02/03 로직을 구조적으로 건드리지 않았고(두 IT 모두 PgCancelPort를 모킹), RESIL-01의 getStatus/cancel/transactionKey 저장 경로는 실 Toss 계약으로 정확히 재배선됐다. 이전 검증의 human_needed 사유(D-01 미대조)가 해소되어 상태를 `passed`로 전환한다.

---

_Verified: 2026-07-29T14:40:00Z_
_Verifier: Claude (gsd-verifier)_
