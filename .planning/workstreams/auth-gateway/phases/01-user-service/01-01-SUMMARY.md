---
phase: 01-user-service
plan: 01
subsystem: auth
tags: [jwt, jjwt, spring-security, bcrypt, hexagonal, flyway, mysql, testcontainers, spring-boot-4]

requires:
  - phase: (none — greenfield module)
    provides: main root build.gradle conventions (Boot 4.0.5 subprojects), settings.gradle
provides:
  - user-service module (port 8085, independent user_db:3315)
  - POST /v1/auth/{signup,login,refresh,logout} — signup/login proven e2e; refresh/logout code landed (verified in Plan 02)
  - access=JWT HS256 1h, refresh=opaque UUID+DB 7d (no rotation)
  - Flyway V1 = users + refresh_tokens (locked persistence contract)
  - server-forced signup role (USER) at the trust boundary
affects: [auth-gateway phase 02 (refresh/logout/fail-fast), gateway JWT verification]

tech-stack:
  added:
    - spring-boot-starter-security (Boot 4.0.5 → Spring Security 7)
    - io.jsonwebtoken:jjwt-api/impl/jackson 0.12.6
    - spring-security-test (test)
  patterns:
    - "Hexagonal ports/adapters: application depends on interfaces, infrastructure implements; domain is pure POJO (JpaEntity from()/toDomain() mapping)"
    - "opaque refresh token (UUID) persisted in DB; logout = hard DELETE by userId"
    - "trust-boundary DTO: privileged fields (role/merchantId) omitted from request record, server-forced in controller"
    - "fail-fast secret: ${JWT_SECRET} with dev default only in local profile document"

key-files:
  created:
    - user-service/build.gradle
    - user-service/Dockerfile
    - user-service/src/main/resources/application.yml
    - user-service/src/main/resources/db/migration/V1__create_user_core.sql
    - user-service/src/main/java/com/example/user/** (34 main files — AUTH slice)
    - user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java
    - user-service/src/test/java/com/example/user/** (7 ported test files)
  modified:
    - settings.gradle (include 'user-service')

key-decisions:
  - "V1 schema (users + refresh_tokens) locked as immutable Flyway contract — approve-as-researched (D-P1-5, one-way)"
  - "Boot 4 test-module reshuffle: integration test uses WebApplicationContext + MockMvcBuilders instead of TestRestTemplate/@AutoConfigureMockMvc"

patterns-established:
  - "PORT via git show redirect from origin/feat/user-product-resilience (never merged); TRIM Address/PaymentMethod/Admin/UserController; ADJUST only security/version deltas"
  - "ddl-auto: validate — trimmed V1 must match trimmed entities exactly"

requirements-completed: [AUTH-01, AUTH-02]

coverage:
  - id: D1
    description: "POST /v1/auth/signup new email → 200 with access (JWT HS256, subject=userId, role=USER) + refresh (opaque UUID)"
    requirement: "AUTH-01"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java#signupLoginAccessRefreshEndToEnd"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST /v1/auth/signup duplicate email → 409"
    requirement: "AUTH-01"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java#signupLoginAccessRefreshEndToEnd"
        status: pass
    human_judgment: false
  - id: D3
    description: "POST /v1/auth/login valid credentials → 200 access + refresh; exactly one refresh_tokens row per user"
    requirement: "AUTH-02"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java#signupLoginAccessRefreshEndToEnd"
        status: pass
      - kind: unit
        ref: "user-service/src/test/java/com/example/user/application/service/AuthServiceTest.java#LoginTests"
        status: pass
    human_judgment: false
  - id: D4
    description: "D-P1-2: client-supplied role=ADMIN / merchantId=99 ignored → stored role=USER, merchant_id=NULL (server-forced)"
    requirement: "AUTH-01"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java#signupIgnoresClientSuppliedRoleAndMerchantId"
        status: pass
    human_judgment: false
  - id: D5
    description: "user-service compiles and boots under Spring Boot 4.0.5 / Security 7 (A2 build-note)"
    verification:
      - kind: automated
        ref: "./gradlew :user-service:build"
        status: pass
    human_judgment: false

duration: ~35min
completed: 2026-07-30
status: complete
---

# Phase 1 Plan 1: user-service Auth Slice Summary

**Ported hexagonal user-service (port 8085) from the reference branch — JWT HS256 access + opaque-UUID refresh, BCrypt, server-forced signup role — proven signup→login→token e2e against Testcontainers MySQL on Spring Boot 4.0.5 / Security 7.**

## Performance

- **Duration:** ~35 min (incl. T1 checkpoint approval)
- **Completed:** 2026-07-30
- **Tasks:** 3 (T1 decision gate + T2 scaffold/port + T3 tests)
- **Files modified:** 47 (34 main java, 8 test java, settings.gradle, build.gradle, Dockerfile, application.yml, V1 SQL)

## Accomplishments
- user-service module scaffolded and wired into settings.gradle; compiles + boots on Boot 4.0.5 / Security 7 with **zero production-code API changes** (A2 risk resolved green — SecurityConfig lambda DSL was already Security 7-compatible).
- Full AUTH hexagonal slice ported (domain POJO → application ports → infrastructure adapters → presentation), with Address/PaymentMethod/Admin/UserController trimmed and their side-effects cleaned (PersistenceConfig beans, ErrorCode entries).
- Security-nonnegotiables enforced: **D-P1-2** (SignupRequest drops role/merchantId; controller forces UserRole.USER/null), **D-P1-3** (jwt.secret ${JWT_SECRET}; dev default only in local profile → fail-fast otherwise), **D-P1-5** (port 8085, datasource localhost:3315/user_db, V1 = users+refresh_tokens).
- 34 tests green including new AuthIntegrationTest that drives signup→duplicate→login end-to-end through the real DispatcherServlet to Testcontainers MySQL, and pins the role/merchantId self-provisioning regression.

## Task Commits

1. **Task 1: Flyway V1 schema contract** — decision gate, approved `approve-as-researched` (no code commit; DDL landed in T2)
2. **Task 2: scaffold + port AUTH slice + trim + security adjust** — `ca413a7` (feat)
3. **Task 3: port unit/persistence tests + e2e integration test** — `da8c8cb` (test)

## Files Created/Modified
- `settings.gradle` — added `include 'user-service'`
- `user-service/build.gradle` — security + jjwt 0.12.6 on root subprojects common deps; flyway + jacoco
- `user-service/src/main/resources/application.yml` — port 8085, user_db:3315, fail-fast jwt.secret + local profile default
- `user-service/src/main/resources/db/migration/V1__create_user_core.sql` — users + refresh_tokens (locked)
- `user-service/src/main/java/com/example/user/**` — AUTH domain/application/infrastructure/presentation (34 files)
- `.../presentation/dto/SignupRequest.java` — role/merchantId removed (D-P1-2)
- `.../presentation/controller/AuthController.java` — signup forces UserRole.USER/null
- `.../infrastructure/config/PersistenceConfig.java` — Address/PaymentMethod beans removed
- `.../common/exception/ErrorCode.java` — ADDRESS_NOT_FOUND/PAYMENT_METHOD_NOT_FOUND removed
- `user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java` — new e2e tracer proof
- `user-service/src/test/java/com/example/user/**` — 7 ported unit/persistence tests

## Decisions Made
- **V1 schema locked** (approve-as-researched): users + refresh_tokens only; addresses/payment_methods trimmed. Immutable Flyway contract — future changes require V2 (D-P1-5, one-way door).
- **BCrypt cost 10** and **refresh non-rotation** retained from reference (in-scope per RESEARCH open questions).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Spring Boot 4 relocated integration-test support classes**
- **Found during:** Task 3 (AuthIntegrationTest compile)
- **Issue:** `org.springframework.boot.test.web.client.TestRestTemplate` and `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` no longer resolve on the Boot 4.0.5 test classpath (test support was restructured out of these modules). Additionally Boot 4's Jackson auto-config exposes no plain `ObjectMapper` bean.
- **Fix:** Assembled MockMvc from the injected `WebApplicationContext` via `MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build()` (spring-test + spring-security-test only, no Boot auto-config); switched `@SpringBootTest` from RANDOM_PORT to default MOCK (canonical MockMvc pairing); used a plain `new ObjectMapper()` in the test for the 2-field response record.
- **Files modified:** user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java
- **Verification:** `./gradlew :user-service:build` green — all 34 tests pass; test still drives the full DispatcherServlet → controller → service → domain → repo → Testcontainers MySQL path (socket layer is the only thing MOCK omits).
- **Committed in:** `da8c8cb` (Task 3 commit)

**2. [Rule 3 - Blocking] AuthControllerTest strict-Jackson signup body**
- **Found during:** Task 3 (ported AuthControllerTest)
- **Issue:** Ported standalone-MockMvc test posted `"role":"USER"` in the signup JSON; after D-P1-2 removed `role` from SignupRequest, the standalone MockMvc's strict ObjectMapper (FAIL_ON_UNKNOWN_PROPERTIES=true) would reject it → 400.
- **Fix:** Removed the `role` field from the test's signup JSON body (aligns the ported test with the trimmed DTO).
- **Files modified:** user-service/src/test/java/com/example/user/presentation/controller/AuthControllerTest.java
- **Verification:** AuthControllerTest.shouldSignup passes (200).
- **Committed in:** `da8c8cb` (Task 3 commit)

---

**Total deviations:** 2 auto-fixed (both Rule 3 - blocking, test-only). **Impact:** Zero production-code deviation — the planned MEDIUM-confidence A2 (Boot 4 compile) resolved green with no changes to shipped code. Friction was confined to Boot 4 test-scaffolding package churn. No scope creep.

## Issues Encountered
None beyond the two Boot 4 test-scaffolding deviations above (both resolved).

## User Setup Required
None for local (spring.profiles.active=local supplies a dev JWT secret). For non-local deployment, `JWT_SECRET` (≥256-bit) must be injected or the context fails fast by design (D-P1-3) — the mysql-user compose service (port 3315) and fail-fast test land in Plan 02.

## Next Phase Readiness
- signup/login proven e2e; refresh/logout code is present in AuthService/AuthController and awaits Plan 02 integration verification (AUTH-03/04) plus the JWT_SECRET fail-fast test and docker-compose `mysql-user` service.
- V1 schema is locked — any user_db column change in later plans must be a new V2 migration.

---
*Phase: 01-user-service*
*Completed: 2026-07-30*

## Self-Check: PASSED
- Key files verified on disk (settings.gradle, user-service build/Dockerfile/yml/V1, PersistenceConfig, AuthIntegrationTest, SUMMARY).
- Commits verified: `ca413a7` (T2 feat), `da8c8cb` (T3 test).
- `:user-service:build` green — 34 tests pass on Boot 4.0.5 / Security 7.
- `.planning/` gitignored (SUMMARY not committed, per commit_docs=false); user-service working tree clean.
