---
phase: 02-api-gateway-jwt
plan: 01
subsystem: api
tags: [api-gateway, spring-cloud-gateway, jwt, hs256, jjwt, wiremock, trust-headers, stateless]

# Dependency graph
requires:
  - phase: 01-user-service
    provides: "JWT 발급 계약(JwtTokenProvider) — HS256, subject=userId, claim role/merchantId, local secret 기본값"
provides:
  - "무상태 api-gateway 모듈 (포트 8000) — Spring Cloud Gateway 5.0.2 서블릿 MVC"
  - "게이트웨이 집약 JWT 검증(verify-only HS256) + 신뢰 헤더 주입(X-User-Id/X-User-Role/X-Merchant-Id)"
  - "클라 X-User-* strip-then-set 스푸핑 방지 필터"
  - "payment 취소 경로 + user 로그인(공개) 경로 라우팅"
  - "BOM 2025.1.2 <-> Boot 4.0.5 정합 빌드 확정 (A1 해소)"
affects: [03-payment-authorization, api-gateway-route-expansion]

# Tech tracking
tech-stack:
  added:
    - "org.springframework.cloud:spring-cloud-dependencies:2025.1.2 (BOM)"
    - "spring-cloud-starter-gateway-server-webmvc:5.0.2"
    - "io.jsonwebtoken:jjwt 0.12.6 (api/impl/jackson)"
    - "org.wiremock:wiremock-standalone:3.9.2 (test)"
  patterns:
    - "무상태 모듈이 루트 강제 JPA/Flyway를 spring.autoconfigure.exclude로 무력화 (Boot 4 재배치 FQCN)"
    - "단일 HandlerFilterFunction으로 strip->verify->401/inject 3책임 응집 (Spring Security 미사용)"
    - "gateway-webmvc 5.0 http() no-arg + before(uri(...)) 라우트 URI 바인딩 idiom"
    - "Boot 4 IT: @SpringBootTest(RANDOM_PORT) + JDK HttpClient + WireMock (TestRestTemplate 미의존)"

key-files:
  created:
    - "api-gateway/build.gradle"
    - "api-gateway/src/main/resources/application.yml"
    - "api-gateway/src/main/java/com/example/gateway/ApiGatewayApplication.java"
    - "api-gateway/src/main/java/com/example/gateway/config/JwtVerifier.java"
    - "api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java"
    - "api-gateway/src/main/java/com/example/gateway/filter/JwtTrustHeaderFilter.java"
    - "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java"
  modified:
    - "settings.gradle"

key-decisions:
  - "신뢰 헤더 계약 확정(승인): X-User-Id / X-User-Role / X-Merchant-Id — Phase 3 payment 소비 (one-way door)"
  - "BOM 2025.1.2를 Boot 4.0.5 핀 위에 얹어 빌드 그린 — 루트 Boot 상향/BOM 하향 불필요 (A1 knob 미발동)"
  - "A3 strip은 ServerRequest.from(req).headers(h -> h.remove(...))로 동작 확인 — removeRequestHeader 폴백 불필요"

patterns-established:
  - "무상태 게이트웨이: autoconfigure.exclude DataSource/HibernateJpa/Flyway (Boot 4.0.5 재배치 FQCN)"
  - "JWT 게이트: Spring Security 없이 단일 HandlerFilterFunction"
  - "IT는 downstream별 별도 WireMock 스텁으로 per-route 라우팅을 실증"

requirements-completed: [GATE-01, GATE-02]

coverage:
  - id: D1
    description: "유효 JWT → 게이트웨이 payment 취소 경로 라우팅 → payment downstream 도달 (GATE-01)"
    requirement: "GATE-01"
    verification:
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#validJwt_routesToPaymentDownstream_withTrustHeaders_strippingSpoofed"
        status: pass
    human_judgment: false
  - id: D2
    description: "downstream이 X-User-Id/X-User-Role 신뢰 헤더 수신 + 클라 위조 X-User-* strip (GATE-02)"
    requirement: "GATE-02"
    verification:
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#validJwt_routesToPaymentDownstream_withTrustHeaders_strippingSpoofed"
        status: pass
    human_judgment: false
  - id: D3
    description: "per-route 라우팅 실증 — user 로그인(공개) 경로가 user downstream으로만 라우팅"
    requirement: "GATE-01"
    verification:
      - kind: integration
        ref: "api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java#publicLoginPath_routesToUserDownstream"
        status: pass
    human_judgment: false
  - id: D4
    description: "게이트웨이 무상태 기동 + BOM 2025.1.2 <-> Boot 4.0.5 컴파일/기동/IT 그린 (D-P2-1, D-P2-6, A1)"
    verification:
      - kind: integration
        ref: "./gradlew :api-gateway:build"
        status: pass
    human_judgment: false

# Metrics
duration: ~30 min
completed: 2026-07-30
status: complete
---

# Phase 2 Plan 01: API Gateway JWT Tracer Summary

**무상태 Spring Cloud Gateway(webmvc 5.0.2, 포트 8000)로 JWT verify-only HS256 검증 + X-User-* 신뢰 헤더 strip-then-set을 단일 HandlerFilterFunction으로 구현하고, payment/user 두 downstream을 별도 WireMock으로 두어 GATE-01/02 happy path를 e2e로 증명 — BOM 2025.1.2↔Boot 4.0.5 정합을 빌드로 확정.**

## Performance

- **Duration:** ~30 min
- **Completed:** 2026-07-30
- **Tasks:** 3 (T1 결정 승인, T2 스캐폴드, T3 tracer)
- **Files created:** 7 · **modified:** 1

## Accomplishments
- 신규 무상태 `api-gateway` 모듈: Spring Cloud Gateway 5.0.2 서블릿 MVC, 포트 8000, 자체 DB 없음(루트 강제 JPA/Flyway autoconfig 제외로 무력화).
- 게이트웨이 집약 JWT 검증(`JwtVerifier`, verify-only HS256, user-service와 동일 secret) + 신뢰 헤더 주입.
- `JwtTrustHeaderFilter` 단일 필터에 strip → verify → 401 단락 / 신뢰헤더 주입 3책임 응집 (Spring Security 미사용, D-P2-2).
- `GatewayRoutingIT`: payment(인증)·user 로그인(공개) 두 경로를 각각 별도 WireMock downstream으로 라우팅 증명 + 위조 X-User-Id(999) strip 후 게이트웨이 값(42) 주입 확인.
- **A1(최대 리스크) 해소:** `:api-gateway:build` 그린 — BOM 2025.1.2가 핀된 Boot 4.0.5 위에서 컴파일·기동·IT 통과. 루트 Boot 상향/BOM 하향 knob 미발동.
- **A3 해소:** `ServerRequest.from(req).headers(h -> h.remove(...))`로 strip 동작 확인 — `removeRequestHeader` 폴백 불필요(단, 공개 라우트엔 표준 필터 체인으로 적용).

## Task Commits

1. **Task 1: 신뢰 헤더 이름 계약 승인** — 체크포인트(코드 없음). 승인: `approve-x-user` (X-User-Id/X-User-Role/X-Merchant-Id).
2. **Task 2: 모듈 스캐폴드 + BOM + 무상태 application.yml** - `e5725f0` (feat)
3. **Task 3: JwtVerifier + JwtTrustHeaderFilter + 라우트 + e2e IT (tracer)** - `16e9bcb` (feat)

_Plan metadata (SUMMARY/STATE): .planning gitignore → 커밋 없음 (의도된 skip)._

## Files Created/Modified
- `settings.gradle` - `include 'api-gateway'`
- `api-gateway/build.gradle` - Spring Cloud BOM 2025.1.2 + gateway-server-webmvc + jjwt 0.12.6 + wiremock (security 없음, 루트 공통 재선언 없음)
- `api-gateway/src/main/resources/application.yml` - port 8000, jwt.secret ${JWT_SECRET}(local=user-service 기본값), autoconfigure.exclude(Boot 4 재배치 FQCN), downstream URI props
- `api-gateway/.../ApiGatewayApplication.java` - @SpringBootApplication main
- `api-gateway/.../config/JwtVerifier.java` - verify-only HS256 (~15줄, user-service 계약 미러)
- `api-gateway/.../filter/JwtTrustHeaderFilter.java` - strip → verify → 401/inject
- `api-gateway/.../config/RouteConfig.java` - payment(/v1/payments/**, 인증) + user 로그인(/v1/auth/login, 공개+strip) 라우트
- `api-gateway/src/test/.../integration/GatewayRoutingIT.java` - @SpringBootTest RANDOM_PORT + 2 WireMock + JDK HttpClient

## Decisions Made
- **신뢰 헤더 계약(one-way):** X-User-Id / X-User-Role / X-Merchant-Id — JWT claim(subject/role/merchantId) 1:1 대응, Phase 3 payment 소비. 승인 게이트 통과.
- **BOM↔Boot:** BOM 2025.1.2 + Boot 4.0.5 조합 빌드 그린으로 확정. Boot 자체 BOM이 boot 아티팩트에 우선하고 spring-cloud BOM은 cloud 아티팩트만 공급 → 패치 갭(4.0.5↔4.0.7) 무해 확인.
- **autoconfigure.exclude FQCN:** Boot 4.0.5는 autoconfig을 모듈별 패키지로 재배치 — `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`, `...hibernate.autoconfigure.HibernateJpaAutoConfiguration`, `...flyway.autoconfigure.FlywayAutoConfiguration` 사용(구 `boot.autoconfigure.*` 경로 아님).

## Deviations from Plan

### Directed / Rule 2 additions

**1. [Directed - plan-checker INFO + coordinator] user 로그인 공개 라우트 추가 (tracer가 payment 단일 경로만이 아님)**
- **Found during:** Task 3
- **Issue:** 플랜 본문 Task 3(c)는 "payment 경로 한 개만"이라 했으나, 같은 플랜의 plan-checker INFO와 coordinator 재지시가 "GatewayRoutingIT에서 payment/user 양쪽 downstream을 각각 별도 WireMock 스텁으로 두어 per-route 라우팅을 진짜로 증명(한 스텁 재사용 금지)"을 요구. 단일 downstream으로는 catch-all이 아님을 증명 불가.
- **Fix:** RouteConfig에 `user-auth-public` 라우트(/v1/auth/login → user-uri, JWT 필터 없음, 표준 필터로 X-User-* strip) 추가. IT는 두 WireMock 인스턴스(별도 포트)로 각 경로가 자기 downstream으로만 라우팅됨을 양방향 검증.
- **Files modified:** RouteConfig.java, GatewayRoutingIT.java
- **Verification:** `publicLoginPath_routesToUserDownstream` 테스트 그린 + payment 경로 테스트가 user downstream 무호출 검증.
- **Committed in:** `16e9bcb`

**2. [Rule 2 - Missing Critical + de-risk A3] IT에 위조 헤더 strip 단정 추가**
- **Found during:** Task 3
- **Issue:** 플랜 happy path는 X-User-Id/Role 수신만 검증. strip은 보안 비협상(T-02-01)이고 A3(strip API 동작)가 MEDIUM 리스크인데 happy-path만으로는 strip 동작을 확인할 수 없음. tracer의 목적이 mechanism 확정임.
- **Fix:** payment 테스트에서 위조 `X-User-Id: 999` 헤더를 함께 전송하고, downstream이 게이트웨이 값(42)만 수신(999 아님)함을 단정 → strip 동작을 tracer 시점에 실증.
- **Files modified:** GatewayRoutingIT.java
- **Verification:** 테스트 그린 (999가 42로 대체됨).
- **Committed in:** `16e9bcb`
- **Note:** 전 경로 strip + 스푸핑 회귀 스위트 전체는 계획대로 Plan 02 범위. 여기선 mechanism 실증만.

---

**Total deviations:** 2 (1 directed 확장, 1 Rule 2 보안 단정). 둘 다 plan-checker INFO/coordinator 지시 및 tracer 목적(mechanism 확정)에 부합. 스코프 크리프 없음 — 라우트 확장(order/merchant/risk, 나머지 공개/보호 경로)과 401/스푸핑 회귀 스위트는 Plan 02로 유지.

## Issues Encountered
- gateway-webmvc 5.0.2에서 `HandlerFunctions.http(uri)` 오버로드 제거됨(no-arg `http()`만 존재). → `before(BeforeFilterFunctions.uri(...))`로 라우트 URI 바인딩하는 5.0 idiom으로 해결.
- Spring 7 `HttpHeaders`가 Map/MultiValueMap 미구현으로 변경. → `remove(String)`/`set(String,String)` 신 API 사용(빌드로 확인).
- Boot 4.0.5 autoconfig 패키지 재배치 → 실제 jar에서 FQCN 확인 후 exclude 값 확정(기동 실패 전 선제 해결).

## User Setup Required
None — 게이트웨이는 무상태(자체 DB 없음). 프로덕션 배포 시 `JWT_SECRET`을 user-service와 **동일 값**으로 주입해야 함(HS256 대칭키 공유; local 프로파일은 dev 기본값 내장). docker-compose 변경 불요.

## Next Phase Readiness
- GATE-01/02 happy path e2e 그린. BOM↔Boot 정합·무상태 기동·필터 API(strip) 전부 빌드로 확정 → 확장 안전.
- **Plan 02 잔여:** 라우트 확장(user-service 전체·order/merchant/risk 노출 범위), GATE-03 401 회귀(누락/무효/만료), 전 경로 strip 스푸핑 회귀 스위트, error-catalog.md 인증 코드(TOKEN_MISSING/INVALID/EXPIRED) append, JwtVerifierTest 단위.

## Self-Check: PASSED
- 7 소스 파일 + SUMMARY 디스크 존재 확인.
- 커밋 e5725f0(T2), 16e9bcb(T3) 존재 확인.
- api-gateway 워킹트리 clean (전부 커밋됨).
- `:api-gateway:build` 그린 (IT 2/2 pass, 0 skipped/failures).

---
*Phase: 02-api-gateway-jwt*
*Completed: 2026-07-30*
