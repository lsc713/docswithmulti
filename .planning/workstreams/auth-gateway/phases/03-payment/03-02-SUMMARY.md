---
phase: 03-payment
plan: 02
subsystem: auth
tags: [authorization, rbac, trusted-headers, payment-cancel, test, docs]

# Dependency graph
requires:
  - phase: 03-payment
    plan: 01
    provides: CancelAuthorizer(domain POJO) + CancelAuthorizationService + CancelController pre-check + AuthenticatedUser
provides:
  - CancelAuthorizerTest 인가 매트릭스 6종 (domain 순수 단위, exhaustive)
  - CancelAuthorizationServiceTest 오케스트레이션 단위 (ADMIN 로드 생략 / MERCHANT 로드·비교 / 404 / 비정상 헤더 403 흡수)
  - CancelControllerTest ADMIN pass-through (SC#1 — 200 + cancel 1회 호출)
  - domain-rules.md §8 취소 인가(AUTHZ-01) 정책 원본 + NetworkPolicy 배포 게이트
  - api-spec.md 취소 API Response 403 note
affects: [k3s NetworkPolicy 배포 게이트, phase 03 verification]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "domain 순수 단위 테스트: Spring/Mockito 없이 primitive 인자로 인가 매트릭스 exhaustive 검증"
    - "오케스트레이션 단위: @InjectMocks + PaymentRepository mock 으로 로드 생략/1회 로드 검증(never/times)"
    - "standaloneSetup + 실 authz 서비스 배선(mock 코어)로 ADMIN pass-through 컨트롤러 증명"

key-files:
  created:
    - payment-service/src/test/java/com/example/payment/domain/service/CancelAuthorizerTest.java
    - payment-service/src/test/java/com/example/payment/application/service/CancelAuthorizationServiceTest.java
  modified:
    - payment-service/src/test/java/com/example/payment/presentation/controller/CancelControllerTest.java
    - docs/domain-rules.md
    - docs/api-spec.md

key-decisions:
  - "인가 매트릭스 실패 경로는 errorCode == FORBIDDEN_PAYMENT(403) 까지 assert (에러코드 재사용 회귀 방어)"
  - "@InjectMocks 로 CancelAuthorizationService 배선 — strict stub 회피 위해 로드 미사용 케이스(ADMIN/USER)는 findByPaymentKey 미stub"
  - "ADMIN pass-through 는 기존 mockMvcWithRealAuthz() 헬퍼 재사용 — 실 authz + mock 코어로 SC#1 증명"
  - "문서는 append-only — domain-rules.md §8 신규 섹션 + api-spec.md 403 신규 섹션, 기존 취소 규칙/응답 불변"

requirements-completed: [AUTHZ-01]

coverage:
  - id: D1
    description: "인가 매트릭스 6종(ADMIN 허용 / MERCHANT match 허용 / MERCHANT mismatch·merchantId 누락 403 / USER 403 / role 누락 403)이 domain 단위로 exhaustive 검증 (D-P3-2)"
    requirement: "AUTHZ-01"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/domain/service/CancelAuthorizerTest.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "MERCHANT 경로만 payment read-only 로드, ADMIN 은 로드 생략 (D-P3-5)"
    requirement: "AUTHZ-01"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/CancelAuthorizationServiceTest.java#admin_skips_payment_load / merchant_match_loads_once_and_passes"
        status: pass
    human_judgment: false
  - id: D3
    description: "ADMIN 취소가 인가 통과 후 기존 취소 플로우로 진입(cancel 1회 호출)이 컨트롤러 레벨에서 증명 (SC#1)"
    requirement: "AUTHZ-01"
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/presentation/controller/CancelControllerTest.java#admin_role_cancel_passes_through_to_core"
        status: pass
    human_judgment: false
  - id: D4
    description: "비정상 X-Merchant-Id 는 500 대신 403 으로 흡수 (T-03-04)"
    requirement: "AUTHZ-01"
    verification:
      - kind: unit
        ref: "CancelAuthorizationServiceTest#non_numeric_merchant_id_absorbed_to_403"
        status: pass
    human_judgment: false
  - id: D5
    description: "취소 실행 코어 파일이 이 phase diff 에 부재 (코어 byte-for-byte 불변)"
    requirement: "AUTHZ-01"
    verification:
      - kind: other
        ref: "BASE=$(git merge-base HEAD origin/main); git diff --name-only $BASE -- payment-service/src/main | grep -E '(CancelPaymentService|CancelTxWriter|CancelDomainService|CancelHistoryRecorder)\\.java|/scheduler/|/messaging/' (empty)"
        status: pass
    human_judgment: false
  - id: D6
    description: "기존 payment-service 스위트(멱등성 5종 등) 무회귀"
    requirement: "AUTHZ-01"
    verification:
      - kind: integration
        ref: "./gradlew :payment-service:test (BUILD SUCCESSFUL)"
        status: pass
    human_judgment: false

# Metrics
duration: 5 min
completed: 2026-07-30
status: complete
---

# Phase 03 Plan 02: 취소 인가 매트릭스 exhaustive 검증 + 정책 문서화 Summary

**Plan 01 tracer 가 관통시킨 인가 경로를 매트릭스 6종 domain 단위 + 오케스트레이션(로드/404/생략) 단위 + ADMIN pass-through 컨트롤러 증명으로 exhaustive 하게 못박고, 취소 인가 정책(AUTHZ-01)과 NetworkPolicy 배포 게이트를 domain-rules.md 원본에 명문화 — 취소 코어는 byte-for-byte 불변**

## Performance

- **Duration:** 5 min
- **Started:** 2026-07-30T06:18:39Z
- **Completed:** 2026-07-30T06:23:42Z
- **Tasks:** 3
- **Files modified:** 5 (2 created, 3 modified)

## Accomplishments
- `CancelAuthorizerTest` — 인가 매트릭스 6종을 순수 POJO 단위로 exhaustive 검증(D-P3-2): ADMIN 전체 허용 / MERCHANT match 허용 / MERCHANT mismatch·merchantId 누락 403 / USER 소유자여도 403 / role 누락 403, 실패 경로 errorCode `FORBIDDEN_PAYMENT`(403)까지 assert
- `CancelAuthorizationServiceTest` — 오케스트레이션 단위(@InjectMocks + PaymentRepository mock): ADMIN `findByPaymentKey` never(로드 생략, D-P3-5) / MERCHANT match 1회 로드 후 통과 / mismatch 403 / payment 없음 404 / USER 로드 없이 403 / 비정상 X-Merchant-Id 403 흡수(T-03-04)
- `CancelControllerTest` ADMIN pass-through 확장(SC#1): 실 authz 서비스 배선 + mock 코어로 X-User-Role: ADMIN → 200 + `cancelPaymentUseCase.cancel()` 1회 호출 증명 — 권한 role 취소가 인가 통과 후 기존 취소 플로우로 진입함을 컨트롤러 레벨에서 증명
- `docs/domain-rules.md §8 취소 인가(AUTHZ-01)` append: 판정 매트릭스·신뢰 헤더 정책(JWT 재검증 없음)·NetworkPolicy 배포 게이트(D-P3-6, 미이관 시 X-User-Role 위조로 전량 취소 가능 리스크 명문화)
- `docs/api-spec.md` 취소 API Response 403 FORBIDDEN_PAYMENT note append (기존 error-catalog 재사용, 신규 코드 없음)
- 취소 코어 불변 게이트 통과 — merge-base(HEAD, origin/main) 대비 코어 실행/TX writer/도메인 서비스/이력/스케줄러/messaging 파일 diff 부재. 풀 스위트 `./gradlew :payment-service:test` 초록(멱등성 5종 등 무회귀)

## Task Commits

각 task 원자 커밋 (실 소스·문서만; `.planning/` 은 gitignore 라 커밋 제외):

1. **Task 1: CancelAuthorizer 인가 매트릭스 6종 (domain 순수 단위)** - `590591e` (test)
2. **Task 2: CancelAuthorizationService 오케스트레이션 단위 (로드/404/위임/생략)** - `804f476` (test)
3. **Task 3: CancelController ADMIN pass-through(SC#1) + 인가 정책 문서화** - `29ccc30` (test)

_SUMMARY/STATE 등 `.planning/` 산출물은 gitignore 정책상 미커밋._

## Files Created/Modified
- `domain/service/CancelAuthorizerTest.java` (created) - 인가 매트릭스 6종 순수 POJO 단위 (Spring/Mockito 없음)
- `application/service/CancelAuthorizationServiceTest.java` (created) - 오케스트레이션 단위 6종 (@ExtendWith(MockitoExtension) + @InjectMocks)
- `presentation/controller/CancelControllerTest.java` (modified) - ADMIN pass-through 테스트 추가 (기존 멱등성 5종·USER tracer e2e 불변)
- `docs/domain-rules.md` (modified) - §8 취소 인가(AUTHZ-01) 섹션 append
- `docs/api-spec.md` (modified) - 취소 API Response 403 note append

## Decisions Made
- 실패 경로를 예외 타입뿐 아니라 `errorCode == FORBIDDEN_PAYMENT` + `httpStatus == 403` 까지 assert — 에러코드 재사용(D-P3-4)의 회귀를 테스트로 고정
- `@InjectMocks` 로 `CancelAuthorizationService` 를 배선하되, 로드 미사용 케이스(ADMIN/USER)는 `findByPaymentKey` 를 stub 하지 않아 Mockito strict-stub(UnnecessaryStubbing) 위반을 회피 + never() 로 로드 생략을 능동 증명
- ADMIN pass-through 는 Plan 01 이 만든 `mockMvcWithRealAuthz()` 헬퍼를 재사용 — 실 CancelAuthorizer + mock PaymentRepository + mock 코어 배선으로 SC#1 을 최소 추가 코드로 증명
- 문서는 append-only — 기존 취소 규칙(§1~7)·기존 취소 응답(200/409/422)은 한 줄도 변경하지 않고 신규 섹션만 추가

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None. 서비스 레이어 `CancelFlowIntegrationTest` 는 `cancelPaymentService.cancel()` 을 직접 호출(컨트롤러 미경유)하므로 인가 pre-check 영향 밖 — 무변경으로 초록 유지되며 그 자체가 취소 코어 불변 증거(계획대로 수정하지 않음).

## Threat Flags

없음 — plan `<threat_model>` 범위 밖의 신규 보안 표면 도입 없음. T-03-02(MERCHANT 소유권 경계, mitigate)는 CancelAuthorizerTest(mismatch/누락) + CancelAuthorizationServiceTest(로드 후 비교)로 검증 완료. T-03-01(payment 직접 도달 스푸핑, transfer)은 domain-rules.md §8-3 에 NetworkPolicy 필수 게이트·미이관 리스크 명문화로 이관 처리 — 코드 phase 스코프 밖(D-P3-6).

## Known Stubs

없음 — 모든 테스트가 실 판정 로직(CancelAuthorizer)을 실제로 실행하며, mock 은 경계(PaymentRepository 로드, 코어 cancel 호출)에만 사용. 하드코딩된 빈 값/placeholder UI stub 없음.

## Next Phase Readiness
- Success Criteria 3종 모두 자동 검증 고정: SC#1(ADMIN pass-through 컨트롤러) / SC#2(무권한 403 매트릭스+서비스 exhaustive) / SC#3(role-only 인가, spring-security 의존 부재)
- 배포 전 필수: k3s NetworkPolicy 로 payment(8080) ingress 를 게이트웨이 파드로 제한 (T-03-01, domain-rules.md §8-3) — 코드가 아닌 배포 게이트
- 취소 코어 불변 게이트를 CI 상시 편입 권장 (merge-base 기반)

## Self-Check: PASSED

---
*Phase: 03-payment*
*Completed: 2026-07-30*
