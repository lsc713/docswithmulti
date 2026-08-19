---
phase: 1
slug: product-service
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-30
---

# Phase 1 — Validation Strategy

> product-service 신규 모듈. Per-task map은 planner가 채운다.

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito + Testcontainers (MySQL) — 프로젝트 관행 |
| **Config file** | product-service/build.gradle (신규) |
| **Quick run command** | `./gradlew :product-service:test` |
| **Full suite command** | `./gradlew :product-service:build` (Boot 4 컴파일 + 전체 테스트) |

## Sampling Rate

- **After every task commit:** Run the task's `<automated>` command.
- **After every plan wave:** `./gradlew :product-service:build`
- **Before verify:** Full suite green.

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| P01-T1 스캐폴드+설정+예외 | 01-01 | 1 | STOCK-01 | 신규 의존성 없음(T-01-SC) | 컴파일 게이트 | `./gradlew :product-service:compileJava` | build.gradle, application.yml, ErrorCode, GlobalExceptionHandler | ⬜ pending |
| P01-T2 V1스키마+reserve/seed 슬라이스(tracer) | 01-01 | 1 | STOCK-01, STOCK-02, STOCK-03 | 원자 조건부 UPDATE 오버셀 방지(T-01-01), qty 검증(T-01-02) | Integration (Testcontainers MySQL) | `./gradlew :product-service:test --tests "com.example.product.integration.StockTracerIntegrationTest"` | V1__create_product_core.sql, StockTracerIntegrationTest | ⬜ pending |
| P02-T1 reserve 멱등(INSERT-ON-DUPLICATE 게이트)+다중아이템 원자 롤백 | 01-02 | 2 | STOCK-03, STOCK-04 | 멱등 재차감/race-loser 500 방지(T-02-03, W1), 전-items 원자 | Integration (Testcontainers MySQL) | `./gradlew :product-service:test --tests "com.example.product.integration.StockIdempotencyIntegrationTest"` | StockIdempotencyIntegrationTest | ⬜ pending |
| P02-T2 release(원자 조건부 상태전이·no-op) | 01-02 | 2 | STOCK-04 | over-release 방지 — 동시 이중 release 복원 1회(T-02-02, W2) | Integration + 동시 이중 release (Testcontainers MySQL) | `./gradlew :product-service:test --tests "com.example.product.integration.StockReleaseIntegrationTest"` | StockReleaseIntegrationTest | ⬜ pending |
| P02-T3 동시 reserve 오버셀 부재 + 같은키 멱등 burst(핵심) | 01-02 | 2 | STOCK-03, STOCK-04 | 동시성 오버셀 방지(T-02-01, critical) + 동시 same-key 멱등(W1) | Integration 동시성 (RANDOM_PORT + JDK HttpClient + Testcontainers) | `./gradlew :product-service:test --tests "com.example.product.integration.StockConcurrencyIntegrationTest"` | StockConcurrencyIntegrationTest | ⬜ pending |

## Wave 0 Requirements

- [x] product-service 모듈 스캐폴드 + build.gradle + Testcontainers 설정 → Plan 01-01 Task 1이 생성(Wave 1 진입 태스크). settings.gradle include·루트 build.gradle 테스트 의존성은 기존재.

## Manual-Only Verifications

*reserve 오버셀·멱등은 Testcontainers 통합테스트로 자동 커버 목표.*

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] 오버셀 방지(동시 reserve 원자성) 검증 포함
- [ ] No watch-mode flags
- [ ] `nyquist_compliant: true` set

**Approval:** pending
