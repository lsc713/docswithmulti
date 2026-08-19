---
phase: 1
slug: user-service
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-30
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Seeded by plan-phase; per-task map filled by planner / validate-phase against RESEARCH.md §Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito + Testcontainers (MySQL) |
| **Config file** | user-service/build.gradle (신규) |
| **Quick run command** | `./gradlew :user-service:test --tests '*AuthServiceTest'` |
| **Full suite command** | `./gradlew :user-service:build` (Boot 4 컴파일 + 전체 테스트) |
| **Estimated runtime** | 단위 <30s · 통합(Testcontainers MySQL) 첫 실행 ~1–2min(이미지 pull 포함) |

---

## Sampling Rate

- **After every task commit:** Run the task's `<automated>` command (per-task map 아래).
- **After every plan wave:** Run `./gradlew :user-service:build`
- **Before `/gsd-verify-work`:** Full suite (`:user-service:build`) must be green
- **Max feedback latency:** 단위 task < 30s; 통합 검증 task < 2min

---

## Per-Task Verification Map

*(planner가 PLAN.md task 분해 후 채운다.)*

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| T1 (checkpoint) | 01-01 | 1 | AUTH-01/02 | — | Flyway V1 스키마 계약 승인 (one-way) | manual gate | — (checkpoint:decision) | ⬜ new | ⬜ pending |
| T2 스캐폴드+이식+조정 | 01-01 | 1 | AUTH-01, AUTH-02 | T-01-01, T-01-02, T-01-03 | signup role 서버강제(USER) · JWT_SECRET dev기본값 제거 · BCrypt · application.yml 8085/3315 정렬(D-P1-5) | compile + config grep | `./gradlew :user-service:compileJava && grep -Eq 'port:\s*8085' user-service/src/main/resources/application.yml && grep -q '3315/user_db' user-service/src/main/resources/application.yml` | ⬜ new (port) | ⬜ pending |
| T3 통합테스트 (tracer) | 01-01 | 1 | AUTH-01, AUTH-02 | T-01-01 | signup→login→access+refresh e2e · role 자기지정 무시 회귀 | integration (Testcontainers) | `./gradlew :user-service:build` | ⬜ new AuthIntegrationTest (+이식 단위테스트) | ⬜ pending |
| T1 refresh/logout 통합 | 01-02 | 2 | AUTH-03, AUTH-04 | T-01-06, T-01-08 | 미회전 갱신 · 무효 refresh 401 · logout 삭제 후 401 | integration | `./gradlew :user-service:test --tests '*AuthIntegrationTest' --tests '*AuthServiceTest'` | ⬜ extend + ✅이식 AuthServiceTest | ⬜ pending |
| T2 fail-fast + compose | 01-02 | 2 | AUTH-03, AUTH-04 | T-01-07 | 비-local JWT_SECRET 미주입 시 fail-fast · mysql-user 3315 배선(app 3315와 정렬) | integration + config | `docker compose config \| grep -A6 'mysql-user' \| grep -q '3315' && ./gradlew :user-service:test --tests '*JwtSecretFailFastTest'` | ⬜ new JwtSecretFailFastTest | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] user-service 모듈 스캐폴드 + build.gradle + Testcontainers 설정 → Plan 01 Task 2 (신규 모듈 — 기존 인프라 없음)
- [x] signup role 서버강제(D-P1-2) 검증 케이스 → Plan 01 Task 3 AuthIntegrationTest 시나리오 3 (RESEARCH Wave 0 gap)
- 참고: PORT 단위테스트(AuthServiceTest/JwtTokenProviderTest/AuthControllerTest/RepositoryImplTest)는 참조 브랜치에 이미 존재 → 신규 작성 아님(이식). 신규 작성 = AuthIntegrationTest + JwtSecretFailFastTest 2개뿐.

---

## Manual-Only Verifications

*All phase behaviors target automated verification (회원가입/로그인/갱신/로그아웃은 통합테스트로 커버).*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency acceptable
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
