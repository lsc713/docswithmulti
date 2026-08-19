---
phase: 2
slug: api-gateway-jwt
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-30
---

# Phase 2 — Validation Strategy

> Per-phase validation contract. Per-task map filled by planner against RESEARCH.md §Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test (MVC gateway) + WireMock (downstream stub) |
| **Config file** | api-gateway/build.gradle (신규) |
| **Quick run command** | `./gradlew :api-gateway:test` |
| **Full suite command** | `./gradlew :api-gateway:build` (Boot 4 + Spring Cloud 2025.1.x 컴파일 + 테스트) |
| **Estimated runtime** | planner 확정 |

---

## Sampling Rate

- **After every task commit:** Run the task's `<automated>` command.
- **After every plan wave:** Run `./gradlew :api-gateway:build`
- **Before `/gsd-verify-work`:** Full suite green.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-T1 | 02-01 | 1 | GATE-02 (D-P2-2) | T-02-01 | 신뢰 헤더 이름 계약 승인 (one-way, Phase3 소비 계약) | checkpoint:decision | — (blocking gate) | n/a | ⬜ pending |
| 02-01-T2 | 02-01 | 1 | GATE-01 (D-P2-1,6) | T-02-SC | 모듈 스캐폴드 + Spring Cloud BOM↔Boot 4.0.5 정합 + 무상태(JPA/Flyway 제외) | build | `./gradlew :api-gateway:compileJava` | ❌ Wave 0 | ⬜ pending |
| 02-01-T3 | 02-01 | 1 | GATE-01, GATE-02 | T-02-01, T-02-02, T-02-04 | 유효 JWT → payment 라우팅 → downstream이 X-User-Id/X-User-Role 수신 (end-to-end) | integration | `./gradlew :api-gateway:build` (`*GatewayRoutingIT` happy) | ❌ Wave 0 | ⬜ pending |
| 02-02-T1 | 02-02 | 2 | GATE-01 (D-P2-5) | T-02-01 | user 공개 3경로(필터 없음)+인증 logout 라우트, order/merchant/risk 미노출, 전 경로 strip | build | `./gradlew :api-gateway:compileJava` | ❌ Wave 0 | ⬜ pending |
| 02-02-T2 | 02-02 | 2 | GATE-03, GATE-02 | T-02-01, T-02-02, T-02-03, T-02-06 | 401 3종(누락/무효/만료, downstream 무호출) + strip 스푸핑 회귀 + 공개경로 통과/logout 401 | integration | `./gradlew :api-gateway:test --tests '*GatewayRoutingIT'` | ❌ Wave 0 | ⬜ pending |
| 02-02-T3 | 02-02 | 2 | GATE-03 (D-P2-4,7) | T-02-03, T-02-05 | JwtVerifier 서명/만료/변조/alg 단위 + error-catalog TOKEN_* 401 등록 | unit | `./gradlew :api-gateway:test --tests '*JwtVerifierTest'` | ❌ Wave 0 | ⬜ pending |

---

## Wave 0 Requirements

- [ ] api-gateway 모듈 스캐폴드 + build.gradle(Spring Cloud BOM) + WireMock 테스트 설정 (신규 모듈) — 02-01-T2/T3
- [ ] `GatewayRoutingIT.java` — @SpringBootTest(RANDOM_PORT) + WireMock 스텁: 라우팅·헤더주입·strip·401 (02-01 happy + 02-02 확장)
- [ ] `JwtVerifierTest.java` — verify-only 파싱: 동일 secret accept, 위조/만료/변조 reject (02-02-T3)
- [ ] 테스트 HTTP 클라이언트: TestRestTemplate 미제공 대비 JDK `HttpClient`/`RestClient`
- [ ] 테스트용 유효/만료 토큰 헬퍼: application.yml local 기본값과 동일 secret으로 jjwt builder(HS256)

---

## Manual-Only Verifications

*라우팅/필터/401/헤더 strip은 통합테스트(WireMock downstream)로 자동 커버 목표.*

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies (checkpoint T1 제외 — blocking gate)
- [x] Sampling continuity 확보 (task commit → `:api-gateway:test`, wave merge → `:api-gateway:build`)
- [x] Wave 0 covers all MISSING references (GatewayRoutingIT, JwtVerifierTest, WireMock, token helper)
- [x] No watch-mode flags
- [ ] `nyquist_compliant: true` set (executor가 Wave 0 파일 생성 후 확정)

**Approval:** planner-filled (per-task map complete)
