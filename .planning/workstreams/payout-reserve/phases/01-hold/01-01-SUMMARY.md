---
phase: 01-hold
plan: 01
subsystem: settlement-service
status: complete
tags: [reserve, payout, flyway, hold, backward-compat]
requires:
  - settlement-service payout v1.0 approve flow (non-@Transactional, 409-race catch)
  - merchant_settlement_config config stack (clone target)
provides:
  - merchant_reserve_config + reserve tables (Flyway V3)
  - ReserveCalculator (pure): min(round(net×rate,2,HALF_UP), max(0,cap−held))
  - ReserveRepository (currentHeld SUM + insertHeld own @Transactional)
  - PayoutService.approve reserve deduction (payout=net−reserve, HELD row)
affects:
  - PayoutService.approve (additive wiring; approve stays non-@Transactional)
tech-stack:
  added: []
  patterns:
    - native ON DUPLICATE KEY upsert (config)
    - COALESCE(SUM(amount),0) null-safe aggregation (currentHeld)
    - deterministic transfer_ref RSV-{settlementId} (uk_reserve_settlement)
    - separate @Transactional @Modifying durable write inside non-@Transactional approve
key-files:
  created:
    - settlement-service/src/main/resources/db/migration/V3__create_reserve.sql
    - settlement-service/src/main/java/com/example/settlement/domain/entity/Reserve.java
    - settlement-service/src/main/java/com/example/settlement/domain/entity/MerchantReserveConfig.java
    - settlement-service/src/main/java/com/example/settlement/domain/service/ReserveCalculator.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/ReserveRepository.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/MerchantReserveConfigRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/ReserveJpaEntity.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/ReserveJpaRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/ReserveRepositoryImpl.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantReserveConfigJpaEntity.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantReserveConfigJpaRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantReserveConfigRepositoryImpl.java
    - settlement-service/src/test/java/com/example/settlement/unit/ReserveCalculatorTest.java
    - settlement-service/src/test/java/com/example/settlement/integration/ReserveHoldIntegrationTest.java
  modified:
    - settlement-service/src/main/java/com/example/settlement/infrastructure/config/PersistenceConfig.java
    - settlement-service/src/main/java/com/example/settlement/application/service/PayoutService.java
decisions:
  - "approve() stays non-@Transactional; reserve HELD INSERT is a separate @Transactional @Modifying durable write after the payout INSERT succeeds — a race loser DIVEs at the payout insert and never writes a reserve row"
  - "reserve compute is read-only BEFORE the payout insert (so payoutAmount is known); the reserve row insert is AFTER (so losers orphan nothing)"
  - "cap enforcement is best-effort per-approve (ponytail): concurrent same-merchant approvals may transiently overshoot reserve_cap — no merchant lock added"
  - "current_held = COALESCE(SUM),0) WHERE status IN ('HELD','RELEASING'); RELEASING has no rows until Phase 2 (forward-compat)"
metrics:
  tasks: 2
  commits: 4
  files: 16
  tests_total: 64
  tests_new: 9
  duration_min: 27
  completed: 2026-08-06
---

# Phase 1 Plan 01: 유보 홀드 tracer Summary

Reserve V3 data model + config/reserve persistence stack + pure `ReserveCalculator` + `PayoutService.approve` reserve deduction — merchants with an active reserve policy are paid `net − reserve` with a `HELD` reserve row (`transfer_ref=RSV-{settlementId}`, `hold_until=today(KST)+holdDays`); merchants without a policy are paid `net` with no reserve row (backward-compatible). `approve()` remains non-`@Transactional` and the existing 409-race catch is byte-for-byte unchanged.

## What was built

**Task 1 — data model + persistence + calculator + approve wiring (2 commits, W2 split):**

1. **Foundation** (`92e23c2`): Flyway `V3__create_reserve.sql` (`merchant_reserve_config` + `reserve`, spec §3 DDL, V1/V2 untouched); annotation-free domain POJOs `Reserve`/`MerchantReserveConfig`; JPA entities matching V3 exactly for `ddl-auto=validate` (`DECIMAL(19,2)`/`(5,4)`, `hold_until DATE`→`LocalDate`, `status VARCHAR(20)`); `ReserveJpaRepository` (`currentHeld` COALESCE(SUM), `insertHeld` own `@Transactional @Modifying`) + config upsert (`ON DUPLICATE KEY`); ports + Impls; `ReserveCalculator` pure `min(round(net×rate,2,HALF_UP), max(0,cap−held))`; `PersistenceConfig` +2 `@Bean`. Compiled clean (`compileJava`+`compileTestJava`) before wiring approve.
2. **approve-hold + IT** (`67a19df`): wired reserve deduction into `PayoutService.approve` — read config+Σheld → `payoutAmount=net−reserve` → guard `payoutAmount>0` → payout INSERT(`payoutAmount`, existing DIVE→409 catch unchanged) → if `reserve>0` reserve `HELD` INSERT → `submit(payoutAmount)`. Constructor gained 2 deps. `ReserveHoldIntegrationTest` happy path.

**Task 2 — edge unit tests + regression IT (`bad18fa`):** `ReserveCalculatorTest` (6 cases: HALF_UP round-up, exact multiple, cap clamp, cap exhausted→0, config-absent→0, rate 0→0); `ReserveHoldIntegrationTest` +2 cases (no-config→payout=net + 0 rows; cap-exhausted seeded `held≥cap`→reserve=0, payout=net, no new row).

## Verification results

- `./gradlew :settlement-service:test` — **BUILD SUCCESSFUL, 64 tests, 0 failures / 0 errors / 0 skipped** (17 suites).
- New: `ReserveCalculatorTest` 6/6, `ReserveHoldIntegrationTest` 3/3.
- Boot `ddl-auto=validate` passed against V3 (no `SchemaManagementException`).
- Backward-compat: all existing payout ITs (`PayoutApproveHardening`, `PayoutTracer`, `PayoutConvergence`, `PayoutQuery`, `PayoutRetry`, `PayoutPoll`) green with **no assertion changes** — they seed no reserve config → reserve=0 → payout=net by construction.
- Tracer feedback gate: tracer `<verify>` passed end-to-end before expansion tasks.

## Deviations from Plan

None — plan executed exactly as written. The W2 mitigation (split Task 1 into foundation + approve commits) was followed; compile-clean gate met before approve wiring.

## Settlement-only + payout-core-unchanged confirmation

- **settlement-only**: all 16 files under `settlement-service/`; no payment/order/product/merchant/risk/user/gateway changes. Flyway confined to settlement `V3` (V1/V2 unchanged, no V4+).
- **payout core unchanged**: `approve()` remains non-`@Transactional`; the `catch (DataIntegrityViolationException)` → 409-return-existing block is unchanged (only the INSERT amount arg changed `net`→`payoutAmount`, and reserve INSERT + submit-amount added around it). Existing payout IT amount assertions untouched.
- No new dependencies. `BankTransferPort` reused; reserve is HELD-only (no reserve transfer — release is Phase 2).

## Commits

- `92e23c2` feat(01-01): reserve V3 model + config/reserve persistence + ReserveCalculator (foundation)
- `67a19df` feat(01-01): wire reserve deduction into PayoutService.approve (payout=net−reserve, HELD)
- `bad18fa` test(01-01): ReserveCalculator edges + no-config/cap-exhausted IT + payout no-regression

## Self-Check: PASSED

All created files exist on disk; all 3 task commits present in git history.
