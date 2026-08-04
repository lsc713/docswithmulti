---
phase: 01-core-tracer
plan: 01
subsystem: settlement-service
status: complete
tags: [settlement, kafka-consumer, ledger, idempotency, kst-week, greenfield-module]
requires:
  - "payment.cancelled Kafka topic (existing, payment-service — consumed, not modified)"
provides:
  - "settlement-service module (port 8086, settlement_db on 3316)"
  - "payment.cancelled → weekly settlement ledger ingestion (CANCEL lines + header upsert/increment)"
  - "GET /v1/settlements list + GET /v1/settlements/{id} detail query API"
  - "SettlementWeek KST bucketing domain helper"
affects:
  - "settings.gradle (+1 module), docker-compose.yml (+mysql-settlement 3316)"
tech-stack:
  added: []          # zero new external deps — all inherited from root build.gradle
  patterns:
    - "new Kafka consumer group (settlement-service) = zero-touch fan-out on existing topic (payment diff 0)"
    - "double-guard idempotency: processed_settlement_event pre-check + settlement_line.event_id UK"
    - "native INSERT..ON DUPLICATE KEY UPDATE upsert + single-statement atomic increment (risk pattern)"
    - "hexagonal: pure POJO domain, *JpaEntity in infrastructure, hand-written *RepositoryImpl in PersistenceConfig"
key-files:
  created:
    - settlement-service/build.gradle
    - settlement-service/src/main/resources/application.yml
    - settlement-service/src/main/resources/db/migration/V1__create_settlement_core.sql
    - settlement-service/src/main/java/com/example/settlement/domain/service/SettlementWeek.java
    - settlement-service/src/main/java/com/example/settlement/application/service/CancelLedgerService.java
    - settlement-service/src/main/java/com/example/settlement/application/service/SettlementQueryService.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/messaging/PaymentCancelledSettlementConsumer.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/config/KafkaConsumerConfig.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/SettlementQueryController.java
  modified:
    - settings.gradle
    - docker-compose.yml
decisions:
  - "Query auth deferred to deploy-time NetworkPolicy (gateway-only ingress), not in-service — same class as payment/product ingress gates (objective open-Q1)"
  - "auto-offset-reset=earliest → new group back-fills retained payment.cancelled history (idempotent; objective open-Q2)"
  - "settlement_line insert via native @Modifying INSERT (not JPA persist) — UK violation surfaces as DataIntegrityViolationException before the atomic increment, so a race dup rolls back the whole TX"
metrics:
  duration: ~35m
  completed: 2026-08-04
  tasks: 3
  files_created: 30
  tests: 9
requirements: [SETUP-01, CANCEL-01, CANCEL-02, QUERY-01]
---

# Phase 1 Plan 01: Settlement Core Tracer Summary

Stood up the greenfield **settlement-service** (port 8086, independent `settlement_db` on 3316) as a NEW Kafka consumer group on the already-existing `payment.cancelled` topic, driving one cancellation end-to-end into a merchant × settlement-week ledger — idempotently, with query APIs — entirely by clone-and-trim of `merchant-limit-service` (skeleton), `product-service` (consumer + idempotency gate, minus RetryRouter/DLQ/Redisson), and `risk-management-service` (atomic upsert/increment SQL). Zero new external dependencies; **payment-service diff 0**.

## What was built

| Task | Type | Result | Commit |
|------|------|--------|--------|
| 1 | tracer | Module boots on Testcontainers MySQL, Flyway V1 creates 4 tables, one `payment.cancelled` → 1 CANCEL line + OPEN header (KST-Monday `period_start`) + `cancel_amount` increment, GET detail returns header+line. End-to-end green. | `523b6a5` |
| 2 | auto (tdd) | Idempotency double-guard: same event twice → 1 line, `cancel_amount` counted once. No production fix needed (guard correct from tracer). | `4dcae28` |
| 3 | auto (tdd) | KST week-boundary unit test (UTC-evening → next KST week; Sun/Mon 7-day split) + query list/status-filter/detail with 400-on-invalid-status and 404-on-missing-id. | `b1c060a` |

**Docs/state metadata commit:** see final commit below.

## Requirements satisfied

- **SETUP-01** — settlement-service boots on 8086 / settlement_db; Flyway V1 creates `merchant_settlement_config`, `settlement`, `settlement_line`, `processed_settlement_event`.
- **CANCEL-01** — `payment.cancelled` → one CANCEL line (`event_id=cancel:{cancelRequestId}`); duplicate delivery = 1 line (double-guard).
- **CANCEL-02** — header (merchant × `period_start` = KST Monday) upserted OPEN + `cancel_amount` atomically incremented by Σ itemAmount; UTC-evening event lands in the correct KST week (explicit unit test).
- **QUERY-01** — `GET /v1/settlements?merchantId&status` list (+ status filter, 400 on invalid) and `GET /v1/settlements/{id}` detail (header+lines, 404 on missing).

## Test results

Full `./gradlew :settlement-service:test` — **9 tests, 0 failures, 0 errors, 0 skipped**:
- `SettlementWeekTest` — 2 (KST boundary)
- `SettlementCancelTracerIntegrationTest` — 1 (Testcontainers MySQL+Kafka e2e)
- `SettlementIdempotencyIntegrationTest` — 1 (Testcontainers MySQL+Kafka)
- `SettlementQueryIntegrationTest` — 5 (RANDOM_PORT + java.net.http.HttpClient, Kafka listener auto-startup=false)

Boot 4 conventions honored: Jackson 3 `tools.jackson.databind.ObjectMapper`; no TestRestTemplate / no bare `@AutoConfigureMockMvc` (HttpClient + JdbcTemplate); Testcontainers `confluentinc/cp-kafka:7.5.0` + `mysql:8.0`.

## INV-01 (payment diff 0)

`git diff $(git merge-base HEAD main)...HEAD -- payment-service` is **empty**. My three task commits touched only `settlement-service/**`, `settings.gradle`, `docker-compose.yml`. (The spec + `.planning/` in the branch diff are pre-existing workstream-setup commits, not this plan.) Full INV-01 gate + cross-module no-regression is plan 01-02's scope.

## Deviations from Plan

None affecting scope or behavior. Notes:
- **Clone strategy:** wrote the trimmed file set directly instead of literal `cp -r merchant-limit-service` then deleting the producer/domain half — cleaner diff and sidesteps the cloud-sync duplicate-file hazard during the copy. Result is identical to the specified clone-and-trim (same skeleton, same hexagonal layout, producer/scheduler/Redisson excluded).
- **Tasks 2 & 3 were test-only commits** — the double-guard idempotency and the status-validation/404 production paths were already built correctly by the tracer (Task 1), so the TDD RED step for those behaviors was already green. Committed honestly as `test(...)` with no accompanying `feat(...)` since no production change was required. This is expected for a tracer that front-loads the mechanism.

## Known Stubs

None. `merchant_settlement_config` is created (V1) but unused in Phase 1 — that is intentional per spec: fee-rate/FINALIZE (fee/vat/net) is Phase 2, reconciler is Phase 3. `gross_amount`/`fee_amount`/`vat_amount`/`net_amount` columns exist and default 0 (populated by later phases). This is scoped-out future work, not a stub blocking the plan goal.

## Deferred / out-of-scope (by design, per spec §6)

- RetryRouter / retry-topic / durable DLQ / Redisson requeue — Phase 3 (reconciler is the backstop). Phase 1 consumer rethrows → `DefaultErrorHandler(FixedBackOff)` redelivers.
- fee/VAT/net rounding (HALF_UP) + FINALIZE state transition — Phase 2.
- `payment.completed` SALE ingestion + reconciler batch — later plans.
- Deploy-time NetworkPolicy restricting settlement ingress to the gateway pod (IDOR mitigation for the unauthenticated query API) — deploy gate, documented in objective + controller javadoc.

## Self-Check: PASSED

- All 5 must-have artifacts exist on disk (V1 SQL, SettlementWeek, PaymentCancelledSettlementConsumer, CancelLedgerService, SettlementQueryController).
- Commits `523b6a5`, `4dcae28`, `b1c060a` exist on `feat/settlement-aggregation`.
- Full module test suite green (9/9).
