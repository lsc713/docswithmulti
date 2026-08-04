---
phase: 02-net
plan: 03
subsystem: settlement-service
status: complete
tags: [settlement, inv-01, verification-gate, merge-base, denylist-allowlist, no-regression, cancel-core]
requires:
  - "02-01 SALE tracer committed on feat/settlement-aggregation (payment.completed outbox + V19)"
  - "02-02 fee calculator committed on feat/settlement-aggregation"
provides:
  - "Scripted, re-runnable INV-01 (Phase 2) merge-base gate: cancel CORE + stock diff 0 denylist / creation-path allowlist [INV-01]"
  - "02-03-VERIFICATION.md recording denylist/allowlist diff scope + 4-module suite counts"
affects: []
tech-stack:
  added: []
  patterns:
    - "merge-base BASE=git merge-base HEAD main → git diff BASE...HEAD denylist(diff 0)/allowlist(only-permitted) split gate"
    - "BSD/macOS-safe: single-quoted glob pathspecs, --name-only, grep -vE with [[:space:]] (never \\s)"
    - "duplicate build/ .sql cleanup before every gradle/Flyway run to avoid checksum failure"
key-files:
  created:
    - .planning/workstreams/settlement/phases/02-net/02-03-VERIFICATION.md
  modified: []
decisions:
  - "INV-01 re-scoped for Phase 2: NOT payment diff 0 (creation path intentionally changed) but cancel CORE + stock diff 0; creation-path outbox is the allowlist"
metrics:
  duration: ~11m
  completed: 2026-08-04
---

# Phase 2 Plan 3: INV-01 (Phase 2 re-scope) gate + 4-module no-regression Summary

Scripted merge-base gate proves the SALE slice's payment-creation change stayed disjoint from the cancel CORE: cancel + stock denylist is **diff 0** vs `git merge-base HEAD main`, only the 10-file creation-path allowlist changed, V19 has no collision, and all four module suites are green (447 tests, 0 failures; payment-service fresh-verified).

## What was done

- **Task 1 — INV-01 (Phase 2) merge-base gate → `INV01_PASS`.** `BASE=583ffee`, `HEAD=4c902c9`.
  - Denylist (CancelPaymentService/CancelTxWriter, 4 recovery/retry services, 5 schedulers, 3 cancel publishers, cancel_event_outbox/cancel_request/stock-release/compensation persistence, ProductStockHttpClient, OutboxDataSourceConfig, cancel domain/service+policy, migrations V1–V18): **diff 0**.
  - Allowlist: 10/10 changed payment-service paths in the permitted creation-path set (PaymentCreateTxWriter + PaymentEventOutbox*/PaymentCompletedOutbox* + application.yml + V19); **0 unexpected**.
  - V19 the only migration ≥19 → no Flyway collision. `PaymentCreateTxWriter` diff confirmed purely additive (outbox INSERT + payload builder), no cancel line touched.
- **Task 2 — no-regression → BUILD SUCCESSFUL.** payment 307 / order 35 / product 82 / settlement 23 = **447 tests, 0 failures/errors/skips**. payment-service re-run fresh (`--rerun-tasks`, 8m41s, not cached) — cancel/stock/recovery/outbox ITs all green.

## Deviations from Plan

None — plan executed exactly as written. Verification/gate plan only; no production code changed.

## Verification

- INV-01 gate: `INV01_PASS` (Task 1 `<verify><automated>`, re-runnable).
- Four-module `./gradlew` suite: BUILD SUCCESSFUL.
- 02-03-VERIFICATION.md records denylist/allowlist scope + per-module counts.

## Threat Flags

None. Threat register T-02-06/07/08 all mitigated by the gate (denylist+allowlist, V19 collision surfacing, build/ .sql cleanup); T-02-SC accepted (no new deps).

## Self-Check: PASSED

- `.planning/workstreams/settlement/phases/02-net/02-03-VERIFICATION.md` — FOUND
- `.planning/workstreams/settlement/phases/02-net/02-03-SUMMARY.md` — FOUND
