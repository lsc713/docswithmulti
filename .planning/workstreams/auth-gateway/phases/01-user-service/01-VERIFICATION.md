---
phase: 01-user-service
verified: 2026-07-30T11:35:00Z
status: passed
score: 5/5 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 1: user-service 인증 기반 Verification Report

**Phase Goal:** 사용자가 자체 계정으로 신원을 만들고 JWT 토큰 수명주기(발급·갱신·무효화)를 관리한다.
**Verified:** 2026-07-30T11:35:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

Goal-backward: 각 성공 기준(AUTH-01~04)이 실제 코드 + 실행되는 통합 테스트로 달성됨을 확인.
토큰 수명주기 4개는 **상태 전이/무효화 불변식**(behavior-dependent)이므로 심볼 존재만으로 VERIFIED로
판정하지 않고, Testcontainers MySQL 대상 실제 e2e 테스트를 강제 재실행(`--rerun-tasks`, 캐시 무효)해
행동 증거를 확보함 — AuthIntegrationTest 4/4, JwtSecretFailFastTest 2/2 fresh green (15s, DB 실기동).

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | 신규 이메일 signup → 200 + access(JWT HS256) + refresh(opaque UUID); 중복 이메일 → 409 [AUTH-01] | ✓ VERIFIED | `AuthIntegrationTest#signupLoginAccessRefreshEndToEnd` — signup 200, access를 HS256 키로 파싱(subject=userId>0, role=USER), refresh가 UUID 정규식 매치. 재-signup 409. DuplicateEmailException→ErrorCode.DUPLICATE_EMAIL(409). Fresh rerun pass. |
| 2 | 로그인 → 200 + access(JWT HS256) + refresh, 사용자당 refresh 1개 [AUTH-02] | ✓ VERIFIED | 동 테스트 login 분기: 200, access JWT, refresh UUID, `SELECT COUNT(*) FROM refresh_tokens = 1`. AuthService.login이 기존 refresh를 deleteByUserId 후 1개 재발급. |
| 3 | 유효 refresh → 새 access 발급, 미회전(응답 refreshToken=null, 행 1개 유지); 무효 refresh → 401 [AUTH-03, D-P1-1] | ✓ VERIFIED | `AuthIntegrationTest#refreshIssuesNewAccessWithoutRotationAndRejectsInvalid` — 200, 새 access subject 일치, `refreshToken=null`, count 여전히 1(미회전). 미존재 UUID → 401(InvalidTokenException→INVALID_TOKEN 401). 만료 케이스는 `AuthServiceTest#shouldThrowOnExpiredRefreshToken` 단위 커버. Fresh rerun pass. |
| 4 | logout(Bearer access) → refresh 하드 DELETE; 그 refresh로 재갱신 → 401 [AUTH-04, D-P1-4] | ✓ VERIFIED | `AuthIntegrationTest#logoutHardDeletesRefreshAndBlocksSubsequentRefresh` — Bearer access로 logout 200(JwtAuthenticationFilter가 principal=userId 세팅, authenticated 경로), `COUNT=0`(deleteByUserId 하드삭제), 그 refresh 재갱신 → 401. 상태전이(DELETE)를 DB로 직접 관측. Fresh rerun pass. |
| 5 | user-service가 Spring Boot 4.0.5 / Security 7에서 컴파일·기동·테스트 그린 [build-note] | ✓ VERIFIED | `./gradlew :user-service:build` BUILD SUCCESSFUL. 15 테스트 클래스, 통합 e2e가 실 DispatcherServlet→controller→service→domain→repo→Testcontainers MySQL 관통. |

**Score:** 5/5 truths verified (0 present, behavior-unverified)

### Security Non-Negotiables (실증)

| 결정 | 요구 | Status | Evidence |
|------|------|--------|----------|
| D-P1-2 | signup role 서버 USER 강제, 클라 ADMIN/merchantId 무시 | ✓ VERIFIED | `SignupRequest`에 role/merchantId 필드 없음(record 4필드). `AuthController.signup`이 `UserRole.USER, null` 하드코딩 전달. 회귀 테스트 `signupIgnoresClientSuppliedRoleAndMerchantId`: JSON에 `"role":"ADMIN","merchantId":99` 억지 주입 → DB `role=USER`, `merchant_id=NULL`, 토큰 role 클레임=USER. |
| D-P1-3 | 비-local JWT_SECRET 미주입 fail-fast | ✓ VERIFIED | `application.yml` 비-local 문서: `jwt.secret: ${JWT_SECRET}`(기본값 없음), dev 기본값은 `on-profile: local` 문서에만. `JwtSecretFailFastTest`: prod 프로파일 미주입 → `context.hasFailed()`, local → `hasNotFailed()`. Fresh rerun 2/2 green. |
| D-P1-1 | refresh 미회전 | ✓ VERIFIED | `AuthController.refresh` → `TokenResponse(access, null)`; `AuthService.refresh`는 새 refresh 미생성. Truth 3 통합 관측. |
| D-P1-4 | logout 하드 DELETE | ✓ VERIFIED | `AuthService.logout` → `refreshTokenRepository.deleteByUserId(userId)`. Truth 4 count→0. |
| D-P1-5 | 독립 DB/포트 8085·3315, V1 = users+refresh_tokens만 | ✓ VERIFIED | `application.yml` port 8085, `jdbc:mysql://localhost:3315/user_db`. `V1__create_user_core.sql` = users + refresh_tokens 2 테이블만(addresses/payment_methods 트림). `docker-compose.yml` mysql-user(mysql:8.0, user_db, `3315:3306`, mysql-user-data 볼륨). |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `settings.gradle` | include 'user-service' | ✓ VERIFIED | line 9 `include 'user-service'` |
| `user-service/build.gradle` | security + jjwt 0.12.6 + spring-security-test | ✓ VERIFIED | starter-security, jjwt-api/impl/jackson 0.12.6, spring-security-test(test) |
| `V1__create_user_core.sql` | users + refresh_tokens only | ✓ VERIFIED | 정확히 2 테이블, email UNIQUE, refresh_tokens.token UNIQUE + FK user_id |
| `PersistenceConfig.java` | user/refresh/encoder/jwt 빈만 | ✓ VERIFIED | Address/PaymentMethod 빈 없음. 4개 어댑터 빈 + txManager + jwtTokenProvider(@Value) |
| `AuthIntegrationTest.java` | Testcontainers e2e | ✓ VERIFIED | 4 테스트, @SpringBootTest + MySQLContainer, JdbcTemplate로 DB 상태 직접 관측 |
| `JwtSecretFailFastTest.java` | fail-fast 회귀 | ✓ VERIFIED | ApplicationContextRunner + PSPC, prod 실패/local 성공 대조 |
| `docker-compose.yml` mysql-user | 3315:3306, user_db, volume | ✓ VERIFIED | 서비스 + mysql-user-data 볼륨 등록 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| AuthController.signup | AuthService.signup | UserRole.USER/null 강제 전달 | ✓ WIRED | 신뢰경계에서 클라 특권필드 차단 |
| SecurityConfig | logout 라우트 | anyRequest().authenticated() (permitAll 아님) | ✓ WIRED | signup/login/refresh만 permitAll, logout은 인증 필요 |
| JwtAuthenticationFilter | logout | Bearer 파싱 → principal=userId → deleteByUserId | ✓ WIRED | Truth 4 e2e로 authenticated 경로 관통 |
| PersistenceConfig.jwtTokenProvider | application.yml | @Value jwt.secret/expiries | ✓ WIRED | fail-fast의 실 소비 지점 |
| application.yml datasource | user_db | jdbc:mysql://localhost:3315/user_db | ✓ WIRED | ddl-auto: validate → V1과 엔티티 정합 |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| AUTH-01~04 e2e (실 MySQL) | `:user-service:test --tests '*AuthIntegrationTest' --rerun-tasks` | 4 tests, 0 fail (fresh, 15s) | ✓ PASS |
| D-P1-3 fail-fast | `--tests '*JwtSecretFailFastTest' --rerun-tasks` | 2 tests, 0 fail (fresh) | ✓ PASS |
| 전체 빌드 | `./gradlew :user-service:build` | BUILD SUCCESSFUL | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| AUTH-01 | 01-01 | 회원가입, 중복 이메일 거부 | ✓ SATISFIED | Truth 1 |
| AUTH-02 | 01-01 | 로그인 → access+refresh JWT HS256 | ✓ SATISFIED | Truth 2 |
| AUTH-03 | 01-02 | refresh로 access 갱신 | ✓ SATISFIED | Truth 3 |
| AUTH-04 | 01-02 | 로그아웃 → refresh 무효화 | ✓ SATISFIED | Truth 4 |

### Anti-Patterns / Isolation

| Check | Result |
|-------|--------|
| Debt markers (TBD/FIXME/XXX/TODO) in user-service/src | None |
| Hardcoded secret literals in main src | None (local-profile dev 기본값은 D-P1-3 설계상 허용) |
| domain 레이어 순수성 | ✓ User/RefreshToken 순수 POJO — Spring/JPA 어노테이션 없음 (of/reconstruct 팩토리 + JpaEntity 매핑) |
| 취소 코어 4개 서비스 불변 | ✓ `git diff ca413a7^..HEAD --name-only` = user-service + settings.gradle + docker-compose.yml만 (payment/order/merchant-limit/risk 무변경) |

### Gaps Summary

없음. 5개 성공 기준(AUTH-01~04 + Boot 4 그린)과 5개 보안/잠금 결정(D-P1-1~5)이 모두 실제 코드 +
캐시 무효화 후 재실행한 통합 테스트로 확인됨. 토큰 수명주기 4개 상태전이(발급/미회전 갱신/하드삭제
무효화)는 Testcontainers MySQL 대상 DB 직접 관측으로 행동 증명. 신원·토큰 발급이 헥사고날 규약
(domain 순수 POJO, ports/adapters)대로 착지했고, 신뢰경계 특권필드 차단(D-P1-2)·시크릿 fail-fast
(D-P1-3)가 회귀로 고정됨. 취소 코어 4개 서비스는 무변경(diff로 확인).

**참고(스코프 밖, gap 아님):** D-P1-3 처분대로 실 시크릿 주입 채널(k3s Secret manifest)은 Phase 2/
deployment로 이관 — 이 phase는 env-var 계약 + fail-fast 회귀만 전달. AUTHZ-01/GATE-01~03은 Phase 2/3.

---

_Verified: 2026-07-30T11:35:00Z_
_Verifier: Claude (gsd-verifier)_
