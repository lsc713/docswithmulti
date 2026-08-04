---
phase: 02-net
plan: 01
subsystem: settlement + payment
status: complete
tags: [outbox, dual-write, kafka, idempotency, settlement, sale, tracer]
requires:
  - payment-service creation path (PaymentCreateTxWriter.persist)
  - settlement Phase 1 ledger (Settlement/SettlementLine/ProcessedSettlementEvent, SettlementWeek)
provides:
  - payment.completed outbox (V19 payment_event_outbox + poll publisher)
  - settlement SALE ingestion (payment.completed consumer + SaleLedgerService + gross_amount increment)
affects:
  - payment creation TX (one atomic outbox INSERT appended)
  - settlement consumer group (2nd @KafkaListener on existing factory)
tech-stack:
  added: []
  patterns: [transactional-outbox, poll-publisher, double-guard-idempotency, atomic-increment]
key-files:
  created:
    - payment-service/src/main/resources/db/migration/V19__create_payment_event_outbox.sql
    - payment-service/src/main/java/com/example/payment/application/interfaces/PaymentEventOutboxRepository.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentEventOutboxJpaEntity.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentEventOutboxJpaRepository.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentEventOutboxRepositoryImpl.java
    - payment-service/src/main/java/com/example/payment/infrastructure/config/PaymentEventOutboxConfig.java
    - payment-service/src/main/java/com/example/payment/infrastructure/scheduler/PaymentCompletedOutboxPublisher.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/messaging/PaymentCompletedPayload.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/messaging/PaymentCompletedSettlementConsumer.java
    - settlement-service/src/main/java/com/example/settlement/application/usecase/RecordSaleUseCase.java
    - settlement-service/src/main/java/com/example/settlement/application/service/SaleLedgerService.java
    - payment-service/src/test/java/com/example/payment/integration/PaymentCompletedOutboxIntegrationTest.java
    - settlement-service/src/test/java/com/example/settlement/integration/SaleLedgerIntegrationTest.java
  modified:
    - payment-service/src/main/java/com/example/payment/application/service/PaymentCreateTxWriter.java
    - payment-service/src/main/resources/application.yml
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/SettlementRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/SettlementRepositoryImpl.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/SettlementJpaRepository.java
    - settlement-service/src/main/resources/application.yml
decisions:
  - "completedAt wire format = UTC Instant with trailing Z via toInstant(ZoneOffset.UTC) — Instant.parse requires Z (RESEARCH Q1 resolved)"
  - "poll publisher runs on MAIN datasource via JPA @Query (no dedicated Hikari pool) → OutboxDataSourceConfig diff 0"
  - "poll-only publisher: dropped wake-relay + purge (weekly settlement latency-insensitive; Phase 3 reconcile is backstop)"
metrics:
  tasks: 2
  files: 19
  payment_tests: 307
  settlement_tests: 23
  completed: 2026-08-04
---

# Phase 02 Plan 01: SALE Vertical Slice (Tracer) Summary

payment.completed transactional outbox (committed atomically inside `PaymentCreateTxWriter.persist`) → poll publisher → settlement records a SALE ledger line (`event_id=sale:{paymentKey}`) and atomically increments the weekly header `gross_amount` — proven end-to-end with Testcontainers.

## What was built

**SALE-01 (payment):** New `payment_event_outbox` table (Flyway V19, clone of cancel_event_outbox V12+V14 with `cancel_request_id`→`payment_key`). The outbox INSERT is the LAST statement of `persist()`, in the same `@Transactional` as the payment/payment_item rows (dual-write safe). A new poll publisher (`PaymentCompletedOutboxPublisher`) — a clone of `CancelEventOutboxPublisher` minus the Redisson wake-relay and purge — emits `payment.completed` (partition key = paymentKey) on the MAIN datasource via JPA, so `OutboxDataSourceConfig` stays diff 0. Independent config keys (`payment.completed.outbox.*`, `scheduler.lock.payment-completed-outbox`) — never coupled to `cancel.publish.mode`.

**SALE-02 (settlement):** New `@KafkaListener` (`PaymentCompletedSettlementConsumer`) on the existing `kafkaListenerContainerFactory`, same `settlement-service` group. `SaleLedgerService` (clone of `CancelLedgerService`, CANCEL→SALE, `cancel:`→`sale:`) does line-insert-before-increment with a double-guard (`processed_settlement_event` pre-check + `settlement_line.event_id` UK). New `addGrossAmount` single-statement atomic increment added across the SettlementRepository interface/impl/JpaRepository.

## Verification

- **SaleLedgerIntegrationTest** (Testcontainers MySQL+Kafka): happy path (1 SALE line + OPEN header, gross=totalAmount, KST-Monday period_start), duplicate delivery → 1 line + gross counted once, `2026-08-02T15:00:00Z` → period_start `2026-08-03` (next KST week, proving Z round-trips through Instant.parse).
- **PaymentCompletedOutboxIntegrationTest** (Testcontainers MySQL): creating a payment writes exactly 1 PENDING outbox row with a Z-suffixed `completedAt`; poll publisher transitions it to PUBLISHED; a forced failure after the outbox insert rolls back `persist()`'s TX leaving 0 payment + 0 outbox rows (dual-write atomicity proven).
- Full suites: payment-service **307** tests, settlement-service **23** tests — all green, no regressions.
- V19 confirmed applied in Testcontainers migration log ("Migrating schema payment_test to version 19 - create payment event outbox").

## Deviations from Plan

**1. [Rule 3 - Blocking] `@Value` defaults on publisher topic/lock keys**
- **Found during:** Task 2 (payment context failed to load — `PlaceholderResolutionException`).
- **Issue:** `PaymentCompletedOutboxPublisher` is unconditional (always loads), but the shared `payment-service/src/test/resources/application.yml` (not in this plan's file set, owned by no plan) lacks `kafka.topic.payment-completed` and `scheduler.lock.payment-completed-outbox`.
- **Fix:** Added defaults to both `@Value` placeholders (`:payment.completed`, `:lock:scheduler:payment-completed-outbox`) — keeps the change inside my own file rather than editing a shared test resource, and makes the bean robust.
- **Files modified:** PaymentCompletedOutboxPublisher.java
- **Commit:** 4442b70

Two test-only assertion corrections (not production): totalAmount is Σ itemAmount (30000), not ×quantity (RESEARCH Pitfall 6); and the payload assertion parses JSON instead of string-matching because MySQL's JSON column normalizes key order/whitespace.

## Parallel-safety confirmation

Touched ONLY this plan's `files_modified`. Did NOT edit `PersistenceConfig.java`, `OutboxDataSourceConfig.java`, or any cancel-core file (CancelTxWriter, schedulers, cancel_event_outbox, cancel_request, stock client). `SaleLedgerService` self-registers as `@Service` (no config wiring needed). INV-01 (cancel core diff 0) is verifiable and left to plan 02-03.

## Known Stubs

None. Both halves are wired to real data end-to-end and proven by Testcontainers ITs.

## Self-Check: PASSED

- All 19 created/modified files present on disk.
- Commits 72c16e4 (Task 1) and 4442b70 (Task 2) exist on `feat/settlement-aggregation`.
