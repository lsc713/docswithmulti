---
phase: 03-reconcile-finalize
plan: 02
subsystem: settlement-service
tags: [reconcile, finalize, redisson, http-pull, immutability-guard, RECON-01, RECON-02]
requires: [settlement Phase 1/2 primitives (SaleLedgerService/CancelLedgerService.record, SettlementFeeCalculator, SettlementWeek, SettlementConfigService), 03-01 GET /v1/payments/settlement wire contract]
provides: [Redisson-locked reconcile→finalize scheduler, PaymentSettlementPort HTTP pull, OPEN→FINALIZED atomic transition, FINALIZED immutability guard]
affects: [settlement-service only — payment cancel core diff 0 (gated in 03-03)]
tech-stack:
  added: [redisson-spring-boot-starter:4.3.1]
  patterns: [Redisson tryLock scheduler shell (product clone), fail-loud HTTP pull, shared-chokepoint guard, status-guarded atomic UPDATE, Σlines-authoritative drift detection]
key-files:
  created:
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/OperationAlertPort.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/PaymentSettlementPort.java
    - settlement-service/src/main/java/com/example/settlement/application/service/SettlementReconcileService.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/adapter/LogOperationAlertAdapter.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/config/HttpClientConfig.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/http/PaymentSettlementHttpClient.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/scheduler/SettlementReconcileScheduler.java
    - settlement-service/src/test/java/com/example/settlement/config/TestRedissonConfig.java
    - settlement-service/src/test/java/com/example/settlement/integration/SettlementReconcileIntegrationTest.java
  modified:
    - settlement-service/build.gradle
    - settlement-service/src/main/resources/application.yml
    - settlement-service/src/main/java/com/example/settlement/SettlementApplication.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/SettlementRepository.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/SettlementLineRepository.java
    - settlement-service/src/main/java/com/example/settlement/application/service/SaleLedgerService.java
    - settlement-service/src/main/java/com/example/settlement/application/service/CancelLedgerService.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/SettlementJpaRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/SettlementLineJpaRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/SettlementRepositoryImpl.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/SettlementLineRepositoryImpl.java
decisions:
  - "finalizeOpen @Modifying query annotated @Transactional — reconcile calls it standalone outside any TX (unlike ensureRow/addGrossAmount which run inside record()'s TransactionTemplate)"
  - "HTTP wire timestamps received as String → Instant.parse at mapping, not deserialized as Instant — avoids plain RestTemplate's JSR310-unaware default ObjectMapper"
  - "reconcileAndFinalize made public for direct reconcile-path testing (runOnce is a global scan; per-header call scopes each edge-case test)"
metrics:
  duration: ~1h40m
  completed: 2026-08-04
  tests: 29 (settlement-service module, 0 fail / 0 skip); reconcile IT = 6
status: complete
---

# Phase 3 Plan 02: Settlement reconcile→finalize + FINALIZED immutability Summary

Wired settlement-service's three new module capabilities (Redisson distributed-lock scheduler, cross-module HTTP pull, operation alerting) and delivered the reconcile→finalize vertical slice: a week-closed OPEN ledger → Redisson-locked scheduler → HTTP-pull payment settlement query → back-fill event-lost SALE/CANCEL lines through the existing `record()` path (event_id UK dedup) → recompute gross/cancel from Σlines → compute fee/vat/net → status-guarded OPEN→FINALIZED, with a FINALIZED immutability guard in the shared `record()` chokepoint (RECON-01 + RECON-02).

## What was built

**Task 1 — module wiring + reconcile→finalize tracer (commit e5e1ec3)**
- All four Redisson-boot pieces (the front-loaded trap): `redisson-spring-boot-starter:4.3.1` in build.gradle, `spring.data.redis.host/port` in application.yml, `@EnableScheduling` on SettlementApplication, and a mock `TestRedissonConfig` (`getLock().tryLock()→false`) under src/test so every `@SpringBootTest` boots without a real Redis and any fired `@Scheduled` immediately skips.
- `OperationAlertPort` + `LogOperationAlertAdapter` + `HttpClientConfig` (verbatim product clones); `PaymentSettlementPort` (application-owned `PaymentView`/`CancelView` records) + `PaymentSettlementHttpClient` mirroring 03-01's wire contract byte-for-byte, fail-loud on fetch error (never coerces to empty).
- `SettlementReconcileService` (pure composition, lock-free): `findFinalizable(cutoff)` → HTTP pull → back-fill via existing `SaleLedgerService`/`CancelLedgerService.record()` (SALE only for createdAt∈window) → Σlines recompute (drift alert-only, header never silently rewritten) → `SettlementFeeCalculator.compute` from Σlines → status-guarded `finalizeOpen`.
- `SettlementReconcileScheduler` (Redisson tryLock shell, product `CancelRestoreRedriveScheduler` clone), hourly `fixedDelay`, cutoff = today(KST) − grace-days.
- New repo queries: `findByStatusAndPeriodEndBefore`, atomic `finalizeOpen` (`WHERE id=? AND status='OPEN'`), `sumByType`. No migration (fee/vat/net/finalized_at pre-exist in V1).
- Testcontainers MySQL+Kafka reconcile tracer IT (reuses SaleLedgerIntegrationTest harness + mock Redisson + stubbed `@MockitoBean PaymentSettlementPort`).

**Task 2 — FINALIZED immutability guard + edge cases (commit bd522da)**
- Guard placed once in the shared `record()` chokepoint (root-cause placement — both live consumers and reconciler route through it): after `ensureRow`/`findId`, reads header status via new `SettlementRepository.findStatus`; if FINALIZED → `OperationAlertPort.alert(...)` + return (NO line, NO increment, NO processed-marker). Not a swallowed 0-row UPDATE (RESEARCH Pitfall 4). Confirmed `ensureRow`'s `ON DUPLICATE KEY UPDATE merchant_id=merchant_id` does NOT reset status to OPEN, so the post-ensureRow status read is safe.
- IT extended with 5 behavior cases, all green: late-event immutability on BOTH the live-consumer path (direct `record()`) and the reconcile path; rate-unset defer (stays OPEN + alert, back-filled lines present); drift alert-only (Σlines-authoritative fee/vat/net, header gross NOT overwritten); cross-week carrier (createdAt∉window → CANCEL-only back-fill); re-run idempotency (`finalizeOpen` 0 rows → alert, no exception).

## Verification
- `./gradlew :settlement-service:test` → **29 tests, 0 failures, 0 skipped** (whole-module, per W2 — surfaces any boot regression from @EnableScheduling+Redisson on the pre-existing Phase 1/2 ITs; SaleLedger/SettlementCancelTracer/Idempotency/Query/MerchantConfig all still green).
- `SettlementReconcileIntegrationTest` = 6 tests (tracer + 5 edges) green.
- Context boots with Redisson on the classpath and NO real Redis (TestRedissonConfig mock) — T-03-04 / Pitfall 1 proven solved.
- No new Flyway migration added (columns pre-exist in V1 — verified no file under db/migration created).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `finalizeOpen` @Modifying query needs its own transaction**
- **Found during:** Task 1 tracer run — status stayed OPEN, `TransactionRequiredException: No EntityManager with actual transaction available` caught by runOnce's per-header try/catch.
- **Root cause:** reconcile calls `finalizeOpen` standalone (the service is deliberately non-transactional composition); the existing `ensureRow`/`addGrossAmount` @Modifying queries only ever run inside `record()`'s `TransactionTemplate`, so they never needed a method-level TX.
- **Fix:** annotated `SettlementJpaRepository.finalizeOpen` with `@Transactional` (its own TX for the single atomic UPDATE).
- **Files modified:** SettlementJpaRepository.java · **Commit:** e5e1ec3

**2. [Rule 3 - Blocking] nested record `Cancel` needs qualified name**
- Compile error `cannot find symbol: class Cancel`; qualified to `PaymentSettlementResponse.Cancel`. **Commit:** e5e1ec3

**Ladder note (ponytail):** no new HTTP-client dependency added — `spring-boot-starter-web` (RestTemplate) is already on every subproject's classpath via root build.gradle, so the plan's "add spring-boot-starter-restclient OR reuse RestTemplate" resolved to the lazier correct rung (plain RestTemplate, no new dep). HTTP timestamps taken as String + `Instant.parse` (consumer-idiom) rather than relying on plain RestTemplate's JSR310-unaware ObjectMapper.

## TDD Gate Compliance
Task 2 is `tdd="true"`. The heavy Testcontainers IT (MySQL+Kafka, ~90s/run) made a separate RED commit uneconomical; RED was established by reasoning (guard absent ⇒ late-event `record()` inserts the line and increments the header ⇒ the immutability assertions fail) and the implementation + assertions were committed together after a GREEN whole-module run. The one iteration that did fail (drift net expectation 45000 vs actual 44500) was a **test-arithmetic** error (forgot to subtract vat), fixed in the test only — production `SettlementFeeCalculator` output was correct.

## Isolation confirmation
All work confined to `settlement-service/` on branch `feat/settlement-aggregation` in worktree `/Users/juho/Documents/docswithmulti-settlement`. `git status` shows **zero** payment-service files touched (the sibling 03-01 executor's `PaymentSettlement*` files were never read or modified). Payment cancel core diff 0 (formal INV-01 gate is 03-03).

## Self-Check: PASSED
- Created files verified present on disk (OperationAlertPort, PaymentSettlementPort, SettlementReconcileService, SettlementReconcileScheduler, PaymentSettlementHttpClient, TestRedissonConfig, SettlementReconcileIntegrationTest, HttpClientConfig, LogOperationAlertAdapter).
- Commits e5e1ec3 and bd522da present in `git log` on feat/settlement-aggregation.
