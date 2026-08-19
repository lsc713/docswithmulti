---
phase: 02-api-gateway-jwt
plan: 02
subsystem: api
tags: [api-gateway, jwt, gate-03, header-strip, spoofing-regression, wiremock, error-catalog]

# Dependency graph
requires:
  - phase: 02-api-gateway-jwt
    plan: 01
    provides: "JwtVerifier(verify-only HS256)·JwtTrustHeaderFilter(strip→verify→401/inject)·RouteConfig·GatewayRoutingIT tracer"
provides:
  - "user-service 공개 3경로(signup/login/refresh) + 인증 logout 라우트 — client-facing만 노출(내부 서비스 미노출)"
  - "GATE-03 401 회귀: 누락/무효서명/만료 토큰 → downstream 도달 전 401 + downstream 무호출 assert"
  - "D-P2-3 스푸핑 회귀: 클라 위조 X-User-*(전 헤더) strip 후 게이트웨이 검증값만 downstream 도달"
  - "JwtVerifier 단위 스위트(서명/만료/변조/alg-none reject)"
  - "error-catalog.md 게이트웨이 인증 코드(TOKEN_MISSING/INVALID/EXPIRED, 401)"
affects: [03-payment-authorization]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "OR 조합 RequestPredicate(path().or().or())로 공개 3경로 단일 라우트 바인딩"
    - "WireMock verify(0, anyRequestedFor(anyUrl()))로 401 단락의 downstream 미도달 증명"
    - "WireMock withHeader(name, absent())로 공개 경로 strip(위조헤더 부재) 단정"
    - "JwtVerifier 생성자 직접 주입 순수 단위(스프링 컨텍스트 불요)"

key-files:
  created:
    - "api-gateway/src/test/java/com/example/gateway/JwtVerifierTest.java"
  modified:
    - "api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java"
    - "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java"
    - "docs/error-catalog.md"

key-decisions:
  - "TOKEN_INVALID 케이스는 '다른 secret 서명'으로 실증(변조 payload는 JwtVerifier 단위에서 커버) — 401 IT는 각 실패 축을 1개씩 분리"
  - "alg 혼동 방어는 unsigned(alg=none) 토큰 reject로 검증 — RS256 비대칭 위장은 RSA 키 부담 대비 실익 낮아 none 케이스로 대표"
  - "게이트웨이 TOKEN_* 코드는 취소 코어 ErrorCode enum과 분리 문서화(게이트웨이 무상태 독립 모듈, common 모듈 미의존)"

requirements-completed: [GATE-01, GATE-02, GATE-03]

coverage:
  - id: D5
    description: "누락/무효서명/만료 토큰 → downstream 도달 전 401 {code} + WireMock downstream 무호출 (GATE-03, T-02-02/T-02-03)"
    requirement: "GATE-03"
    verification:
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#missingToken_returns401_downstreamNotCalled"
        status: pass
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#invalidSignature_returns401_downstreamNotCalled"
        status: pass
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#expiredToken_returns401_downstreamNotCalled"
        status: pass
    human_judgment: false
  - id: D6
    description: "유효 JWT + 클라 위조 X-User-*(9999/ADMIN/1) → 게이트웨이 검증값(42/USER/7)만 downstream 도달 (D-P2-3, GATE-02 스푸핑 측면, T-02-01)"
    requirement: "GATE-02"
    verification:
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#validJwt_spoofedTrustHeaders_downstreamReceivesGatewayIdentityOnly"
        status: pass
    human_judgment: false
  - id: D7
    description: "공개 경로(signup) 토큰없이 200 통과 + 위조 X-User-Role strip / 인증 경로(logout) 토큰없으면 401 (D-P2-5, T-02-06)"
    requirement: "GATE-01"
    verification:
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#publicSignupPath_noToken_passesThrough_stripsSpoofedHeaders"
        status: pass
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#securedLogoutPath_noToken_returns401_downstreamNotCalled"
        status: pass
    human_judgment: false
  - id: D8
    description: "JwtVerifier 서명/만료/변조/alg-none 단위 검증 (D-P2-4, T-02-05)"
    requirement: "GATE-03"
    verification:
      - kind: unit
        ref: "api-gateway/src/test/java/com/example/gateway/JwtVerifierTest.java"
        status: pass
    human_judgment: false
  - id: D9
    description: "라우팅 범위 — client-facing(user-service·payment)만 노출, order/merchant-limit/risk 라우트 부재 (D-P2-5)"
    requirement: "GATE-01"
    verification:
      - kind: manual
        ref: "api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java (3 라우트 빈: payment/user-auth-public/user-auth-secured만)"
        status: pass
    human_judgment: false

# Metrics
duration: ~7 min
completed: 2026-07-30
status: complete
---

# Phase 2 Plan 02: Gateway Security Contract 확장 Summary

**02-01 tracer가 증명한 happy path 위에서 게이트웨이의 실패 경로·스푸핑 방어·라우팅 범위를 회귀로 못박음 — GATE-03 401 3종(누락/무효/만료, 각각 downstream 무호출), D-P2-3 전 헤더 스푸핑 strip, 공개 3경로 통과 / logout 인증, JwtVerifier 단위, error-catalog TOKEN_* 등록. `:api-gateway:build` 그린(13 tests 0 failures).**

## Performance

- **Duration:** ~7 min (start 04:37Z)
- **Completed:** 2026-07-30
- **Tasks:** 3 (전부 type=auto, 체크포인트 없음)
- **Files created:** 1 · **modified:** 3

## Accomplishments
- **라우트 확장(D-P2-5):** `user-auth-public`(signup/login/refresh, OR predicate, strip-only)·`user-auth-secured`(logout, JwtTrustHeaderFilter) 추가. payment 라우트 유지. order/merchant-limit/risk는 라우트 부재 — 내부 서비스 미노출.
- **GATE-03 401 회귀(T-02-02/03):** 누락(TOKEN_MISSING)·무효서명(TOKEN_INVALID, 다른 secret)·만료(TOKEN_EXPIRED) 3종 모두 401 + `{code}` 본문 + WireMock downstream **무호출**(verify 0 requests) 단정 → downstream 도달 전 차단 증명.
- **D-P2-3 스푸핑 회귀(T-02-01):** 유효 JWT + 위조 X-User-Id:9999/X-User-Role:ADMIN/X-Merchant-Id:1 동시 전송 → downstream이 게이트웨이 검증값 42/USER/7만 수신. 인가 우회 방지 고정.
- **공개/인증 경로 대칭(T-02-06):** 공개 signup 토큰없이 200 통과 + 위조 X-User-Role은 `absent()`로 strip 확인 / logout 토큰없으면 401 + user downstream 무호출.
- **JwtVerifier 단위(D-P2-4/T-02-05):** 동일 secret accept·위조서명·만료·변조 payload·unsigned(alg=none) reject 5케이스. 스프링 컨텍스트 없이 생성자 직접 주입.
- **error-catalog(D-P2-7):** 401 상태코드 행 + TOKEN_MISSING/INVALID/EXPIRED 신규 섹션 append. 기존 취소 코어 항목·ErrorCode enum 불변.

## Task Commits

1. **Task 1: RouteConfig 확장(공개 3경로 + logout)** — `d11a858` (feat)
2. **Task 2: GatewayRoutingIT 401 3종 + 스푸핑 + 공개/인증 회귀** — `df98185` (test)
3. **Task 3: JwtVerifierTest 단위 + error-catalog TOKEN_* 등록** — `7dc3b14` (test)

_Plan metadata(SUMMARY): .planning gitignore → 커밋 없음(의도된 skip)._

## Files Created/Modified
- `api-gateway/.../config/RouteConfig.java` (수정) — 라우트 빈 2→3: user-auth-public(signup/login/refresh OR predicate, strip)·user-auth-secured(logout, jwt filter) 추가
- `api-gateway/.../integration/GatewayRoutingIT.java` (수정) — 401 3종·스푸핑 회귀·공개signup·logout 테스트 6개 + WRONG_SECRET/signedToken 헬퍼 추가(총 8 tests)
- `api-gateway/.../JwtVerifierTest.java` (신규) — 5 단위 케이스
- `docs/error-catalog.md` (수정) — 401 status 행 + 인증 오류(401) 섹션 append

## Decisions Made
- **401 실패 축 1:1 분리:** 무효 케이스는 IT에서 '다른 secret 서명'으로 대표하고, 변조 payload reject는 JwtVerifier 단위로 커버 — IT 각 테스트가 단일 실패 원인만 격리.
- **alg 혼동 방어 = unsigned(alg=none) reject:** RS256 비대칭 위장은 RSA 키 생성 부담 대비 실익 낮음. jjwt `verifyWith(SecretKey)`가 대칭키 파서로 unsigned/none을 거부하는 것으로 alg-confusion 방어 대표 검증.
- **TOKEN_* 코드 분리 문서화:** 게이트웨이는 무상태 독립 모듈(common 미의존)이라 취소 코어 `ErrorCode` enum에 넣지 않고 error-catalog에 별도 섹션으로 명시.

## Deviations from Plan

None — plan executed exactly as written. Task 1/2/3 및 각 `<verify>` 계획대로 그린, 신규 발명·스코프 크리프 없음.

## Threat Mitigations Verified
- **T-02-01 (전 경로 strip):** 스푸핑 회귀(D6) + 공개경로 strip(D7) 그린.
- **T-02-02 (401 단락):** 누락/무효 → downstream 무호출(D5) 그린.
- **T-02-03 (만료 재사용):** 만료 토큰 401(D5) + JwtVerifier 단위(D8) 그린.
- **T-02-05 (alg 혼동/변조):** JwtVerifier 변조·unsigned reject(D8) 그린.
- **T-02-06 (미인증 logout):** logout 토큰없이 401(D7) 그린.

## Issues Encountered
None. `:api-gateway:build` 그린 — GatewayRoutingIT 8/8 + JwtVerifierTest 5/5, 0 skipped/failures.

## Known Stubs
None — 모든 라우트가 실제 WireMock downstream으로 e2e 검증됨. 하드코딩 placeholder 없음.

## Next Phase Readiness
- Phase 2(api-gateway JWT) 보안·라우팅 계약 완성: GATE-01/02/03 전부 통합/단위로 못박음. 스푸핑·401·라우팅 범위 회귀 고정 → Phase 3 payment authorization이 신뢰 헤더(X-User-Id/Role/Merchant-Id)를 안전하게 소비 가능.
- 잔여 확장 여지(현 범위 밖): user-service 그 외 인증 경로 추가 노출 시 secured 라우트에 편입, 프로덕션 `JWT_SECRET` 동일값 주입(무상태 대칭키).

## Self-Check: PASSED
- `api-gateway/src/test/java/com/example/gateway/JwtVerifierTest.java` 디스크 존재 확인.
- 커밋 d11a858(T1)·df98185(T2)·7dc3b14(T3) 존재 확인.
- `:api-gateway:build` 그린 (IT 8/8 + unit 5/5, 0 failures/skipped).
- RouteConfig에 order/merchant/risk 라우트 부재, error-catalog TOKEN_EXPIRED 존재 확인.

---
*Phase: 02-api-gateway-jwt*
*Completed: 2026-07-30*
