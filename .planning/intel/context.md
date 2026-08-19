# Context (DOC intel)

27 DOC-typed docs were classified; all 27 are extracted below (the cross-ref
cycles that previously held three back are resolved — graph is acyclic).

Grouped by topic. Each entry is the classifier distillation; full text at `source`.

---

## Project foundations / conventions

- source: /Users/juho/Documents/docswithmulti/docs/domain-rules.md
  Single source of business rules for payment cancellation: cancel
  eligibility, amount validation, merchant limits, Payment/PaymentItem +
  CancelRequest + Order state transitions, request_hash idempotency,
  compensation/EXHAUSTED policy. Load-bearing business constraints.

- source: /Users/juho/Documents/docswithmulti/docs/architecture.md
  System-wide design guide: layered architecture, multi-module topology,
  module-to-table ownership, inter-module communication (HTTP + Kafka), MySQL,
  Kafka. 5 modules incl. product-service (unimplemented).

- source: /Users/juho/Documents/docswithmulti/docs/conventions/architecture.md
  Layer structure + exception class hierarchy (BusinessException;
  domain/application/infrastructure/presentation layers).

- source: /Users/juho/Documents/docswithmulti/docs/db-schema.md
  DB conventions: Flyway rules, naming, UTC datetime handling, index strategy.
  Defers to per-module V1..V7 migration files as source of truth.

- source: /Users/juho/Documents/docswithmulti/docs/contributeing.md
  Coding standards: layer architecture, naming, Effective Java style, testing,
  Lombok policy, exception rules, commit message format.

- source: /Users/juho/Documents/docswithmulti/docs/agent.md
  Agent behavioral rules: pre-work checklist, TDD workflow, layer boundaries,
  error handling, testing, Definition of Done.

- source: /Users/juho/Documents/docswithmulti/docs/STATUS.md
  Implementation progress tracker: design/module completion checklist and
  messaging-design branch variants.

---

## Cancel flow implementation plans (build guides)

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-04-23-payment-cancel-service.md
  payment-service CancelPaymentService build plan: TX1→Risk→TX2→PG→TX3→Outbox,
  V8 DDL, request_hash idempotency, Risk/PG ports. (Contains SPEC-like DDL but
  framed as a work plan.)

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-04-23-merchant-limit-service.md
  merchant-limit-service build plan: internal/admin CRUD APIs, hexagonal
  layout, Kafka Outbox publishing, V1 migration.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-04-24-order-service.md
  order-service build plan: payment.cancelled consumer, OrderItem/Order sync,
  idempotency, 3x retry, DLQ.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-04-27-payment-scheduler.md
  4 payment-service schedulers build plan; OutboxPublisher fully built (TDD),
  other three skeletons; Redisson lock, Spring Kafka.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-04-28-scheduler-enhancement.md
  Add real recovery logic to pending-recovery + processing-recovery
  schedulers; V9 migration; CancelRequest.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-11-cancel-history-batch.md
  Batch cancel-request history writes via ThreadLocal buffer (per-cancel DB
  commits 6→4); CancelHistoryRecorder; REQUIRES_NEW transaction.

---

## Messaging evolution (historical — see WARNING in INGEST-CONFLICTS.md)

These DOCs record the payment.cancelled publish mechanism changing over time.
The authoritative topic contract (kafka-design.md) and CLAUDE.md invariants
state inline-TX3 publish. Earlier intermediate designs are retained here for
provenance only — NOT auto-applied.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-04-28-simplified-messaging.md
  Replaces Outbox with an AFTER_COMMIT TransactionalEventListener +
  failed_kafka_event retry; simplifies merchant.limit.updated payload.
  (Intermediate step.)

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-04-30-tx3-inline-kafka.md
  Replaces AFTER_COMMIT publishing with inline kafkaTemplate.send() at end of
  TX3, deletes failed_kafka_event machinery; CancelTxWriter; Flyway V11.
  (Current mechanism per CLAUDE.md.)

- source: /Users/juho/Documents/docswithmulti/docs/load-test/publish-pattern-benchmark.md
  Runbook comparing the 3 publish modes (INLINE / INLINE_ASYNC / OUTBOX) on an
  AWS rig for throughput, latency, and failure behavior. Measurement procedure
  behind the benchmark SPEC; reconciles the modes as runtime-selectable.

---

## Load-test / observability / capacity

- source: /Users/juho/Documents/docswithmulti/docs/load-test/measurement-journey.md
  Load-test methodology: layered observation metrics, network/AZ decisions, VU
  ramp-up staging for the cancel flow.

- source: /Users/juho/Documents/docswithmulti/docs/load-test/capacity-planning.md
  Capacity planning: measured ~190 rps knee vs demand/SLO; payment commit
  fsync bottleneck; open-model arrival rate; Hikari pool; hot-merchant
  contention.

- source: /Users/juho/Documents/docswithmulti/docs/load-test/aws-run-plan-2026-07.md
  AWS load-test run procedure: Phase O (observation smoke) + Phase M
  (throughput/consistency), realistic-mix, 3-config baseline, terraform, k6.

- source: /Users/juho/Documents/docswithmulti/docs/load-test/saturation-diagnosis.md
  Runbook for diagnosing the ~185 rps saturation ceiling: run procedure, USE
  decision tree, dashboards, and Tempo cross-check to locate the bottleneck.
  Pairs with the saturation-diagnosis-kit SPECs.

- source: /Users/juho/Documents/docswithmulti/docs/load-test/outbox-poller-livelock.md
  Post-mortem of an OUTBOX-poller livelock (shared Hikari pool starvation);
  adopted dedicated-DataSource fix (PR #59) and deferred CDC/Debezium (YAGNI).
  Rationale behind the outbox-poller-dedicated-datasource SPECs.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-10-loadtest-whitebox-observability.md
  Build plan for opt-in OTel tracing + per-request query-count metrics via a
  new common-observability module.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-11-system-views-dashboard.md
  Build plan for a Grafana dashboard with Inbound/App/Infra views reusing
  existing scraped signals (system-views.json).

---

## k3s horizontal scale-out

- source: /Users/juho/Documents/docswithmulti/docs/load-test/k3s-scaleout-results.md
  Empirical results of 5 k3s scale-out validation experiments; identifies
  payment_db as the true bottleneck.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-13-k3s-scaleout-phase-a.md
  Phase A build plan: stand up multi-node k3s cluster (Strimzi Kafka, Redis, 4
  app services) to a passing cancel e2e smoke test.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-13-k3s-scaleout-phase-b.md
  Phase B build plan: harden merchant-limit outbox poller lock to Redisson
  RLock; graceful shutdown across services.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-13-k3s-scaleout-phase-c.md
  Phase C measure-first runbook: prove 5 scaleout claims (scheduler lock
  safety, idempotency, DB ceiling, node-failure HA, zero-downtime rolling
  deploy) via load-test experiments.

---

## Tooling / content

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-13-oncall-skills-target-abstraction.md
  Build plan to remove petclinic hardcoding from oncall skills via a
  self-describing per-project oncall-target.yml descriptor.

- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-13-scale-series.md
  Execution plan for authoring the 9-part "Scale" blog series (per-task
  authoring steps). Pairs with REQ-scale-blog-series.
