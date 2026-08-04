# 02-03 VERIFICATION — INV-01 (Phase 2 re-scope) gate + 4-module no-regression

- **Phase:** 02-net (settlement) · **Plan:** 03 · **Requirement:** INV-01
- **Branch:** `feat/settlement-aggregation` (worktree `docswithmulti-settlement`)
- **merge-base BASE:** `583ffeee7565e0fa7d7b3c446ef83a8a06a018e9`
- **HEAD:** `4c902c92f72f6e07d176a28c30c566f969bc3708`
- **Run (UTC):** 2026-08-04T02:40Z
- **Gate command range:** `git diff $BASE...HEAD` (BASE = `git merge-base HEAD main`)

## Re-scope statement

Phase 1's INV-01 proved **payment diff 0** entirely. Phase 2 (SALE slice) **intentionally
changes payment creation** — one outbox INSERT line in `PaymentCreateTxWriter` + new
`PaymentEventOutbox*`/`PaymentCompletedOutbox*` files + Flyway `V19`. So the gate shifts to a
**denylist/allowlist split**: the cancel CORE + stock path must be **diff 0**; the only
permitted payment change is the creation-path allowlist.

---

## Task 1 — INV-01 (Phase 2) merge-base gate: **PASS** (`INV01_PASS`)

### (1) Denylist — cancel CORE + stock path: **diff 0** ✅

`git diff $BASE...HEAD --name-only` over the denylist pathspecs returned **empty**. Guarded
(byte-identical vs merge-base):

- `CancelPaymentService`, `CancelTxWriter`, `CancelAuthorizationService`, `CancelHistoryRecorder`
- Recovery/retry services: `PendingRecoveryService`, `ProcessingRecoveryService`, `CompensationRetryService`, `StockReleaseRetryService`
- Schedulers: `CancelEventOutboxPublisher`, `PendingRecoveryScheduler`, `ProcessingRecoveryScheduler`, `CompensationRetryScheduler`, `StockReleaseRetryScheduler`
- Publishers: `OutboxCancelEventPublisher`, `InlineCancelEventPublisher`, `InlineAsyncCancelEventPublisher`
- Persistence: `CancelEventOutbox*` (incl. `CancelEventOutboxRepository`), `CancelRequest*`, `StockReleaseRetry*`, `CompensationRetry*`
- `ProductStockHttpClient`, `OutboxDataSourceConfig` (new poller runs on MAIN datasource via JPA — this config must stay diff 0)
- cancel `domain/service/*`, `domain/policy/*`
- payment migrations **V1–V18**

Denylist files confirmed to exist on disk (glob is real, not vacuously empty): `CancelPaymentService.java`, `CancelTxWriter.java`, `OutboxDataSourceConfig.java`, `ProductStockHttpClient.java` all present.

### (2) Allowlist — the only changed payment-service paths ✅

Every changed `payment-service` path (10 total) is in the permitted creation-path set; the
allowlist grep (`grep -vE '(PaymentCreateTxWriter\.java|PaymentEventOutbox|PaymentCompletedOutbox|application\.yml|V19__create_payment_event_outbox\.sql)'`)
returned **empty** — no unexpected payment path.

| # | Changed path | Allowlist match |
|---|--------------|-----------------|
| 1 | `application/interfaces/PaymentEventOutboxRepository.java` | PaymentEventOutbox |
| 2 | `application/service/PaymentCreateTxWriter.java` | PaymentCreateTxWriter.java |
| 3 | `infrastructure/config/PaymentEventOutboxConfig.java` | PaymentEventOutbox |
| 4 | `infrastructure/persistence/PaymentEventOutboxJpaEntity.java` | PaymentEventOutbox |
| 5 | `infrastructure/persistence/PaymentEventOutboxJpaRepository.java` | PaymentEventOutbox |
| 6 | `infrastructure/persistence/PaymentEventOutboxRepositoryImpl.java` | PaymentEventOutbox |
| 7 | `infrastructure/scheduler/PaymentCompletedOutboxPublisher.java` | PaymentCompletedOutbox |
| 8 | `src/main/resources/application.yml` | application.yml |
| 9 | `src/main/resources/db/migration/V19__create_payment_event_outbox.sql` | V19__create_payment_event_outbox.sql |
| 10 | `src/test/java/.../integration/PaymentCompletedOutboxIntegrationTest.java` | PaymentCompletedOutbox |

**`PaymentCreateTxWriter` diff is purely additive** — imports + injected `PaymentEventOutboxRepository`
+ one `insertPending(...)` outbox INSERT at end of TX + a private `buildCompletedPayload(...)`
(CancelTxWriter.buildPayload-homologous, hand-rolled JSON). No cancel-path line touched.

### (3) V19 collision check ✅

Only one migration ≥ V19 on this branch: `V19__create_payment_event_outbox.sql`. No parallel
checkout-buynow `V19` present → no Flyway duplicate-version collision. (Had one existed, it would
surface as `UNEXPECTED_PAYMENT_DIFF` in the allowlist and/or a Flyway build failure — T-02-07.)
Cloud-sync duplicate `.sql` copies under `build/` deleted before the gate (`find . -path '*/build/*' -name '* [0-9].sql' -delete`) so Flyway never sees a duplicate V-file (T-02-08).

**Gate output:** `INV01_PASS`

---

## Task 2 — Full-suite no-regression: **PASS** (all 4 modules green)

`find . -path '*/build/*' -name '* [0-9].sql' -delete` then
`./gradlew :payment-service:test :order-service:test :product-service:test :settlement-service:test` → **BUILD SUCCESSFUL**.

| Module | tests | failures | errors | skipped | classes |
|--------|-------|----------|--------|---------|---------|
| payment-service | **307** | 0 | 0 | 0 | 65 |
| order-service | **35** | 0 | 0 | 0 | 12 |
| product-service | **82** | 0 | 0 | 0 | 26 |
| settlement-service | **23** | 0 | 0 | 0 | 7 |
| **Total** | **447** | **0** | **0** | **0** | **110** |

**payment-service fresh rerun** (`--rerun-tasks`, 9 tasks executed, not cached): **BUILD SUCCESSFUL in 8m41s, 307/0/0**. Confirms the creation-path outbox INSERT + V19 did not perturb the cancel core or stock path at runtime. Guarded IT classes present and green, including:
`CancelPaymentServiceTest`, `CancelTxWriterTest`, `CancelTxWriterPayloadTest`, `PendingRecoveryServiceTest`, `ProcessingRecoveryOutboxIT`, `ProcessingRecoveryServiceTest`, `CompensationRetryServiceTest`, `StockReleaseRetryServiceTest`, `OutboxDataSourceConfigIT`, `ProductStockHttpClientTest`, `CancelEventOutboxRepositoryIT`, `CancelEventOutboxPublisherIT`, `OutboxCancelEventPublisherTest`.

settlement Phase 2 suite green (SALE tracer + payment-outbox IT + idempotency/week-boundary + fee calculator + config IT).

---

## Verdict

**INV-01 (Phase 2 re-scope) SATISFIED.**

- Cancel CORE + stock denylist: **diff 0** vs merge-base.
- Only creation-path allowlist payment files changed (10/10 in-set; 0 unexpected).
- V19, no version collision.
- Four-module no-regression: **447 tests, 0 failures** (payment-service fresh-verified).

Gate is scripted and re-runnable (Task 1 `<verify><automated>`). No production code changed by
this plan — verification/gate only.
