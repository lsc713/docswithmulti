---
phase: 03-cancel-restore
plan: 04
subsystem: api
tags: [spring-boot, rest, jpa, testcontainers, payment]

requires:
  - phase: 02-payment-reserve
    provides: PaymentController(create), PaymentRepository.findByPaymentKey, Testcontainers 통합테스트 조립
provides:
  - "GET /v1/payments/{paymentKey}/exists 경량 존재확인 read 엔드포인트 (RST-03)"
  - "PaymentRepository.existsByPaymentKey 조회 계약"
affects: [03-05 product orphan 예약 복구 스케줄러]

tech-stack:
  added: []
  patterns:
    - "read-only 조회 usecase 분리: PaymentExistsQuery 인터페이스 + @Service 위임 (create/cancel 오케스트레이터와 동형)"
    - "exists 계열은 JpaRepository 파생쿼리(existsBy*)로 count 없이 경량 조회"

key-files:
  created:
    - payment-service/src/main/java/com/example/payment/application/usecase/PaymentExistsQuery.java
    - payment-service/src/main/java/com/example/payment/application/service/PaymentExistsQueryService.java
    - payment-service/src/main/java/com/example/payment/presentation/dto/PaymentExistsResponse.java
    - payment-service/src/test/java/com/example/payment/integration/PaymentExistsEndpointIntegrationTest.java
  modified:
    - payment-service/src/main/java/com/example/payment/presentation/controller/PaymentController.java
    - payment-service/src/main/java/com/example/payment/application/interfaces/PaymentRepository.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentJpaRepository.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentRepositoryImpl.java

key-decisions:
  - "존재/미존재 모두 200 + {exists:bool} 바디 — 404 대신 명시적 boolean으로 orphan 판별(계약 단순화)"
  - "existsByPaymentKey는 JPA 파생쿼리로 구현(findByPaymentKey().isPresent() 대비 엔티티 로드 없음)"

patterns-established:
  - "read 전용 조회는 별도 usecase 인터페이스 + @Service로 분리, 취소/생성 오케스트레이터와 격리"

requirements-completed: [RST-03]

coverage:
  - id: D1
    description: "GET /v1/payments/{paymentKey}/exists 가 커밋 payment 존재 시 {exists:true}, 미존재 시 {exists:false} 200 반환 (RST-03)"
    requirement: RST-03
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/PaymentExistsEndpointIntegrationTest.java#existingPaymentKey_returnsTrue"
        status: pass
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/PaymentExistsEndpointIntegrationTest.java#unknownPaymentKey_returnsFalse"
        status: pass
    human_judgment: false
  - id: D2
    description: "신규 조회 엔드포인트가 취소 코어 로직을 변경하지 않음 — 기존 취소 통합테스트 무회귀 그린 (D-P3-5)"
    verification:
      - kind: integration
        ref: "./gradlew :payment-service:test (전체 모듈 스위트 BUILD SUCCESSFUL, CancelFlowIntegrationTest 포함)"
        status: pass
    human_judgment: false

duration: 8min
completed: 2026-07-31
status: complete
---

# Phase 3 Plan 4: Payment 존재확인 엔드포인트 Summary

**GET /v1/payments/{paymentKey}/exists — orphan 예약 복구(03-05)가 조회할 payment-service 경량 read 계약. 취소 코어 무변경.**

## Performance

- **Duration:** 8 min
- **Started:** 2026-07-31T02:30:25Z
- **Completed:** 2026-07-31T02:38:28Z
- **Tasks:** 2
- **Files modified:** 8 (4 created, 4 modified)

## Accomplishments
- `GET /v1/payments/{paymentKey}/exists` 신규 @GetMapping — 커밋 payment 존재 여부를 `{exists:bool}` 200으로 반환 (RST-03)
- read 전용 경로 격리: `PaymentExistsQuery`(usecase) + `PaymentExistsQueryService`(@Service) + `PaymentRepository.existsByPaymentKey`(JPA 파생쿼리)
- Testcontainers MySQL 통합테스트 2케이스(존재→true, 미존재→false) 그린
- 취소 코어(CancelTxWriter/CancelPaymentService/스케줄러/outbox)·결제 생성 로직 완전 무변경 — 전체 모듈 스위트 무회귀

## Task Commits

1. **Task 1: existsByPaymentKey 조회 + exists 엔드포인트** - `8b90312` (feat)
2. **Task 2: exists 통합테스트 + 취소 코어 무회귀** - `8fe5671` (test)

## Files Created/Modified
- `application/usecase/PaymentExistsQuery.java` - read 전용 usecase 인터페이스 (boolean exists)
- `application/service/PaymentExistsQueryService.java` - @Service, repository.existsByPaymentKey 위임
- `presentation/dto/PaymentExistsResponse.java` - record(boolean exists)
- `presentation/controller/PaymentController.java` - @GetMapping("/{paymentKey}/exists") 추가 (create/cancel 메서드 무변경)
- `application/interfaces/PaymentRepository.java` - existsByPaymentKey 계약 추가
- `infrastructure/persistence/PaymentJpaRepository.java` - existsByPaymentKey 파생쿼리
- `infrastructure/persistence/PaymentRepositoryImpl.java` - JPA 위임
- `integration/PaymentExistsEndpointIntegrationTest.java` - Testcontainers 통합테스트

## Decisions Made
- 존재/미존재 모두 HTTP 200 + `{exists:bool}` 바디 (404 대신 명시적 boolean). orphan 판별 계약을 단순화하고 "미존재"를 정상 응답으로 취급.
- `existsByPaymentKey`는 JPA 파생쿼리(exists)로 구현 — 엔티티 로드/count 없이 경량 조회.

## Deviations from Plan

None - plan executed exactly as written.

Plan은 Task 1 verify가 Task 2에서 만드는 테스트 파일을 참조하는 구조여서, Task 1 커밋 전 통합테스트를 함께 작성해 verify를 통과시킨 뒤 구현(Task 1)·테스트(Task 2)를 논리 순서로 원자 커밋함. 산출물·검증 결과는 plan과 동일.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- 03-05 product orphan 예약 복구 스케줄러가 의존할 `GET /v1/payments/{paymentKey}/exists` 조회 계약 준비 완료.
- 취소 코어 불변 확인(전체 `:payment-service:test` BUILD SUCCESSFUL).

## Self-Check: PASSED

- 파일 확인: 4개 created + 4개 modified 모두 존재 (git diff --stat 확인)
- 커밋 확인: `8b90312`(feat), `8fe5671`(test) git log 존재
- verify 확인: PaymentExistsEndpointIntegrationTest + CancelFlowIntegrationTest + 전체 모듈 스위트 그린

---
*Phase: 03-cancel-restore*
*Completed: 2026-07-31*
