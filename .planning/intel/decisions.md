# Decisions (ADR intel)

No ADR-typed documents were present in this ingest set (0 of 48 classifications).

All architectural decisions in this project live embedded inside SPEC design
docs, DOC runbooks/post-mortems, and CLAUDE.md invariants rather than in
standalone ADR files. There are no LOCKED decisions, so no LOCKED-vs-LOCKED
checks fired and precedence between contradicting sources falls back to the type
ordering ADR > SPEC > PRD > DOC.

Notable decision-shaped content extracted elsewhere for provenance:

- payment.cancelled publish mechanism evolution (Outbox → AFTER_COMMIT → inline
  TX3) — see the WARNING in INGEST-CONFLICTS.md and context.md "Messaging
  evolution". Same-tier SPEC-vs-SPEC contradiction; not auto-resolvable by
  precedence.
- OUTBOX poller livelock fix (dedicated DataSource, PR #59) — recorded in
  context.md ("Load-test / observability") and as a SPEC constraint
  (outbox-poller-dedicated-datasource).
- CDC/Debezium deferred (YAGNI) — recorded in context.md
  (outbox-poller-livelock post-mortem).

Nothing to auto-resolve at the ADR tier.
