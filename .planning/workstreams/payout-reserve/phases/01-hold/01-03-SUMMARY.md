---
phase: 01-hold
plan: 03
subsystem: settlement-service
tags: [verification, inv-01, gate, flyway, no-regression]
requires: ["01-01", "01-02"]
provides: [INV-01-gate-pass]
affects: []
tech-stack:
  added: []
  patterns: [merge-base-confinement-gate, single-quote-glob-pathspec]
key-files:
  created:
    - .planning/workstreams/payout-reserve/phases/01-hold/01-03-VERIFICATION.md
  modified: []
decisions:
  - "Flyway assertion flipped vs payout 02-03: V3 PRESENT + no V4+ + V1/V2 unmodified + no payment migration (reserve legitimately adds V3)"
metrics:
  duration: ~20m
  completed: 2026-08-06
requirements: [INV-01]
status: complete
---

# Phase 01 Plan 03: INV-01 Gate (payout-reserve / 01-hold) Summary

Scripted, re-runnable `git merge-base HEAD main` gate proving reserve hold (Phase 1) is settlement-only with a single legitimate Flyway V3 and zero regression across 4 modules. Verification-only plan — no production code changed.

## What was proven

- **Confinement (settlement-only):** all 39 changed paths under `BASE...HEAD` (BASE=`44b9abb`) match `^(settlement-service/|\.planning/|docs/)`. Zero payment/order/product/merchant-limit/risk/user/gateway/frontend paths.
- **Cancel-core diff 0:** 7-module denylist empty; explicit grep for CancelTxWriter/CancelPaymentService/recovery+stock-release schedulers/cancel_event_outbox/cancel_request/ProductStockHttpClient/OutboxDataSourceConfig → 0 matches.
- **Flyway V3 flip:** migration dir = V1+V2+**V3**; V3 present, no V4+, V1/V2 unmodified, no payment migration. Gate emitted `INV01_PASS`.
- **Three-dot correctness:** main's post-branch cancel-approval commits (incl. payment V20) excluded — no leak.
- **No-regression:** `:payment :order :product :settlement :test` → 528/528 green (payment 319, order 56, product 83, settlement 70; 0 fail/err/skip). New reserve ITs (ReserveConfig/Hold/Query + ReserveCalculator = 15) green; payout hardening ITs (409-return-existing, retry/DEAD, convergence, poll, tracer) green — approve change did not regress 409/retry, payout=net backward-compat preserved.

## Deviations from Plan

None — plan executed exactly as written. Both tasks passed on first run. A transient MySQL "Connection refused" surfaced during Testcontainers lifecycle but was non-fatal (BUILD SUCCESSFUL, exit 0).

## Self-Check: PASSED

- VERIFICATION.md exists.
- Gate re-runnable and emits `INV01_PASS`.
- INV-01 SATISFIED.
