---
phase: 02-api-gateway-jwt
verified: 2026-07-30T05:00:00Z
status: passed
score: 8/8 must-haves verified
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: none
  previous_score: n/a
---

# Phase 2: API Gateway (JWT) Verification Report

**Phase Goal:** 단일 게이트웨이 진입점에서 JWT 검증 후 신원(userId·role)을 신뢰 헤더로 downstream 전달, downstream 재검증 없음.
**Verified:** 2026-07-30T05:00:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

게이트웨이 3책임(라우팅 / JWT 검증 / 신뢰헤더 주입)이 실제 코드로 구현되고, 8개 통합테스트 + 5개
단위테스트로 e2e 실증됨. 검증자가 SUMMARY 신뢰 없이 `./gradlew :api-gateway:test --rerun-tasks`를
직접 재실행하여 13/13 pass(0 failures/errors/skipped) 확인. 모든 보안 불변식(401 단락 시 downstream
무호출, 클라 위조 X-User-* strip)이 WireMock으로 런타임 실증됨 — presence가 아닌 behavior 확인.

### Observable Truths

| #   | Truth | Status | Evidence |
| --- | ----- | ------ | -------- |
| 1 | 단일 게이트웨이 진입점이 각 downstream으로 라우팅 (GATE-01) | ✓ VERIFIED | RouteConfig 3 라우트 빈. IT `validJwt_routesToPaymentDownstream`(payment)·`publicLoginPath_routesToUserDownstream`(user), 각 테스트가 반대 downstream `verify(0, anyRequestedFor)`로 per-route 실증. re-run pass. |
| 2 | 유효 JWT 통과 시 downstream이 X-User-Id/X-User-Role 신뢰헤더 수신 (GATE-02) | ✓ VERIFIED | JwtTrustHeaderFilter L64-73 subject→X-User-Id, role claim→X-User-Role, merchantId→X-Merchant-Id. IT `validJwt...strippingSpoofed`가 downstream 수신값 42/USER assert. pass. |
| 3 | 무효/만료/누락 토큰 → downstream 도달 전 401 차단 (GATE-03) | ✓ VERIFIED | Filter L48-62 Bearer 누락→TOKEN_MISSING, ExpiredJwt→TOKEN_EXPIRED, JwtException→TOKEN_INVALID, next 미호출. IT 3종(`missingToken`/`invalidSignature`/`expiredToken`) 401 본문 {code} + `verify(0)` downstream 무호출. pass. |
| 4 | 클라 위조 X-User-*(전 헤더)는 모든 경로에서 strip — 유효 JWT여도 게이트웨이 검증값만 도달 (D-P2-3, 보안 비협상) | ✓ VERIFIED | 인증경로: Filter L41-46 `ServerRequest.from().headers(h->remove())`. 공개경로: RouteConfig L53-55 `removeRequestHeader` 3개 before-filter. IT `validJwt_spoofedTrustHeaders`(9999/ADMIN/1 → 42/USER/7만 도달) + `publicSignupPath...stripsSpoofed`(X-User-Role ADMIN → `absent()`). pass. |
| 5 | 공개 경로(signup/login/refresh) 토큰없이 통과 / 인증 경로(logout) 토큰없으면 401 (D-P2-5) | ✓ VERIFIED | RouteConfig user-auth-public(필터 없음)·user-auth-secured(logout, jwt filter). IT `publicSignupPath_noToken_passesThrough`(200)·`securedLogoutPath_noToken_returns401`(401 + user downstream `verify(0)`). pass. |
| 6 | 라우팅 범위 = client-facing(user-service·payment)만, order/merchant-limit/risk 내부 서비스 미노출 (D-P2-5) | ✓ VERIFIED | RouteConfig 라우트 빈 정확히 3개(payment/user-auth-public/user-auth-secured). order/merchant/risk path 부재 (grep 확인). |
| 7 | 게이트웨이 무상태 기동 — JPA/DataSource/Flyway autoconfig 제외, 포트 8000, Spring Security 없이 단일 HandlerFilterFunction (D-P2-2/D-P2-6) | ✓ VERIFIED | application.yml autoconfigure.exclude 3 FQCN, port 8000. JwtTrustHeaderFilter 단일 HandlerFilterFunction. build.gradle에 spring-security 미포함(주석만). @SpringBootTest RANDOM_PORT 8 IT 기동 pass = DataSource 없이 기동 확정. |
| 8 | Spring Cloud BOM 2025.1.2 ↔ 핀된 Boot 4.0.5 정합 빌드 그린, JwtVerifier verify-only HS256 공유 secret 하드코딩 없음 (D-P2-1/D-P2-4) | ✓ VERIFIED | build.gradle BOM 2025.1.2 + gateway-server-webmvc(5.0.x). 루트 Boot 4.0.5 미변경 확인. `:api-gateway:build` BUILD SUCCESSFUL. JwtVerifier verify-only HS256. jwt.secret ${JWT_SECRET}(prod fail-fast), local 프로파일만 dev 기본값. |

**Score:** 8/8 truths verified (0 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
| -------- | -------- | ------ | ------- |
| `api-gateway/build.gradle` | Spring Cloud BOM + gateway-webmvc + jjwt, no security | ✓ VERIFIED | BOM 2025.1.2, jjwt 0.12.6, wiremock 3.9.2. security 미포함. |
| `application.yml` | port 8000, ${JWT_SECRET}, autoconfigure.exclude | ✓ VERIFIED | 전부 존재. exclude 3 FQCN (Boot 4 재배치 경로). |
| `JwtVerifier.java` | verify-only HS256 | ✓ VERIFIED | `verifyWith(key).parseSignedClaims` 12줄. |
| `JwtTrustHeaderFilter.java` | strip→verify→401/inject | ✓ VERIFIED | 단일 필터 3책임 응집, 84줄. |
| `RouteConfig.java` | payment + user 라우트, 내부 서비스 미노출 | ✓ VERIFIED | 3 라우트 빈, order/merchant/risk 없음. |
| `GatewayRoutingIT.java` | RANDOM_PORT + 2 WireMock | ✓ VERIFIED | 8 tests, JDK HttpClient, per-route 검증. |
| `JwtVerifierTest.java` | 서명/만료/변조/alg-none | ✓ VERIFIED | 5 단위, 모두 pass. |
| `docs/error-catalog.md` | TOKEN_* 401 append | ✓ VERIFIED | 401 행 + TOKEN_MISSING/INVALID/EXPIRED 섹션. 기존 항목 불변. |

### Key Link Verification

| From | To | Via | Status |
| ---- | -- | --- | ------ |
| JwtTrustHeaderFilter | JwtVerifier | 생성자 주입 `verifier.parse()` | ✓ WIRED |
| RouteConfig payment/secured | JwtTrustHeaderFilter | `.filter(jwt)` | ✓ WIRED |
| RouteConfig public | strip | `.before(removeRequestHeader(...))` x3 | ✓ WIRED |
| application.yml jwt.secret | user-service local secret | 동일 문자열 (IT SECRET 상수 = yml local 기본값) | ✓ WIRED (HS256 대칭키 공유) |
| IT | WireMock downstream | @DynamicPropertySource 포트 override | ✓ WIRED |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| 전체 IT + 단위 재실행 (SUMMARY 미신뢰) | `./gradlew :api-gateway:test --rerun-tasks` | BUILD SUCCESSFUL, 4s | ✓ PASS |
| GatewayRoutingIT 카운트 | test-results XML | tests=8 skipped=0 failures=0 errors=0 | ✓ PASS |
| JwtVerifierTest 카운트 | test-results XML | tests=5 skipped=0 failures=0 errors=0 | ✓ PASS |
| 전체 build (BOM↔Boot 정합) | `./gradlew :api-gateway:build` | BUILD SUCCESSFUL | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Status | Evidence |
| ----------- | ----------- | ------ | -------- |
| GATE-01 (단일 진입점 라우팅) | 02-01, 02-02 | ✓ SATISFIED | Truth 1·6, per-route IT 2종 |
| GATE-02 (JWT 검증 + 신뢰헤더 전달) | 02-01, 02-02 | ✓ SATISFIED | Truth 2·4, 헤더수신 + 스푸핑 strip IT |
| GATE-03 (무효/만료/누락 401 차단) | 02-02 | ✓ SATISFIED | Truth 3, 401 3종 + downstream 무호출 |

ORPHANED: 없음. AUTHZ-01은 Phase 3, 이번 phase 범위 밖(정상).

### Anti-Patterns Found

| File | Pattern | Severity | Impact |
| ---- | ------- | -------- | ------ |
| application.yml L41 | local 프로파일 dev 기본 secret | ℹ️ Info | 하드코딩 아님 — local 프로파일 게이트 + prod는 ${JWT_SECRET} fail-fast. D-P2-4/CLAUDE.md 시크릿 규칙 준수. 실제 배포 secret 아님. |

블로커/경고 없음. TBD/FIXME/XXX 마커 없음. 스텁 없음(모든 라우트 실제 WireMock e2e).

### Module Isolation

api-gateway 외 변경: `docs/error-catalog.md`(+13, append) · `settings.gradle`(include). 취소 코어
4개 서비스(payment/order/merchant-limit/risk) 소스 불변 확인. 루트 Boot 4.0.5 미변경 —
BOM은 api-gateway 모듈 dependencyManagement 국소 적용.

### Human Verification Required

없음 — 모든 보안 불변식(401 단락, 전 경로 strip, per-route 라우팅)이 WireMock 통합테스트로
런타임 실증되고 검증자가 직접 재실행함. 시각/외부서비스/실시간 항목 없음.

### Gaps Summary

없음. 3개 성공 기준(GATE-01/02/03) 전부 코드 + 재실행된 테스트로 달성. 보안 비협상(스푸핑
strip, 401 downstream 무호출), 잠긴 결정(무상태·단일필터·verify-only·라우팅범위·헤더계약),
모듈 격리, BOM↔Boot 정합 모두 확인.

---

_Verified: 2026-07-30T05:00:00Z_
_Verifier: Claude (gsd-verifier)_
