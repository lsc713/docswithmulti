---
phase: 01-attribute-dictionary-variant-core
plan: 02
subsystem: product-service
tags: [invariant-gate, verification-only, INV-01, stock, cancel-restore]
requires: [01-01]
provides: [INV-01-gate-pass]
affects: []
tech-stack:
  added: []
  patterns: [git-diff-merge-base-invariant-gate, flyway-additive-migration-gate]
key-files:
  created: []
  modified: []
decisions: []
metrics:
  duration: ~7m (test run 6m17s)
  completed: 2026-08-03
status: complete
---

# Phase 01 Plan 02: INV-01 Invariance Gate Summary

Mechanically proved Phase 1 (attribute/variant catalog additions) touched zero stock/cancel-restore code: guarded-path git diff vs merge-base is empty, only Flyway V8 was added, and the full 74-test product-service suite passes with no regression. Verification-only — no production code changed, no commits made.

## What This Plan Did

This is a gate, not an implementation. It ran three independent checks against `merge-base(HEAD, main) = 5e36155` and confirmed all green.

### Gate 1 — Guarded stock/cancel source diff = 0

`git diff --name-only "$BASE"...HEAD` restricted to the guarded pathspec (single-quoted globs, passed unexpanded) returned **empty**:

- `application/service/StockService.java`
- `application/service/ProcessCancelledStockService.java`
- `application/service/CancelRestoreRedriveService.java`
- `application/service/OrphanReservationRecoveryService.java`
- `infrastructure/persistence/ProductStock*` · `StockReservation*` · `CancelRestoreDlq*` · `ProcessedCancelEvent*`
- `infrastructure/messaging/` (consumers, RetryRouter, payloads, DLQ)
- `infrastructure/scheduler/`
- `presentation/controller/StockController.java`

Combined automated assertion emitted: **`INV_DIFF_GATE_PASS`**.

### Gate 2 — Migration additive (V8 only)

`git diff --name-only "$BASE"...HEAD -- product-service/src/main/resources/db/migration/`:

```
product-service/src/main/resources/db/migration/V8__create_attribute_variant.sql
```

Single file. Explicit V1~V7 diff (`V1__create_product_core` … `V7__create_product_image`) returned **empty** — no existing migration touched.

### Gate 3 — Full regression (no regression)

`find . -path '*/build/*' -name '* [0-9].sql' -delete` (removed cloud-sync duplicate SQL — none present this run) then `./gradlew :product-service:test --rerun-tasks`.

**BUILD SUCCESSFUL in 6m 17s.** Aggregated from 23 JUnit result files:

```
TOTAL tests=74  failures=0  errors=0  skipped=0
```

Matches the 01-01 baseline of 74 tests. Guarded suites all green and unregressed:

| Suite | tests |
|-------|-------|
| Stock tracer (seed→reserve→oversell prevention) | 1 |
| Stock reserve idempotent + multi-item atomic rollback | 2 |
| Stock release atomic conditional transition | 4 |
| Concurrent reserve no-oversell + idempotent burst | 2 |
| Cancel restore tracer (reserve→cancel event→restore) | 1 |
| Cancel restore idempotency / partial-cancel | 2 |
| Cancel restore loss-free (retry send fail→redeliver) | 1 |
| Cancel restore convergence (handler fail→DLQ→resolve) | 1 |
| Cancel restore DEAD escalation + NonRetryable→DLQ | 3 |
| Orphan reservation recovery (scan→exists→release/skip) | 4 |
| RetryRouter | 3 |
| Product browse / Category taxonomy / Browse price / Seed price | 11 + 7 + 2 + 1 |
| New Phase-1 attribute/variant/tracer suites | 5 + 5 + 1 |
| Product image / query / storage | 10 + 2 + 4 + 1 |

Docker daemon confirmed up before the run (Testcontainers precondition met).

## Deviations from Plan

None — plan executed exactly as written. No source files reverted (nothing violated the gate).

## Known Stubs

None. Verification-only plan.

## Threat Flags

None. T-01-04 (unauthorized mutation of stock/cancel paths) is the mitigation this gate enforces — the empty guarded-path diff is its mechanical proof.

## Gate Command Evidence

```
BASE=5e36155d62b8018dfa9629b76ebc9eedcec8e01f
guarded source diff  → (empty)
V1~V7 migration diff  → (empty)
combined assertion    → INV_DIFF_GATE_PASS
db/migration new file → V8__create_attribute_variant.sql (only)
./gradlew :product-service:test --rerun-tasks → BUILD SUCCESSFUL (6m17s)
tests=74 failures=0 errors=0 skipped=0
```

## Self-Check: PASSED

- Verification-only plan — no files created/modified, no commits expected or made.
- Gate evidence captured from live command output above.
- INV-01 satisfied: stock/cancel diff 0, migration V8-only, full suite green.
