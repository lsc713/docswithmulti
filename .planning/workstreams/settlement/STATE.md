---
workstream: settlement
created: 2026-08-03
---

# Project State

## Current Position

**Status:** In progress
**Current Phase:** 02-net
**Last Activity:** 2026-08-04
**Last Activity Description:** Executed 02-03-PLAN (INV-01 Phase 2 re-scope gate) — cancel CORE diff 0 denylist + creation-path allowlist (INV01_PASS), 4-module no-regression 447 tests green. Phase 2 complete.

## Progress

**Phases Complete:** 2
**Current Plan:** 03-01 (next phase)

## Decisions

- Query auth deferred to deploy-time NetworkPolicy (gateway-only ingress), not in-service
- Consumer auto-offset-reset=earliest (ledger back-fill), idempotent
- settlement_line native INSERT so UK race rolls back the atomic increment in the same TX

## Session Continuity

**Stopped At:** Completed 02-03-PLAN.md (Phase 2 done)
**Resume File:** None
