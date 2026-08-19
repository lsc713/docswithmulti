---
phase: 01-user-service
plan: 02
subsystem: auth
tags: [jwt, refresh, logout, fail-fast, spring-security, testcontainers, docker-compose, spring-boot-4]

requires:
  - phase: 01-01
    provides: user-service module + AuthService.refresh/logout + JwtAuthenticationFilter + SecurityConfig(logout=authenticated) + AuthIntegrationTest 골격
provides:
  - AUTH-03 통합 검증 (유효 refresh→새 access 미회전 / 무효 refresh→401)
  - AUTH-04 통합 검증 (Bearer logout→refresh 하드삭제→재갱신 401)
  - JwtSecretFailFastTest (비-local JWT_SECRET 미주입 시 컨텍스트 기동 실패 회귀, D-P1-3)
  - docker-compose mysql-user 서비스 (user_db, host 3315) — 로컬 앱 실행용 배선 (D-P1-5)
affects: [auth-gateway phase 02 (deployment / k3s Secret 주입), gateway JWT verification]

tech-stack:
  added: []  # 신규 프로덕션 코드/의존성 없음 — 이식된 refresh/logout 경로의 통합 검증 + 로컬 배선만
  patterns:
    - "통합 e2e: MockMvc(WebApplicationContext + springSecurity) + Testcontainers MySQL + JdbcTemplate 로 DB 상태 직접 관측 (refresh_tokens row count)"
    - "fail-fast 회귀: ApplicationContextRunner + ConfigDataApplicationContextInitializer 로 실 application.yml 로드, PropertySourcesPlaceholderConfigurer 로 실 Boot의 @Value 재귀 placeholder 해석/미해결 예외 재현 (DB 비의존)"

key-files:
  created:
    - user-service/src/test/java/com/example/user/infrastructure/security/JwtSecretFailFastTest.java
  modified:
    - user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java
    - docker-compose.yml

key-decisions:
  - "fail-fast 테스트는 full @SpringBootTest 대신 ApplicationContextRunner + PSPC 로 DB 없이 placeholder-미해결 기동실패를 격리 검증 (실 Boot @Value 재귀 해석 동작 재현)"
  - "AUTH-03 만료 refresh 케이스는 통합 중복 생략 — 이식된 AuthServiceTest 단위(shouldThrowOnExpiredRefreshToken)가 이미 커버"

patterns-established:
  - "docker-compose 신규 DB 서비스는 main mysql-payment 블록 형식 복제 + volumes 섹션 등록; host 포트는 기존 3307~3311과 무충돌 배정"

requirements-completed: [AUTH-03, AUTH-04]

coverage:
  - id: D6
    description: "AUTH-03: 유효 refresh 제출 → 200 + 새 access(subject 일치), 응답 refreshToken=null(미회전 D-P1-1), refresh_tokens 행 1개 유지"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java#refreshIssuesNewAccessWithoutRotationAndRejectsInvalid"
        status: pass
    human_judgment: false
  - id: D7
    description: "AUTH-03: 조작/미존재 refresh 문자열 → 401 (InvalidToken); 만료 케이스는 AuthServiceTest 단위 커버"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java#refreshIssuesNewAccessWithoutRotationAndRejectsInvalid"
        status: pass
      - kind: unit
        ref: "user-service/src/test/java/com/example/user/application/service/AuthServiceTest.java#RefreshTests.shouldThrowOnExpiredRefreshToken"
        status: pass
    human_judgment: false
  - id: D8
    description: "AUTH-04: Bearer access로 logout → 200 (JwtAuthenticationFilter가 principal=userId 세팅, authenticated 경로 e2e), refresh_tokens 행 0 (하드 DELETE)"
    requirement: "AUTH-04"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java#logoutHardDeletesRefreshAndBlocksSubsequentRefresh"
        status: pass
    human_judgment: false
  - id: D9
    description: "AUTH-04: 로그아웃에 사용된 refresh로 재갱신 → 401 (무효화 최종 관측)"
    requirement: "AUTH-04"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java#logoutHardDeletesRefreshAndBlocksSubsequentRefresh"
        status: pass
    human_judgment: false
  - id: D10
    description: "D-P1-3: 비-local 프로파일 + JWT_SECRET 미주입 → ApplicationContext 기동 실패 (fail-fast); local은 dev 기본값으로 기동"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/infrastructure/security/JwtSecretFailFastTest.java#nonLocalProfileWithoutSecretFailsFast"
        status: pass
      - kind: integration
        ref: "user-service/src/test/java/com/example/user/infrastructure/security/JwtSecretFailFastTest.java#localProfileStartsWithDevDefault"
        status: pass
    human_judgment: false
  - id: D11
    description: "D-P1-5: docker-compose.yml mysql-user 서비스 (mysql:8.0, user_db, 3315:3306, mysql-user-data 볼륨); config 유효"
    requirement: "AUTH-04"
    verification:
      - kind: automated
        ref: "docker compose config | grep -A15 'mysql-user:' | grep -q '\"3315\"'"
        status: pass
    human_judgment: false

duration: ~13min
completed: 2026-07-30
status: complete
---

# Phase 1 Plan 2: AUTH-03/04 통합 검증 + JWT_SECRET fail-fast + mysql-user 배선 Summary

**Wave 1 tracer 위에서 토큰 갱신(미회전)·로그아웃 무효화를 실 HTTP+DB(Testcontainers)로 관통 검증하고, 비-local JWT_SECRET 미주입 기동실패를 회귀로 고정했으며, 로컬 실행용 docker-compose mysql-user(3315)를 배선했다. 신규 프로덕션 코드 0 — 이식된 경로의 검증·배선 전용.**

## Performance
- **Duration:** ~13 min
- **Completed:** 2026-07-30
- **Tasks:** 2 (T1 통합 검증 · T2 fail-fast + compose)
- **Files:** 3 (AuthIntegrationTest 확장, JwtSecretFailFastTest 신규, docker-compose.yml)

## Accomplishments
- **AUTH-03** 통합 확정: signup 발급 refresh를 `POST /v1/auth/refresh`로 제출 → 200 + 새 access(jjwt 파싱 subject 일치), 응답 `refreshToken=null`(미회전 D-P1-1), `refresh_tokens` 행은 1개 유지(재발급 없음). 조작 refresh(존재하지 않는 UUID) → 401. 만료 케이스는 이식된 `AuthServiceTest` 단위가 이미 커버하므로 통합 중복 생략.
- **AUTH-04** 통합 확정: login access를 `Authorization: Bearer`로 실어 `POST /v1/auth/logout` → 200 (JwtAuthenticationFilter가 principal=userId 세팅해야 authenticated 통과 — permitAll 아님을 e2e로 확인, T-01-08). 직후 `refresh_tokens` 해당 user 행 0개(하드 DELETE), 그 refresh로 재갱신 → 401 (무효화 최종 관측, T-01-06 창 축소).
- **D-P1-3** fail-fast 회귀 고정: 비-local 프로파일 + JWT_SECRET 미주입 시 placeholder 미해결로 컨텍스트 기동 실패, local 프로파일은 dev 기본값으로 기동. `ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`로 실 `application.yml`을 로드하고 `PropertySourcesPlaceholderConfigurer`로 실 Boot의 `@Value` 재귀 해석/미해결 예외 동작을 재현 — DB 비의존, 하드코딩 시크릿 없음(CLAUDE.md 준수). T-01-07 회귀.
- **D-P1-5** 로컬 배선: `docker-compose.yml`에 `mysql-user`(mysql:8.0, user_db, user/user, `3315:3306`, `mysql-user-data` 볼륨) 추가 + volumes 섹션 등록. 기존 3307~3311 무충돌, 01-01 application.yml 3315와 정렬. Testcontainers 통합테스트는 자체 컨테이너를 띄우므로 이 서비스에 비의존(`docker compose up` 로컬 앱 실행 전용).
- `./gradlew :user-service:build` 그린 — 전체 스위트(단위+통합+fail-fast) Boot 4.0.5/Security 7에서 통과.

## Task Commits
1. **Task 1: AUTH-03/04 통합 검증 시나리오** — `c46a148` (test)
2. **Task 2: JWT_SECRET fail-fast 회귀 + docker-compose mysql-user 배선** — `0884dca` (test)

## Files Created/Modified
- `user-service/src/test/java/com/example/user/integration/AuthIntegrationTest.java` — AUTH-03/04 실 HTTP+DB 시나리오 2건 추가(refresh 미회전·무효 401 / Bearer logout 하드삭제·재갱신 401)
- `user-service/src/test/java/com/example/user/infrastructure/security/JwtSecretFailFastTest.java` — 신규 fail-fast 회귀(비-local 기동실패 / local 기동성공 대조)
- `docker-compose.yml` — `mysql-user` 서비스 + `mysql-user-data` 볼륨

## Decisions Made
- **fail-fast 테스트 격리 전략**: full `@SpringBootTest`(DataSource/Flyway → DB 필요)를 피하고 `ApplicationContextRunner` + `PropertySourcesPlaceholderConfigurer`로 placeholder-미해결 기동실패만 DB 없이 격리 검증. 실 Boot 컨텍스트의 `@Value` 재귀 해석 동작을 PSPC로 동일 재현하여 신뢰도 확보.
- **AUTH-03 만료 refresh 통합 중복 생략**: 이식된 `AuthServiceTest.shouldThrowOnExpiredRefreshToken` 단위가 커버 — 플랜 지시대로 통합 경계만 보강.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Task 2 verify 명령 grep 윈도우가 정규화된 compose 출력에 부족**
- **Found during:** Task 2 (verify 실행)
- **Issue:** 플랜의 `docker compose config | grep -A6 'mysql-user' | grep -q '3315'`는 `docker compose config`가 서비스 필드를 알파벳 재정렬해 정규화하면서 `published: "3315"`가 `mysql-user:` 라인보다 6줄 넘게 아래로 밀려 `-A6` 윈도우 밖으로 나가 실패(fail). 아티팩트(compose)는 정확 — 검증 명령 자체의 윈도우 가정 오류.
- **Fix:** 서비스명 앵커 + 넉넉한 윈도우로 교정: `docker compose config | grep -A15 'mysql-user:' | grep -q '"3315"'` → pass. compose 유효성(`docker compose config` exit 0)도 그린.
- **Files modified:** 없음 (검증 명령만 교정; docker-compose.yml 아티팩트는 플랜대로 정확)
- **Verification:** 교정 명령 pass, `docker compose config` valid, mysql-user@3315 배선 확인.
- **Commit:** 아티팩트 변경 없음 — 검증 절차 교정 (0884dca에 compose 포함)

---

**Total deviations:** 1 auto-fixed (Rule 1 - verify 명령 grep 윈도우; 아티팩트 무결). **Impact:** 프로덕션/아티팩트 편차 0. 플랜 verify 명령의 `-A6`가 Docker 정규화 재정렬을 미고려한 것뿐 — done-criteria 의도(mysql-user 3315 배선 + config 유효)는 완전 충족.

## Threat Model Coverage
- **T-01-06** (refresh 재사용): AUTH-04 logout 하드삭제 무효화를 Task 1 시나리오 3/4가 e2e 고정 — ✅
- **T-01-07** (JWT_SECRET 미설정 배포): JwtSecretFailFastTest가 비-local 미주입 기동실패를 회귀로 고정 — ✅
- **T-01-08** (logout 무인증 접근): Bearer 경로 e2e로 authenticated 확인(permitAll 아님) — ✅

## Issues Encountered
None. (fail-fast 테스트 초기 설계에서 최소 러너가 `@Value` 단일-레벨 해석만 해 기동실패를 재현 못함 → PSPC 추가로 실 Boot 동작 재현하여 태스크 내 해소; 플랜 편차 아님.)

## Next Phase Readiness
- AUTH-01~04 전부 통합 검증 완료 — Phase 1 user-service 인증 슬라이스 기능 확정.
- D-P1-3 처분대로 k3s Secret manifest(실 시크릿 주입 채널)는 Phase 2/deployment 관심사로 이관됨 — 이 phase는 env-var 계약 + fail-fast 회귀만 전달(스코프 준수).
- `docker compose up` 로컬 앱 실행 시 mysql-user(3315) 사용 가능; JWT_SECRET은 local 프로파일 dev 기본값 자동 공급, 비-local 배포에는 주입 필수(미주입 시 설계상 fail-fast).

---
*Phase: 01-user-service · Plan: 02*
*Completed: 2026-07-30*

## Self-Check: PASSED
- Key files verified on disk: JwtSecretFailFastTest.java (신규), AuthIntegrationTest.java (확장), docker-compose.yml (mysql-user).
- Commits verified: `c46a148` (T1 test), `0884dca` (T2 test) — 둘 다 feat/auth-gateway HEAD 계보에 존재.
- `./gradlew :user-service:build` 그린 — 전체 스위트 통과 (AUTH-03/04 통합 + fail-fast 포함).
- `docker compose config` valid; mysql-user@3315 배선 확인.
- `.planning/` gitignored → SUMMARY/STATE 미커밋(실 소스만 원자 커밋). user-service 작업트리 클린.
