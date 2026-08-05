---
phase: 01-paid-tracer
plan: 03
subsystem: settlement-service / payout (INV-01 gate)
tags: [payout, inv-01, confinement, merge-base-gate, no-regression, flyway]
requires: ["01-01", "01-02"]
provides: ["INV-01 settlement-only gate (INV01_PASS)", "4-module no-regression proof (501/501 green)"]
affects: [settlement-service]
tech-stack:
  added: []
  patterns: ["merge-base confinement gate (git diff BASE...HEAD, single-quoted BSD globs)", "exactly-V2 Flyway assertion", "cloud-sync duplicate .sql purge before build"]
key-files:
  created:
    - .planning/workstreams/payout/phases/01-paid-tracer/01-03-VERIFICATION.md
  modified: []
decisions:
  - "Confinement regex is simply ^(settlement-service/|\\.planning/|docs/) — NO PaymentSettlement allowlist token (unlike settlement reconcile 03-03). Payout reads net from settlement's own row and never calls payment (spec §8), so payment stays diff 0 as a plain denylist, not an allowlisted read-surface."
  - "Verification/gate plan — zero production code changes; the gate FAILS loudly rather than editing code to pass."
  - "Duplicate cloud-sync .sql/.java/.class copies under */build/* purged before both the gate and gradle to avoid Flyway checksum / duplicate-version failures (T-01-10/11)."
metrics:
  duration: "~25m (18m of it the 4-module suite)"
  completed: "2026-08-05"
status: complete
---

# Phase 1 Plan 03: INV-01 Settlement-Only Gate + 4-Module No-Regression Summary

Scripted, re-runnable merge-base gate proving the payout slice is a pure `settlement-service/` addition (+ `.planning/` · `docs/`) with the payment cancel core and all six other modules at diff 0, exactly one settlement Flyway migration (V2) added, and the full four-module suite green (501/501) — turning the "payout is settlement-only" structural claim into an enforced invariant, INV-01.

## What was verified

### Task 1 — INV-01 merge-base confinement gate → `INV01_PASS`
- **BASE** = `git merge-base HEAD main` = `baf9935`.
- **Confinement:** 46 changed files, all under `settlement-service/` (38) · `.planning/` (6) · `docs/` (2). No payment/order/product/merchant-limit/risk-management/user/api-gateway/frontend/root path. `NON_SETTLEMENT_DIFF` empty.
- **Module denylist:** seven non-settlement module dirs diff 0 (`MODULE_DIFF` empty). Cancel-core denylist grep (CancelTxWriter/CancelPaymentService/recovery+stock-release schedulers/cancel_event_outbox/cancel_request/ProductStockHttpClient/OutboxDataSourceConfig/payment migrations) = 0 hits.
- **Flyway:** settlement dir = exactly `V1__create_settlement_core.sql` + `V2__create_payout.sql`; V2 present; no `V3+`; V1 unchanged; no payment migration added.

### Task 2 — 4-module no-regression → all green
`./gradlew :payment-service:test :order-service:test :product-service:test :settlement-service:test` → `BUILD SUCCESSFUL in 18m 13s` (exit 0).

| Module | tests | fail | err | skip |
|--------|------:|----:|----:|----:|
| payment-service | 319 | 0 | 0 | 0 |
| order-service | 56 | 0 | 0 | 0 |
| product-service | 83 | 0 | 0 | 0 |
| settlement-service | 43 | 0 | 0 | 0 |
| **total** | **501** | **0** | **0** | **0** |

Regression guards confirmed green: payment cancel core / stock (`CancelPaymentServiceTest`, `CancelTxWriterTest`, `ProcessingRecoveryOutboxIT`, `StockReleaseRetryServiceTest`, …); settlement Phase 1/2/3 (`SaleLedger`, `SettlementCancelTracer`, `SettlementReconcile`, `SettlementIdempotency`, `SettlementQuery`, `MerchantSettlementConfig`); new payout ITs (`PayoutTracer`, `PayoutPoll`, `PayoutQuery`, `PayoutConvergence`, `MockBankTransferClientTest`).

## Deviations from Plan

None — plan executed exactly as written. Gate produced `INV01_PASS` on first run; suite green on first run. No production code changed (verification/gate plan).

## INV-01 result

**SATISFIED.** Settlement-only confinement + seven-module diff 0 + cancel-core diff 0 + exactly-V2 Flyway (V1 unchanged, no payment migration) + full four-module suite green.

## Self-Check: PASSED
- `01-03-VERIFICATION.md` exists at `.planning/workstreams/payout/phases/01-paid-tracer/`.
- INV01 gate re-run in-session printed `INV01_PASS`.
- Suite exit code 0, per-module counts read from JUnit XML.
