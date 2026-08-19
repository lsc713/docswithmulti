## Conflict Detection Report

### BLOCKERS (0)

No blockers. The two cross-ref cycles from the prior run were resolved by
dropping one back-reference each (outbox-poller-livelock no longer refs
publish-pattern-benchmark; saturation-diagnosis no longer refs
saturation-diagnosis-kit-design). The cross-ref graph is now acyclic and all 48
docs were synthesized (nothing held back). No ADR-typed docs and no LOCKED
decisions, so no LOCKED-vs-LOCKED checks fired. No UNKNOWN/low-confidence docs.

### WARNINGS (1)

[WARNING] Competing payment.cancelled publish mechanism across SPEC-tier docs
  Found: docs/superpowers/specs/2026-04-27-payment-scheduler-design.md specifies
    an Outbox publisher for payment.cancelled (dedicated OutboxPublisher scheduler).
  Found: docs/kafka-design.md specifies payment.cancelled published INLINE at end
    of TX3 (kafkaTemplate.send, key cancelRequestId) with NO outbox.
  Found (context): docs/superpowers/plans/2026-04-28-simplified-messaging.md uses
    an AFTER_COMMIT listener; docs/superpowers/plans/2026-04-30-tx3-inline-kafka.md
    switches to inline TX3 — an Outbox → AFTER_COMMIT → inline-TX3 evolution.
    docs/superpowers/specs/2026-07-11-kafka-publish-pattern-benchmark-design.md
    makes the mode runtime-togglable (INLINE / INLINE_ASYNC / OUTBOX), and
    docs/load-test/publish-pattern-benchmark.md is the runbook measuring them.
  Impact: Two same-tier SPECs contradict on the same scope (how payment.cancelled
    is published), neither locked and with no per-doc precedence override.
    Precedence rules give no automatic winner (no picking by timestamp/filename),
    so downstream routing could encode a superseded mechanism.
  → Confirm the authoritative mechanism (CLAUDE.md invariants + kafka-design.md
    say inline TX3) and mark payment-scheduler-design's Outbox path as superseded,
    or record an ADR so precedence is explicit. merchant.limit.updated is NOT in
    conflict (consistently Outbox, key merchantId, across all sources).

### INFO (2)

[INFO] No ADR-typed documents in ingest set
  Note: 0 of 48 docs classified ADR. No LOCKED decisions, so no LOCKED-vs-LOCKED
    checks fired and no decision-tier auto-resolution was needed. All
    architectural decisions live embedded in SPEC design docs / DOC post-mortems
    / CLAUDE.md. decisions.md records this and points to the embedded ones.

[INFO] Messaging-evolution DOCs retained as history, not applied
  Note: simplified-messaging (AFTER_COMMIT), tx3-inline-kafka (inline TX3), and
    the publish-pattern-benchmark runbook were extracted to context.md under
    "Messaging evolution" for provenance only. They are the intermediate/current
    steps of the mechanism flagged in the WARNING above; recorded for
    transparency, not auto-merged.
