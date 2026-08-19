# Constraints (SPEC intel)

20 SPEC-typed docs were classified; all 20 are extracted below (the cross-ref
cycles that previously held one back are resolved — graph is acyclic).

Extracted from classifier distillations (title/summary/scope). Full contract
text lives at each `source`.

---

## API / interface contracts

## Payment cancellation API spec
- source: /Users/juho/Documents/docswithmulti/docs/api-spec.md
- type: api-contract
- content: Cancel endpoints — request headers, path/body params,
  request/response schemas, error schema. Server-generated idempotency via
  request_hash (no Idempotency-Key header). Refs error-catalog.md.

## Error catalog
- source: /Users/juho/Documents/docswithmulti/docs/error-catalog.md
- type: api-contract
- content: Standard error response format, HTTP status-code usage, full
  enumerated error-code list (cancel-request validation, compensation errors).

## merchant-limit-service design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-04-23-merchant-limit-service-design.md
- type: api-contract
- content: Hexagonal package structure, entities, internal + admin CRUD APIs,
  MySQL schema, Outbox-pattern publish of `merchant.limit.updated` (partition
  key merchantId). Refs domain-rules.md, db-schema.md.

## risk-management-service (implementation plan, SPEC-tier contracts)
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-04-23-risk-management-service.md
- type: api-contract
- content: 3 internal cancel-limit APIs (validate-and-reserve, compensate,
  check-charge), merchant.limit.updated Kafka consumer, Redis distributed
  lock, Resilience4j CircuitBreaker, MerchantCancelUsage data model, TX
  boundaries.

## risk-management-service design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-04-23-risk-management-service-design.md
- type: api-contract
- content: 3 internal APIs, merchant.limit.updated consumer, Redis
  distributed lock, Resilience4j CircuitBreaker, hexagonal structure,
  persistence contracts. Refs db-schema.md.

---

## Schema contracts

## oncall skills target abstraction design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-13-oncall-skills-target-abstraction-design.md
- type: schema
- content: Self-contained per-project `oncall-target.yml` descriptor to remove
  per-project hardcoding from oncall-triage/pr/log skills; differential-table
  and incident-memory config schema.

---

## Messaging / protocol contracts

## Kafka design
- source: /Users/juho/Documents/docswithmulti/docs/kafka-design.md
- type: protocol
- content: Topic table (partitions/RF/retention/keys), payment.cancelled +
  merchant.limit.updated event schemas, Kafka header schema, producer/consumer
  config, offset-commit, DLQ message format. payment.cancelled published TX3
  inline (key cancelRequestId); merchant.limit.updated via Outbox (key
  merchantId). NOTE: contradicts payment-scheduler-design on the
  payment.cancelled mechanism — see WARNING in INGEST-CONFLICTS.md.

## order-service design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-04-24-order-service-design.md
- type: protocol
- content: payment.cancelled Kafka consumer; Order/OrderItem status sync;
  processed_cancel_event idempotency; retry/DLQ routing; TX boundaries; unit
  tests. Consumer recomputes all items + order-row lock → order-unit ordering
  not required.

## payment-service scheduler design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-04-27-payment-scheduler-design.md
- type: protocol
- content: 4 schedulers with Redisson distributed locks: OutboxPublisher (of
  payment.cancelled), pending-recovery, processing-recovery, compensation-retry.
  NOTE: describes Outbox publish of payment.cancelled — contradicts the later
  inline-TX3 contract in kafka-design.md; competing-variant WARNING in
  INGEST-CONFLICTS.md.

## scheduler enhancement design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-04-28-scheduler-enhancement-design.md
- type: protocol
- content: Real recovery logic for pending-recovery + processing-recovery
  schedulers; V9 migration; CancelRequest entity changes; OperationAlertPort.
  Refs cancel-design.md.

## Kafka publish-pattern benchmark (impl plan, SPEC-tier contracts)
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-11-kafka-publish-pattern-benchmark.md
- type: protocol
- content: CancelEventPublisher port making payment.cancelled publish
  runtime-togglable across INLINE / INLINE_ASYNC / OUTBOX modes;
  cancel_event_outbox table (V10); TX3 saveTx3; partition key cancelRequestId.

## Kafka publish-pattern benchmark design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-11-kafka-publish-pattern-benchmark-design.md
- type: protocol
- content: Design to benchmark 3 publish modes on AWS rig; outbox table +
  outbox publisher scheduler; CancelEventPublisher strategy; measurement plan;
  Grafana dashboard. Status "승인됨(brainstorming)" — not locked.

## Outbox poller dedicated DataSource (impl plan, SPEC-tier contracts)
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-12-outbox-poller-dedicated-datasource.md
- type: protocol
- content: Isolate OUTBOX poller drain path onto a dedicated small
  HikariDataSource + NamedParameterJdbcTemplate (cancelOutboxDataSource) to
  eliminate shared-pool livelock; insertPending stays on main pool.
  CancelEventOutboxRepositoryImpl routing contract + global-constraint invariants.

## Outbox poller dedicated DataSource design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-12-outbox-poller-dedicated-datasource-design.md
- type: protocol
- content: Dedicated small Hikari pool + cancelOutboxJdbcTemplate for the
  poller drain path; connection-pool isolation to eliminate livelock. Refs the
  outbox-poller-livelock post-mortem.

---

## Non-functional / observability / infra

## Load-test white-box observability design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-10-loadtest-whitebox-observability-design.md
- type: nfr
- content: Opt-in OpenTelemetry distributed tracing (Java agent → Tempo) +
  per-request query counting (Micrometer/Prometheus) via a new
  common-observability module. Runtime toggles OTEL_JAVAAGENT /
  LOADTEST_QUERYCOUNT_ENABLED.

## Cancel-history batch design (commits 6→4)
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-11-cancel-history-batch-design.md
- type: nfr
- content: Batch cancel_request_history writes (3 commits → 1; cancel commits
  6→4) via ThreadLocal buffer + recordAll batch INSERT to reduce HikariCP
  connection occupancy and raise the ~150 rps throughput ceiling. History
  stays outside TX1/2/3.

## Saturation diagnosis kit (impl plan, SPEC-tier contracts)
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/plans/2026-07-11-saturation-diagnosis-kit.md
- type: nfr
- content: USE-method saturation diagnosis observability kit for the ~185 rps
  wall: fixed env names (SERVER_TOMCAT_MBEANREGISTRY_ENABLED), Micrometer
  metric names, Tomcat thread metrics, payment→risk hop latency, Grafana
  dashboard, hard "do-not-modify" constraints.

## Saturation diagnosis kit design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-11-saturation-diagnosis-kit-design.md
- type: nfr
- content: USE-method saturation diagnosis design (Tomcat threads, payment→risk
  hop latency, HikariCP pool, Micrometer http.client.requests, Grafana USE
  dashboard) to identify the 185 rps ceiling. Refs measurement-journey.md and
  the saturation-diagnosis runbook.

## 3-view system dashboard design
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-11-system-views-dashboard-design.md
- type: nfr
- content: Grafana dashboard (system-views.json) with Inbound/App/Infra rows
  and their PromQL panel expressions; Prometheus, k6 remote-write,
  node-exporter sources.

## k3s scale-out design spec
- source: /Users/juho/Documents/docswithmulti/docs/superpowers/specs/2026-07-13-k3s-scaleout-design.md
- type: nfr
- content: Deploy the cancel MSA on a multi-node k3s cluster to validate
  horizontal scale-out, availability, and deploy behavior. Strimzi/KRaft
  Kafka, Traefik ingress, payment-service replicas, external MySQL, Terraform
  infra. Refs capacity-planning.md, architecture.md, CLAUDE.md.
