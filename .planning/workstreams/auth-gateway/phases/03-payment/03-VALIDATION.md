---
phase: 3
slug: payment
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-30
---

# Phase 3 — Validation Strategy

> Per-phase validation contract. Per-task map filled by planner against RESEARCH.md §Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito (standaloneSetup MockMvc) — payment-service 기존 관행 |
| **Config file** | payment-service/build.gradle (기존, spring-security 미추가) |
| **Quick run command** | `./gradlew :payment-service:test` |
| **Full suite command** | `./gradlew :payment-service:test` |
| **Core-unchanged gate** | `BASE=$(git merge-base HEAD origin/main); git diff --name-only "$BASE" -- payment-service/src/main` 가 `(CancelPaymentService\|CancelTxWriter\|CancelDomainService\|CancelHistoryRecorder)\.java\|/scheduler/\|/messaging/` 미포함 (base는 로컬 main 아님 — origin/main과의 merge-base) |

---

## Sampling Rate

- **After every task commit:** Run the task's `<automated>` command.
- **After every plan wave:** Run `./gradlew :payment-service:test`
- **Before `/gsd-verify-work`:** Full suite green + core-unchanged gate.

---

## Per-Task Verification Map

*(planner가 채운다.)*

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-1 | 03-01 | 1 | AUTHZ-01 | T-03-03/04 | 인가 클래스 컴파일, domain POJO 순수 | compile | `./gradlew :payment-service:compileJava` | ❌ new | ⬜ pending |
| 01-2 | 03-01 | 1 | AUTHZ-01 | T-03-01/03 | USER→403 취소 진입 전 차단 + 취소 코어 diff 0 | web(standalone) + gate | `./gradlew :payment-service:test --tests '*CancelControllerTest'` + merge-base(origin/main) 기준 core 부재 | ⚠️ 확장 | ⬜ pending |
| 02-1 | 03-02 | 2 | AUTHZ-01 | T-03-02/03 | 인가 매트릭스 6종 exhaustive | unit(domain) | `./gradlew :payment-service:test --tests '*CancelAuthorizerTest'` | ❌ new | ⬜ pending |
| 02-2 | 03-02 | 2 | AUTHZ-01 | T-03-02/04 | ADMIN 로드 생략 / MERCHANT 로드·404 / 비정상 헤더 403 | unit(Mockito) | `./gradlew :payment-service:test --tests '*CancelAuthorizationServiceTest'` | ❌ new | ⬜ pending |
| 02-3 | 03-02 | 2 | AUTHZ-01(SC#1) | T-03-01 | ADMIN 취소 기존 플로우 정상 처리 + 정책 문서화 | web(standalone) + gate | `./gradlew :payment-service:test --tests '*CancelControllerTest'` + merge-base(origin/main) 기준 core diff 게이트 | ⚠️ 확장 | ⬜ pending |

---

## Wave 0 Requirements

- [ ] 인가 매트릭스 domain 단위테스트 + CancelController authz 테스트(기존 standaloneSetup 확장)

---

## Manual-Only Verifications

*인가 매트릭스(ADMIN/MERCHANT match·mismatch/USER/누락)는 domain 단위 + 컨트롤러 테스트로 자동 커버.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] 취소 코어 불변 게이트(git diff --name-only) 포함
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] `nyquist_compliant: true` set

**Approval:** pending
