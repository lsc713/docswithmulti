# 03-03 VERIFICATION — INV-01 (Phase 3) gate + 4-module no-regression

**Requirement:** INV-01 (Phase 3 variant) · RECON-03
**Branch:** `feat/settlement-aggregation` (worktree `/Users/juho/Documents/docswithmulti-settlement`)
**Merge base:** `BASE=$(git merge-base HEAD main)` = `583ffeee7565e0fa7d7b3c446ef83a8a06a018e9`
**Result:** ✅ PASS — INV-01 (Phase 3) satisfied

---

## Task 1 — INV-01 (Phase 3) merge-base gate

Gate = the Phase 2 gate (`02-03-PLAN.md:61`) with the allowlist regex extended by a single `|PaymentSettlement` token. Denylist UNCHANGED from Phase 2. macOS-safe: single-quoted glob pathspecs, `--name-only`, `[[:space:]]` (no `\s`).

**Gate output: `INV01_PASS`**

### (1) Cancel CORE + stock denylist — diff 0

`git diff $BASE...HEAD --name-only` over the cancel/stock denylist pathspec set returned **empty** → `CANCEL_CORE_DIFF` not raised.

Denylist covered (all byte-identical vs BASE):
- CancelPaymentService, CancelTxWriter, CancelAuthorizationService, CancelHistoryRecorder
- PendingRecoveryService, ProcessingRecoveryService, CompensationRetryService, StockReleaseRetryService
- cancel `domain/service/*`, cancel `domain/policy/*`
- schedulers: CancelEventOutboxPublisher, Pending/Processing/CompensationRetry/StockReleaseRetry schedulers
- messaging: OutboxCancelEventPublisher, Inline/InlineAsyncCancelEventPublisher
- persistence: CancelEventOutbox\*, CancelRequest\*, StockReleaseRetry\*, CompensationRetry\*
- ProductStockHttpClient, OutboxDataSourceConfig
- payment migrations V1–V18

Negative-control confirmed: `CancelTxWriter.java` / `CancelPaymentService.java` are tracked at the exact denylist paths, so any committed change would populate `$DENY` and trip `CANCEL_CORE_DIFF` (exit 1). Gate fails loudly by construction.

### (2) Payment-service allowlist (Phase 2 set + `PaymentSettlement`) — clean

Full `git diff $BASE...HEAD --name-only -- payment-service` (16 paths), every one in the allowlist. Split:

**Phase 2 set (PaymentCreateTxWriter | PaymentEventOutbox | PaymentCompletedOutbox | application.yml | V19):**
- `application/interfaces/PaymentEventOutboxRepository.java`
- `application/service/PaymentCreateTxWriter.java`
- `infrastructure/config/PaymentEventOutboxConfig.java`
- `infrastructure/persistence/PaymentEventOutboxJpaEntity.java`
- `infrastructure/persistence/PaymentEventOutboxJpaRepository.java`
- `infrastructure/persistence/PaymentEventOutboxRepositoryImpl.java`
- `infrastructure/scheduler/PaymentCompletedOutboxPublisher.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V19__create_payment_event_outbox.sql`
- `src/test/java/.../integration/PaymentCompletedOutboxIntegrationTest.java`

**Phase 3 extension (`PaymentSettlement` token — new read-only query surface):**
- `application/service/PaymentSettlementQueryService.java`
- `application/usecase/PaymentSettlementQuery.java`
- `infrastructure/persistence/PaymentSettlementJpaRepository.java`
- `presentation/controller/PaymentSettlementController.java`
- `presentation/dto/PaymentSettlementResponse.java`
- `src/test/java/.../integration/PaymentSettlementQueryIntegrationTest.java`

`UNEXPECTED_PAYMENT_DIFF` not raised — no PaymentController edit, no cancel-core touch, no checkout-buynow collision path.

### (3) No payment migration above V19

`ls payment-service/src/main/resources/db/migration/ | grep -E '^V(2[0-9]|[3-9][0-9])__'` → empty. Highest V-file is **V19**. `UNEXPECTED_PAYMENT_MIGRATION` not raised. RECON-03 queries existing tables read-only — no new migration, no version collision.

Duplicate cloud-sync `.sql` copies under `build/` deleted before the gate (`find . -path '*/build/*' -name '* [0-9].sql' -delete`).

---

## Task 2 — Full four-module no-regression suite

Command (after duplicate-.sql cleanup):
`./gradlew :payment-service:test :order-service:test :product-service:test :settlement-service:test`

**BUILD SUCCESSFUL in 10m 7s.**

| Module | tests | skipped | failures | errors |
|--------|------:|--------:|---------:|-------:|
| payment-service    | 310 | 0 | 0 | 0 |
| order-service      |  35 | 0 | 0 | 0 |
| product-service    |  82 | 0 | 0 | 0 |
| settlement-service |  29 | 0 | 0 | 0 |
| **Total**          | **456** | **0** | **0** | **0** |

### Key ITs green (no regression)

**Cancel core / stock (payment):** CancelFlowIntegrationTest, CancelRaceIdempotencyIT, CancelPaymentServiceTest, CancelTxWriterTest, CancelTxWriterPayloadTest, Pending/ProcessingRecoveryServiceTest, ProcessingRecoveryOutboxIT, ProcessingRecoveryConcurrencyIT, StockReleaseRetryServiceTest, ProductStockHttpClientTest, CancelEventOutboxRepositoryIT, CancelEventOutboxPublisherIT — all pass → read-only query surface + settlement reconciler did **not** perturb the cancel TX / schedulers / stock path.

**New read-only query (payment):** PaymentSettlementQueryIntegrationTest — green.

**Settlement reconcile / immutability:** SettlementReconcileIntegrationTest, SettlementIdempotencyIntegrationTest, SettlementQueryIntegrationTest, SettlementCancelTracerIntegrationTest, SaleLedgerIntegrationTest, MerchantSettlementConfigIntegrationTest — all green.

---

## INV-01 (Phase 3) conclusion

✅ **Satisfied.** Cancel CORE + stock diff 0 vs merge-base (denylist UNCHANGED from Phase 2); every changed payment-service path is in the allowlist (Phase 2 set + the `PaymentSettlement` read-only query files); no payment migration above V19; full four-module suite (456 tests) green with the cancel/stock/order ITs and the new settlement reconcile/immutability + payment read-only query ITs all passing.
