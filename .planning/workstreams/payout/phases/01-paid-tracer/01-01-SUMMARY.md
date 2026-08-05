---
phase: 01-paid-tracer
plan: 01
subsystem: settlement-service / payout
status: complete
tags: [payout, settlement, tracer, flyway-v2, redisson-scheduler, webhook, idempotent-convergence]
requires:
  - settlement v1 (Settlement/SettlementRepository, FINALIZED net_amount)
  - Redisson + spring.data.redis + @EnableScheduling (already wired by reconcile)
provides:
  - merchant_payout_account + payout tables (Flyway V2)
  - PayoutService.approve (FINALIZED → PROCESSING + submit)
  - PayoutResultService.applyResult chokepoint (webhook + poll convergence)
  - BankTransferPort + @Profile(local) mock + @Profile(!local) stub
  - PayoutPollScheduler (Redisson-locked backstop)
affects:
  - settlement-service only (INV-01: payment/order/product/merchant untouched)
tech-stack:
  added: []          # zero new dependencies
  patterns:
    - status-guarded native @Modifying UPDATE (clone SettlementJpaRepository.finalizeOpen)
    - Redisson tryLock scheduler with pure logic split (clone SettlementReconcileScheduler)
    - constant-time secret compare (MessageDigest.isEqual) in-controller, no Spring Security
    - transfer_ref = "PO-"+settlementId (deterministic, single INSERT, no circular id dep)
key-files:
  created:
    - settlement-service/src/main/resources/db/migration/V2__create_payout.sql
    - settlement-service/src/main/java/com/example/settlement/domain/entity/Payout.java
    - settlement-service/src/main/java/com/example/settlement/domain/entity/MerchantPayoutAccount.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/BankTransferPort.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/PayoutRepository.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/MerchantPayoutAccountRepository.java
    - settlement-service/src/main/java/com/example/settlement/application/service/PayoutService.java
    - settlement-service/src/main/java/com/example/settlement/application/service/PayoutResultService.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/PayoutJpaEntity.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/PayoutJpaRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/PayoutRepositoryImpl.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantPayoutAccountJpaEntity.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantPayoutAccountJpaRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantPayoutAccountRepositoryImpl.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/http/MockBankTransferClient.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/http/BankTransferHttpClient.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/scheduler/PayoutPollScheduler.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/PayoutAccountController.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/PayoutController.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/PayoutCallbackController.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/PayoutAccountRequest.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/PayoutResponse.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/PayoutCallbackRequest.java
    - settlement-service/src/test/java/com/example/settlement/integration/PayoutTracerIntegrationTest.java
    - settlement-service/src/test/java/com/example/settlement/integration/PayoutPollIntegrationTest.java
    - settlement-service/src/test/java/com/example/settlement/http/MockBankTransferClientTest.java
  modified:
    - settlement-service/src/main/java/com/example/settlement/infrastructure/config/PersistenceConfig.java
    - settlement-service/src/main/resources/application.yml
decisions:
  - transfer_ref = "PO-"+settlementId (not auto-increment id) — single INSERT, uk_payout_settlement guarantees uniqueness
  - webhook secret via X-Bank-Signature header, constant-time compared; 401 returned directly (not thrown — handler would swallow to 500)
  - PayoutService.upsertAccount is @Transactional so the @Modifying native upsert has an execution context (bug found & fixed during tracer IT)
metrics:
  duration_min: 65
  completed: 2026-08-04
  tasks: 2
  commits: 4
  tests_new: 6
  tests_total_module: 35
---

# Phase 1 Plan 01: Paid Tracer Summary

Payout end-to-end happy path wired through every layer of settlement-service: set a merchant payout account, approve a FINALIZED settlement into a PROCESSING payout submitted to a mock bank, and converge PROCESSING→PAID via a signature-verified webhook, with a Redisson-locked poll backstop funneling into the same status-guarded `applyResult` chokepoint. Zero new dependencies; payment/order/product/merchant and settlement v1 logic untouched (INV-01).

## What was built

- **Flyway V2** (`V2__create_payout.sql`): `merchant_payout_account` (client-set PK) + `payout` (`uk_payout_settlement`, `idx_payout_status`). Money `DECIMAL(19,2)`, status `VARCHAR(20)` no DB enum. V1 unchanged. Boots clean under `ddl-auto=validate`.
- **Domain + persistence**: `Payout`/`MerchantPayoutAccount` POJOs (no JPA annotations), JPA entities byte-matching V2, ports + thin RepositoryImpls, two `@Bean` wires in `PersistenceConfig`.
- **BankTransferPort** + `@Profile("local")` `MockBankTransferClient` + `@Profile("!local")` `BankTransferHttpClient` (defaulted `@Value` so it instantiates in profile-less ITs; ITs substitute via `@MockitoBean`).
- **PayoutService.approve**: reads `settlement.net_amount` from settlement's own FINALIZED row (no payment HTTP), guards FINALIZED ∧ net>0 ∧ active account ∧ no existing payout, single INSERT PROCESSING (`transfer_ref=PO-{settlementId}`), then `submit` strictly after save.
- **PayoutResultService.applyResult**: the one convergence chokepoint — status-guarded native `UPDATE … WHERE transfer_ref=? AND status='PROCESSING'`; 0 rows = idempotent no-op. `pollStuckProcessing` funnels bank `getStatus` terminal results through the SAME method.
- **Controllers**: `PayoutAccountController` PUT, `PayoutController` POST approve, `PayoutCallbackController` webhook (constant-time `MessageDigest.isEqual` on `X-Bank-Signature`, 401 returned directly, class comment on the NetworkPolicy deploy gate).
- **PayoutPollScheduler**: clone of `SettlementReconcileScheduler` — `fixedDelay=60s`, new lock key `lock:scheduler:payout-poll`, no new Redisson bean.

## Task-by-task

| Task | Name | Commits | Verify |
|------|------|---------|--------|
| 1 (tracer) | account → approve → submit → webhook → PAID | `4b88ffb` foundation (compile-clean gate), `6f026b5` behavior+e2e | `PayoutTracerIntegrationTest` (2) + `MockBankTransferClientTest` (2) green |
| 2 (auto) | poll backstop scheduler | `a4f7a1f` | `PayoutPollIntegrationTest` (2) green |

**W1 mitigation honored**: Task 1 split into a foundation commit (25 files, gated by `compileJava`/`compileTestJava` clean) and a behavior+e2e commit, reducing ddl-auto=validate / wiring risk.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `upsertAccount` missing transaction context for `@Modifying` native upsert**
- **Found during:** Task 1 behavior verify — tracer IT account PUT returned 500 (`InvalidDataAccessApiUsageException: TransactionRequiredException`).
- **Cause:** `PayoutService.upsertAccount` called the `@Modifying` native `INSERT … ON DUPLICATE KEY UPDATE` without a surrounding transaction (unlike `SettlementConfigService.setRate`, which is `@Transactional`).
- **Fix:** annotated `upsertAccount` with `@Transactional`.
- **Files modified:** `PayoutService.java`
- **Commit:** `6f026b5`

No architectural deviations. `transfer_ref = "PO-"+settlementId` and header-based webhook signature were pre-resolved in the plan (RESEARCH Pitfall 3/4), not runtime deviations.

## Verification

- `./gradlew :settlement-service:test` — 35 tests, 0 failures, 0 errors (6 new payout + 29 pre-existing settlement, no regression).
- transfer_ref = `PO-{settlementId}` asserted in tracer IT; amount = net snapshot; `submit` invoked after save (`verify`).
- Mismatched signature → 401 with status unchanged (PROCESSING, paid_at null) asserted.
- Poll: stuck PROCESSING → getStatus PAID → PAID (paid_at set); fresh PROCESSING not selected.
- V2 boots under `ddl-auto=validate`; V1 unchanged.

## INV-01 (cancel-core / settlement-v1 invariance)

Every changed source path is under `settlement-service/` (plus `.planning/` + `docs/`, both allowlisted). No payment/order/product/merchant file touched. No settlement v1 logic file (reconcile, finalize, ledger, config, query, V1 migration) modified — verified via `git diff --name-only $(git merge-base main HEAD) HEAD`.

## Known Stubs

- `BankTransferHttpClient` (`@Profile("!local")`) is an intentional Phase-1 stub returning `accepted`/`PROCESSING` — real bank integration is a later phase. Not on any tested path (ITs use `@MockitoBean`; local runs use the `@Profile("local")` mock). Documented in the class Javadoc.

## Follow-ups (out of scope — Phase 2/3 per plan)

- 409-return-existing polish on duplicate approve (UK already enforces one-payout; PAY-02).
- Retry/DEAD transition + terminal-FAILED alerting (attempt_count/last_error columns already provisioned in V2).
- INV-01 git-diff CI gate script (Plan 03).

## Self-Check: PASSED

All created files present on disk; all three per-task commits (`4b88ffb`, `6f026b5`, `a4f7a1f`) exist in git history; no stray cloud-sync duplicate files (`* N.java`/`* N.sql`).
