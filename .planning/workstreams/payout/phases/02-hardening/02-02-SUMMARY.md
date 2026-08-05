---
phase: 02-hardening
plan: 02
subsystem: settlement-service (payout)
status: complete
tags: [payout, retry, dlq, dead-letter, alert-once, scheduler, RETRY-01]
requires:
  - "Phase-1 payout slice: PayoutResultService.applyResult / pollStuckProcessing, PayoutPollScheduler, PayoutJpaRepository, MerchantPayoutAccountRepository"
  - "02-01 (PAY-02 409) complete"
provides:
  - "PayoutRepository.claimForRetry / markDeadIfExhausted / findRetryableFailed"
  - "PayoutResultService.retryFailed() (retry→resubmit→DEAD+alert-once)"
  - "payout.status terminal value DEAD (PROCESSING|PAID|FAILED|DEAD)"
  - "payout.retry.max-attempts / grace-seconds config"
affects:
  - "PayoutPollScheduler tick (now also runs retryFailed under same Redisson lock)"
tech-stack:
  added: []
  patterns:
    - "status-guarded atomic UPDATE returning rowcount (clone of applyResult/finalizeOpen)"
    - "terminal DEAD status idiom (cancel-restore DLQ), exactly-once via rowcount==1 guard"
key-files:
  created:
    - settlement-service/src/test/java/com/example/settlement/integration/PayoutRetryIntegrationTest.java
  modified:
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/PayoutJpaRepository.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/PayoutRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/PayoutRepositoryImpl.java
    - settlement-service/src/main/java/com/example/settlement/application/service/PayoutResultService.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/scheduler/PayoutPollScheduler.java
    - settlement-service/src/main/resources/application.yml
decisions:
  - "Terminal state = new DEAD value in existing status VARCHAR(20) — NO Flyway migration (ddl-auto=validate checks column shape, not values; no CHECK constraint)"
  - "Alert-once enforced by guarded FAILED→DEAD transition being the rowcount==1 winner, not a flag/timestamp heuristic"
  - "Inactive account mid-retry = skip+warn, left FAILED (inert, never claimed → attempt_count never burns → never DEAD). Ceiling noted with ponytail comment"
metrics:
  duration: "~40m"
  completed: 2026-08-05
  tasks: 2
  files: 7
  tests: "6 new (PayoutRetryIntegrationTest); 55 total settlement-service, all green"
---

# Phase 2 Plan 2: Payout FAILED Retry → DEAD + Alert-Once (RETRY-01) Summary

FAILED payouts now self-heal up to a cap and surface unrecoverable ones exactly once, entirely
by reusing the Phase-1 status-guarded atomic-UPDATE idiom and the shipped cancel-restore DEAD-status
idiom — no new Flyway migration, no new scheduler bean, zero new dependencies.

## What was built

**Task 1 — two guarded UPDATEs + repo plumbing + config** (commit `5ea02c4`)
- `PayoutJpaRepository.claimForRetry(ref, max)`: `UPDATE payout SET status='PROCESSING', attempt_count=attempt_count+1, updated_at=CURRENT_TIMESTAMP(3) WHERE transfer_ref=:ref AND status='FAILED' AND attempt_count < :max` → int rowcount.
- `PayoutJpaRepository.markDeadIfExhausted(ref, max, err)`: `UPDATE payout SET status='DEAD', last_error=:err, updated_at=CURRENT_TIMESTAMP(3) WHERE transfer_ref=:ref AND status='FAILED' AND attempt_count >= :max` → int rowcount.
- Both `@Modifying(flushAutomatically=true, clearAutomatically=true)` `@Transactional`, cloning `applyResult` shape. Predicates disjoint (`< :max` vs `>= :max`).
- `PayoutRepository`/`Impl` expose `claimForRetry`, `markDeadIfExhausted`, and `findRetryableFailed(cutoff)` (delegates to existing `findByStatusAndUpdatedAtBefore("FAILED", cutoff)`, mirroring `findStuckProcessing`).
- `application.yml`: `payout.retry.max-attempts: 5`, `payout.retry.grace-seconds: 60`.

**Task 2 — retryFailed() wired into existing poll tick + integration test** (RED `6b9cdc6`, GREEN `9ac696a`)
- `PayoutResultService` gains `MerchantPayoutAccountRepository` + `OperationAlertPort` constructor deps (both already beans) and `@Value` `retryMax` / `retryGraceSeconds`.
- `retryFailed()`: `cutoff = now - retryGraceSeconds`; `findRetryableFailed(cutoff)`; per-row try/catch:
  - `attempt_count < max`: `accountRepo.findActive(merchantId)` — empty → warn + `continue` (row stays FAILED, not claimed). Present → `claimForRetry`; submit same `transfer_ref` **only when rowcount==1**.
  - `attempt_count >= max`: `markDeadIfExhausted`; `operationAlertPort.alert(...)` **only when rowcount==1**, message includes transfer_ref + attempt.
- `PayoutPollScheduler.run()` calls `retryFailed()` right after `pollStuckProcessing()` inside the same Redisson lock — no new `@Bean`, no `@EnableScheduling`.

## New repo methods + config
| Symbol | Location | Purpose |
|--------|----------|---------|
| `int claimForRetry(String, int)` | PayoutRepository / Jpa / Impl | atomic FAILED→PROCESSING attempt++, rowcount==1 = claim |
| `int markDeadIfExhausted(String, int, String)` | PayoutRepository / Jpa / Impl | atomic FAILED→DEAD, rowcount==1 = alert once |
| `List<Payout> findRetryableFailed(Instant)` | PayoutRepository / Impl | FAILED + updated_at < cutoff (grace backoff) |
| `void retryFailed()` | PayoutResultService | the retry pass invoked from the poll tick |
| `payout.retry.max-attempts` (=5) | application.yml | retry cap; >= → DEAD |
| `payout.retry.grace-seconds` (=60) | application.yml | FAILED backoff before eligible |

## Tests
`PayoutRetryIntegrationTest` (Testcontainers MySQL, `@MockitoBean BankTransferPort` + `OperationAlertPort`) — 6 tests, all green:
1. FAILED attempt=2 (<5), past grace → PROCESSING, attempt_count 2→3, `submit(eq("PO-901"),…)` `times(1)`, `never().alert`.
2. FAILED attempt=5 (>=5) → DEAD, `verify(operationAlertPort, times(1)).alert(contains("PO-902"))`, `never().submit`.
3. **No re-alert**: two `retryFailed()` passes on an at-cap row → DEAD + `times(1).alert` (DEAD never re-matches FAILED select).
4. **No double-resubmit**: two `retryFailed()` passes on one under-cap FAILED row → claimed once, `submit(eq("PO-904"),…)` `times(1)`, attempt 2→3.
5. Fresh FAILED (updated_at=now, inside grace) → not selected, stays FAILED attempt unchanged, no submit/alert.
6. No active account → skip+warn, left FAILED, no claim/DEAD/submit/alert.

Full `:settlement-service:test`: **55 tests, 0 failures, 0 errors, 0 skipped**.

## Deviations from Plan
None — plan executed exactly as written. (TDD RED verified via test-compile failure on the missing `retryFailed()` symbol rather than a full container run, to avoid a wasteful Testcontainers spin-up on known-non-compiling code.)

## Guardrail compliance
- **No new Flyway migration** — migration dir still `V1__create_settlement_core.sql`, `V2__create_payout.sql`. DEAD is a new string in the existing `status VARCHAR(20)`; no CHECK/enum; ddl-auto=validate passes.
- **No new scheduler bean** — `retryFailed()` runs in the existing `PayoutPollScheduler` tick under the same lock key.
- **Disjoint guards** — `claimForRetry` (WHERE status='FAILED') and `applyResult` (WHERE status='PROCESSING') never touch the same row-state; Phase-1 CONFIRM-03 convergence unbroken.
- **Settlement-only** — `git diff --name-only 27f935b..HEAD` touches only `settlement-service/`; no payment/order/product/merchant. Cloud-sync `* N.sql` purge run before every `./gradlew`.

## Threat mitigations applied
- T-02-03 (double-resubmit): atomic `claimForRetry`, submit only when rowcount==1 (test 4).
- T-02-04 (alert storm): exactly-once via guarded FAILED→DEAD, alert only inside rowcount==1 (test 3).
- T-02-05 (stray migration / validate break): DEAD as existing-column value, no migration.

## Known Stubs
None.

## Self-Check: PASSED
- Files exist: PayoutRetryIntegrationTest.java, PayoutJpaRepository.java, PayoutResultService.java, PayoutPollScheduler.java (all verified via Edit/Write success).
- Commits present: 5ea02c4 (feat repo+config), 6b9cdc6 (test RED), 9ac696a (feat retryFailed).
