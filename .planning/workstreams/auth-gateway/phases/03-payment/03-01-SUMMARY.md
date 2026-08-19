---
phase: 03-payment
plan: 01
subsystem: auth
tags: [authorization, rbac, trusted-headers, payment-cancel, spring, mockmvc]

# Dependency graph
requires:
  - phase: 02-gateway
    provides: 게이트웨이가 검증 후 재주입하는 X-User-* 신뢰 헤더 (payment 는 이를 무검증 신뢰)
provides:
  - CancelController presentation pre-check 인가 (취소 코어 호출 이전 403)
  - CancelAuthorizer 순수 POJO 판정 규칙 (ADMIN 전체 / MERCHANT 자가맹점 / USER·누락 403)
  - AuthenticatedUser 신뢰 헤더 carrier record
  - CancelAuthorizationUseCase/Service read-only 오케스트레이션 (MERCHANT 경로만 payment 1회 로드)
  - CancelNotAuthorizedException (기존 FORBIDDEN_PAYMENT 403 재사용)
affects: [03-payment-plan-02, k3s NetworkPolicy 배포 게이트, merchant self-cancel 경로]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "presentation pre-check 인가: cancel() 호출 이전 read-only 판정, 코어 byte-for-byte 무변경"
    - "domain 순수 POJO 판정 (primitive 만) — Spring/JPA 의존 없이 인가 규칙 격리"
    - "신뢰 헤더 무검증 신뢰 + NetworkPolicy 이관 (JWT 재검증·spring-security 미도입)"

key-files:
  created:
    - payment-service/src/main/java/com/example/payment/common/exception/domain/CancelNotAuthorizedException.java
    - payment-service/src/main/java/com/example/payment/domain/service/CancelAuthorizer.java
    - payment-service/src/main/java/com/example/payment/application/authz/AuthenticatedUser.java
    - payment-service/src/main/java/com/example/payment/application/usecase/CancelAuthorizationUseCase.java
    - payment-service/src/main/java/com/example/payment/application/service/CancelAuthorizationService.java
  modified:
    - payment-service/src/main/java/com/example/payment/presentation/controller/CancelController.java
    - payment-service/src/test/java/com/example/payment/presentation/controller/CancelControllerTest.java

key-decisions:
  - "인가는 CancelController pre-check — cancelPaymentUseCase.cancel() 호출 이전 403 (D-P3-1)"
  - "신규 에러코드 없이 기존 FORBIDDEN_PAYMENT(403) 재사용 + GlobalExceptionHandler 무변경 (D-P3-4)"
  - "domain CancelAuthorizer 는 primitive 만 받는 순수 POJO — 참조 self-cancel 분기 미채택(USER→403) (D-P3-2)"
  - "ADMIN 은 payment 로드 생략, MERCHANT 경로만 findByPaymentKey 1회 read-only 로드 (D-P3-5)"
  - "비정상 X-Merchant-Id 는 NumberFormatException 을 null 로 흡수 → 500 대신 403 (T-03-04)"
  - "JWT 재검증·spring-security 의존 미도입, @RequestHeader 직접 읽기 (D-P3-3)"

patterns-established:
  - "취소 코어 불변 게이트: merge-base(HEAD, origin/main) 대비 코어 파일 diff 부재를 CI 게이트화"
  - "tracer e2e: 실 authz 서비스 배선 + 코어 mock 으로 무권한 차단을 end-to-end 증명"

requirements-completed: [AUTHZ-01]

coverage:
  - id: D1
    description: "무권한(USER) 취소가 취소 코어 진입 전 403 으로 차단되고 cancel() 은 호출되지 않는다"
    requirement: "AUTHZ-01"
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/presentation/controller/CancelControllerTest.java#user_role_cancel_forbidden_before_core"
        status: pass
    human_judgment: false
  - id: D2
    description: "인가는 신뢰 헤더 role 만으로 판정, JWT 재검증·spring-security 의존 없음"
    requirement: "AUTHZ-01"
    verification:
      - kind: other
        ref: "grep 'spring-security|jjwt' 부재 + CancelAuthorizer/AuthenticatedUser primitive-only POJO"
        status: pass
    human_judgment: false
  - id: D3
    description: "취소 실행 코어 파일이 이 phase diff 에 한 줄도 나타나지 않는다"
    requirement: "AUTHZ-01"
    verification:
      - kind: other
        ref: "BASE=$(git merge-base HEAD origin/main); git diff --name-only $BASE -- payment-service/src/main | grep -E '(CancelPaymentService|CancelTxWriter|CancelDomainService|CancelHistoryRecorder)\\.java|/scheduler/|/messaging/' (empty)"
        status: pass
    human_judgment: false
  - id: D4
    description: "기존 멱등성 5종 테스트 무회귀 (setUp authz mock no-op)"
    verification:
      - kind: integration
        ref: "payment-service/.../CancelControllerTest.java (5 idempotency tests)"
        status: pass
    human_judgment: false

# Metrics
duration: 8 min
completed: 2026-07-30
status: complete
---

# Phase 03 Plan 01: 취소 인가 presentation pre-check (tracer) Summary

**취소 코어를 byte-for-byte 무변경으로 둔 채 CancelController 앞단에 신뢰 헤더 role 기반 인가를 얹어 무권한(USER) 취소를 취소 플로우 진입 전 403 으로 차단하는 tracer 슬라이스**

## Performance

- **Duration:** 8 min
- **Started:** 2026-07-30T06:06:00Z
- **Completed:** 2026-07-30T06:15:00Z
- **Tasks:** 2
- **Files modified:** 7 (5 created, 2 modified)

## Accomplishments
- 취소 인가를 CancelController pre-check 로 배선 — `cancelPaymentUseCase.cancel()` 호출 이전에 판정, 실패 시 기존 GlobalExceptionHandler 가 403 매핑
- domain `CancelAuthorizer` 순수 POJO 판정 규칙: ADMIN 전체 허용 / MERCHANT 자가맹점 일치 허용 / USER·role 누락·불일치·merchantId 누락 403 (참조 브랜치 self-cancel 분기 미채택)
- `CancelAuthorizationService` read-only 오케스트레이션: ADMIN 은 payment 로드 생략, MERCHANT 경로만 `findByPaymentKey` 1회 로드, 비정상 X-Merchant-Id 는 null 흡수(403)
- USER→403 end-to-end 테스트로 무권한 차단을 실 authz 서비스 배선으로 증명 (cancel never-invoked)
- 취소 코어 불변 게이트 통과 — merge-base(HEAD, origin/main) 대비 코어 실행/TX writer/도메인/이력/스케줄러/messaging 파일 diff 부재

## Task Commits

각 task 원자 커밋 (실 소스만; `.planning/` 은 gitignore 라 커밋 제외):

1. **Task 1: 인가 클래스 생성 (예외 PORT + domain POJO + read-only 오케스트레이션)** - `d50237b` (feat)
2. **Task 2: CancelController pre-check 배선 + USER→403 e2e (취소 코어 불변 게이트)** - `0b58d3c` (feat)

_SUMMARY/STATE 등 `.planning/` 산출물은 gitignore 정책상 미커밋._

## Files Created/Modified
- `common/exception/domain/CancelNotAuthorizedException.java` - 기존 FORBIDDEN_PAYMENT(403) 재사용 PORT
- `domain/service/CancelAuthorizer.java` - 순수 POJO 인가 판정 (primitive 만, Spring/JPA import 0)
- `application/authz/AuthenticatedUser.java` - 신뢰 헤더 carrier record (판정 로직 없음)
- `application/usecase/CancelAuthorizationUseCase.java` - 인가 유스케이스 인터페이스
- `application/service/CancelAuthorizationService.java` - read-only 오케스트레이션 + 신뢰 경계 javadoc
- `presentation/controller/CancelController.java` - @RequestHeader 3종 + cancel() 최상단 authorize pre-check (기존 로직 무변경)
- `presentation/controller/CancelControllerTest.java` - setUp authz mock no-op + USER→403 tracer e2e

## Decisions Made
- 인가 판정을 domain POJO 로 격리하고 오케스트레이션(payment 로드)은 application 서비스로 분리 — domain 은 primitive 만 받아 Spring/JPA 의존 0
- `CancelAuthorizationService` 는 `CancelAuthorizer` 를 필드 `new CancelAuthorizer()` 로 직접 인스턴스화 (무상태 판정기, 빈 등록 불필요) — 테스트에서 실 판정기 배선을 단순화
- 신뢰 경계 가정(T-03-01, high, transfer)은 코드가 아닌 k3s NetworkPolicy 로 이관하고 `CancelAuthorizationService` javadoc 에 명시 (Plan 02 배포 게이트)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None. (기존 테스트가 사용하던 `MappingJackson2HttpMessageConverter` deprecation 경고는 사전 존재 패턴을 헬퍼에 그대로 답습한 것으로, 범위 밖 — 수정하지 않음.)

## Threat Flags

없음 — plan `<threat_model>` 범위 밖의 신규 보안 표면 도입 없음. T-03-03(USER→403)·T-03-04(비정상 merchantId 흡수) 완화는 구현·테스트 완료. T-03-01(payment 직접 도달 스푸핑)은 설계대로 배포 시점 NetworkPolicy 이관(transfer) — Plan 02 게이트.

## User Setup Required
None - 외부 서비스 설정 불필요. (단, 프로덕션 신뢰 경계 T-03-01 은 배포 시 k3s NetworkPolicy 필수 — Plan 02 에서 다룸.)

## Next Phase Readiness
- MERCHANT/ADMIN 허용 경로·비정상 헤더 흡수 로직은 구현 완료, tracer 는 USER→403 한 경로만 e2e 관통 — Plan 02 에서 MERCHANT 일치/불일치·ADMIN 전체 허용 경로 확장 테스트 예정
- 취소 코어 불변 게이트를 CI 에 상시 편입 권장 (merge-base 기반)
- 배포 전 필수: k3s NetworkPolicy 로 payment(8080) ingress 를 게이트웨이 파드로 제한 (T-03-01)

## Self-Check: PASSED

---
*Phase: 03-payment*
*Completed: 2026-07-30*
