---
phase: 01-hold
plan: 02
subsystem: settlement-service
tags: [reserve, reserve-config, rest-api, hold, exceptions]
requires:
  - "01-01: MerchantReserveConfigRepository(findConfig/upsert), ReserveRepository(findBySettlementId), reserve/merchant_reserve_config 테이블(V3)"
provides:
  - "PUT/GET /v1/settlements/reserve-config/{merchantId} (RCFG-01/02)"
  - "GET /v1/settlements/{id}/reserve (HOLD-04)"
  - "settlement ErrorCode: INVALID_RESERVE_CONFIG·RESERVE_CONFIG_NOT_FOUND·RESERVE_NOT_FOUND"
affects:
  - "docs/error-catalog.md (settlement 섹션 3코드)"
tech-stack:
  added: []
  patterns:
    - "커스텀 예외 → BusinessException 상속 → GlobalExceptionHandler generic 핸들러 자동 매핑(전용 핸들러/ResponseEntity 없음)"
    - "PUT-upsert + GET-orElseThrow(404) 컨트롤러 클론(SettlementConfig/PayoutAccount/PayoutController 미러)"
key-files:
  created:
    - settlement-service/src/main/java/com/example/settlement/application/exception/InvalidReserveConfigException.java
    - settlement-service/src/main/java/com/example/settlement/application/exception/ReserveConfigNotFoundException.java
    - settlement-service/src/main/java/com/example/settlement/application/exception/ReserveNotFoundException.java
    - settlement-service/src/main/java/com/example/settlement/application/service/ReserveConfigService.java
    - settlement-service/src/main/java/com/example/settlement/application/service/ReserveQueryService.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/ReserveConfigController.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/ReserveQueryController.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/ReserveConfigRequest.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/ReserveConfigResponse.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/ReserveResponse.java
    - settlement-service/src/test/java/com/example/settlement/integration/ReserveConfigIntegrationTest.java
    - settlement-service/src/test/java/com/example/settlement/integration/ReserveQueryIntegrationTest.java
  modified:
    - settlement-service/src/main/java/com/example/settlement/common/exception/ErrorCode.java
    - docs/error-catalog.md
decisions:
  - "ReserveConfigIntegrationTest 를 Task 2 대신 Task 1 에 배치 — Task 1 <verify> 가 이 테스트를 참조하므로 커밋 원자성/verify 실행성 확보(파일 배정 경미 이동)"
  - "검증 실패는 IllegalArgumentException(SettlementConfigService 방식) 대신 InvalidReserveConfigException(400) — 가드레일이 명시한 커스텀 예외 컨벤션"
metrics:
  duration: ~40m active build/test (wall ~3h with idle)
  completed: 2026-08-06
status: complete
---

# Phase 01 Plan 02: Reserve API Layer (RCFG-01/02 · HOLD-04) Summary

01-01 이 만든 `MerchantReserveConfigRepository`·`ReserveRepository` 포트를 소비하는 REST 표면 — 유보 정책 PUT/GET upsert·조회와 유보 상태 GET — 을 서비스+컨트롤러+DTO+커스텀 예외 3종만으로 얹었다. 신규 영속/빈/마이그레이션 0.

## Tasks

- **Task 1 (7856c1c)**: ErrorCode 3항목 + 예외 3종 + ReserveConfigService(setConfig 검증→upsert / getConfig 404) + ReserveConfigController PUT·GET + DTO 2종 + error-catalog 3코드 + ReserveConfigIntegrationTest.
- **Task 2 (18be178)**: ReserveQueryService(findBySettlementId→404) + ReserveQueryController GET /{id}/reserve + ReserveResponse DTO + ReserveQueryIntegrationTest.

## What was built

### 예외 3종 (settlement application.exception, BusinessException 상속)
- `InvalidReserveConfigException(String reason)` → `INVALID_RESERVE_CONFIG` (400)
- `ReserveConfigNotFoundException(long merchantId)` → `RESERVE_CONFIG_NOT_FOUND` (404)
- `ReserveNotFoundException(long settlementId)` → `RESERVE_NOT_FOUND` (404)

3종 모두 generic `BusinessException` 핸들러(GlobalExceptionHandler:32-38)가 `{code,message}` 를 errorCode.httpStatus 로 자동 매핑 — **전용 @ExceptionHandler 추가 없음, GlobalExceptionHandler 무수정.**

### ErrorCode (settlement 자체 enum)
`INVALID_RESERVE_CONFIG`(400), `RESERVE_CONFIG_NOT_FOUND`(404), `RESERVE_NOT_FOUND`(404) 3항목 추가. payment/order ErrorCode 무관.

### 컨트롤러/라우트
- `PUT /v1/settlements/reserve-config/{merchantId}` `{reserveRate,reserveCap,holdDays}` → 멱등 upsert (검증: rate 0≤r<1·scale≤4, cap≥0, holdDays≥0; 위반 400)
- `GET /v1/settlements/reserve-config/{merchantId}` → 정책 반환 / 미설정 404
- `GET /v1/settlements/{id}/reserve` → 유보 상태(id/status/amount/holdUntil/transferRef) / 미존재 404

### error-catalog.md
settlement(지급) 섹션에 3코드 1:1 추가 (400 표에 INVALID_RESERVE_CONFIG, 404 표에 RESERVE_CONFIG_NOT_FOUND·RESERVE_NOT_FOUND).

## Deviations from Plan

**1. [Rule 3 - task ordering] ReserveConfigIntegrationTest 를 Task 1 로 이동**
- **Found during:** Task 1 실행 (verify 준비)
- **Issue:** 플랜의 Task 1 `<verify>` 는 `*ReserveConfigIntegrationTest` 를 실행하지만, 파일은 Task 2 `<files>` 에 배정돼 있어 Task 1 verify 가 실행 불가.
- **Fix:** ReserveConfigIntegrationTest 를 Task 1 에서 작성·커밋(각 태스크의 verify 가 독립 실행 가능·커밋 원자성 유지). ReserveQueryIntegrationTest 는 계획대로 Task 2.
- **Impact:** 파일-태스크 배정만 경미 이동, 산출물/코드 동일.

**2. [가드레일 준수] 검증 예외를 IllegalArgumentException → InvalidReserveConfigException 로**
- SettlementConfigService.setRate 는 IllegalArgumentException 을 던지지만, 가드레일/플랜이 명시한 커스텀 예외(INVALID_RESERVE_CONFIG 400) 를 사용. 클론하되 예외 타입만 컨벤션에 맞춤.

## Verification

- `ReserveConfigIntegrationTest`: PUT→GET round-trip(DB 왕복)·재PUT overwrite·미설정 GET 404·검증 400 5종(rate<0/rate≥1/scale>4/cap<0/holdDays<0) + `{code}` 바디 확인 — green.
- `ReserveQueryIntegrationTest`: approve 후 GET reserve 200(status HELD·amount 1000.00·transferRef RSV-{id}·holdUntil)·미존재 404 RESERVE_NOT_FOUND — green.
- **전체 `:settlement-service:test`: BUILD SUCCESSFUL, 70 tests / 19 classes, 전부 green** (신규 6 test methods 포함, 회귀 0).

## Settlement-only confirmation

payment/order/product/merchant 무변경. 신규 마이그레이션 0(V3 의 reserve/merchant_reserve_config 재사용). shared/타모듈 ErrorCode 무편집. 01-01 영속/approve 로직 무변경. GlobalExceptionHandler 무수정. 신규 패키지 0.

## Known Stubs

None.

## Self-Check: PASSED

- 12 created files + 2 modified files 전부 디스크 존재 확인.
- 커밋 7856c1c·18be178 git log 존재 확인.
- 전체 테스트 스위트 70/70 green.
