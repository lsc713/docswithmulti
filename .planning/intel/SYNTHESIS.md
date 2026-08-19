# Ingest Synthesis Summary

Mode: new (net-new bootstrap, no existing .planning project files)
Precedence: ADR > SPEC > PRD > DOC (no per-doc overrides present)
Cross-ref graph: acyclic (prior two cycles resolved) — all docs synthesized.

## Doc counts by type

- ADR: 0
- SPEC: 20 (all synthesized)
- PRD: 1
- DOC: 27 (all synthesized)
- UNKNOWN: 0
- Total classifications consumed: 48

## Decisions locked

- 0 ADRs, 0 locked decisions. See decisions.md (decisions live embedded in
  SPECs/DOCs/CLAUDE.md, not standalone ADRs).

## Requirements extracted

- 1: REQ-scale-blog-series (editorial deliverable). See requirements.md.

## Constraints extracted (20)

- api-contract: 5 (api-spec, error-catalog, merchant-limit-service-design,
  risk-management-service plan + design)
- protocol: 8 (kafka-design, order-service-design, payment-scheduler-design,
  scheduler-enhancement-design, kafka-publish-pattern-benchmark plan + design,
  outbox-poller-dedicated-datasource plan + design)
- nfr: 6 (loadtest-whitebox-observability-design, cancel-history-batch-design,
  saturation-diagnosis-kit plan + design, system-views-dashboard-design,
  k3s-scaleout-design)
- schema: 1 (oncall-skills-target-abstraction-design)

## Context topics (27 DOCs)

- Project foundations / conventions: 7
- Cancel-flow build plans: 6
- Messaging evolution (historical): 3
- Load-test / observability / capacity: 7
- k3s scale-out: 4
- Tooling / content: 2 (partially overlaps above; see context.md for the
  authoritative grouping)

## Conflicts

- Blockers: 0
- Competing variants (warnings): 1 (payment.cancelled publish mechanism,
  SPEC-vs-SPEC same-tier — needs user decision)
- Auto-resolved / info: 2
- Detail: ../INGEST-CONFLICTS.md

## Per-type intel files

- Decisions: ./decisions.md
- Requirements: ./requirements.md
- Constraints: ./constraints.md
- Context: ./context.md
- Conflicts report: ../INGEST-CONFLICTS.md

## Status

AWAITING USER — 0 blockers, but 1 competing variant (payment.cancelled publish
mechanism, same-tier SPEC-vs-SPEC) needs a user decision before routing. See
INGEST-CONFLICTS.md.
