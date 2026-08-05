# 01-03 VERIFICATION — INV-01 settlement-only gate + 4-module no-regression

- **Phase:** 01-paid-tracer (payout workstream)
- **Plan:** 01-03 (INV-01 gate)
- **Branch / worktree:** `feat/settlement-payout` @ `/Users/juho/Documents/docswithmulti-payout`
- **BASE:** `git merge-base HEAD main` = `baf99352ea08bf92e36cb0cd7b9413151a459c14`
- **Result:** **INV-01 SATISFIED — INV01_PASS + full 4-module suite green (501 tests, 0 failures).**

---

## Task 1 — INV-01 merge-base confinement gate → `INV01_PASS`

Duplicate cloud-sync `.sql`/`.java`/`.class` copies under `*/build/*` purged first (single-quoted BSD/macOS globs). Gate re-runnable; output was `INV01_PASS`.

### (1) Confinement — every changed path ∈ `^(settlement-service/|.planning/|docs/)`

`git diff BASE...HEAD --name-only` → **46 files**, prefix breakdown:

| Prefix | Files |
|--------|-------|
| `settlement-service/` | 38 |
| `.planning/` | 6 |
| `docs/` | 2 |

No `payment-service/`, `order-service/`, `product-service/`, `merchant-limit-service/`, `risk-management-service/`, `user-service/`, `api-gateway/`, `frontend/`, or root-config path appears. `NON_SETTLEMENT_DIFF` = empty. **PASS.**

Full changed-path list:

```
.planning/workstreams/payout/ROADMAP.md
.planning/workstreams/payout/phases/01-paid-tracer/01-01-PLAN.md
.planning/workstreams/payout/phases/01-paid-tracer/01-01-SUMMARY.md
.planning/workstreams/payout/phases/01-paid-tracer/01-02-PLAN.md
.planning/workstreams/payout/phases/01-paid-tracer/01-02-SUMMARY.md
.planning/workstreams/payout/phases/01-paid-tracer/01-03-PLAN.md
docs/error-catalog.md
docs/superpowers/specs/2026-08-04-settlement-payout-design.md
settlement-service/src/main/java/com/example/settlement/application/exception/InvalidPayoutAccountException.java
settlement-service/src/main/java/com/example/settlement/application/exception/MerchantPayoutAccountNotFoundException.java
settlement-service/src/main/java/com/example/settlement/application/exception/PayoutAccountInactiveException.java
settlement-service/src/main/java/com/example/settlement/application/exception/PayoutNotFoundException.java
settlement-service/src/main/java/com/example/settlement/application/exception/PayoutNotPayableException.java
settlement-service/src/main/java/com/example/settlement/application/exception/PayoutSignatureException.java
settlement-service/src/main/java/com/example/settlement/application/interfaces/BankTransferPort.java
settlement-service/src/main/java/com/example/settlement/application/interfaces/MerchantPayoutAccountRepository.java
settlement-service/src/main/java/com/example/settlement/application/interfaces/PayoutRepository.java
settlement-service/src/main/java/com/example/settlement/application/service/PayoutResultService.java
settlement-service/src/main/java/com/example/settlement/application/service/PayoutService.java
settlement-service/src/main/java/com/example/settlement/common/exception/ErrorCode.java
settlement-service/src/main/java/com/example/settlement/domain/entity/MerchantPayoutAccount.java
settlement-service/src/main/java/com/example/settlement/domain/entity/Payout.java
settlement-service/src/main/java/com/example/settlement/infrastructure/config/PersistenceConfig.java
settlement-service/src/main/java/com/example/settlement/infrastructure/http/BankTransferHttpClient.java
settlement-service/src/main/java/com/example/settlement/infrastructure/http/MockBankTransferClient.java
settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantPayoutAccountJpaEntity.java
settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantPayoutAccountJpaRepository.java
settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantPayoutAccountRepositoryImpl.java
settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/PayoutJpaEntity.java
settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/PayoutJpaRepository.java
settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/PayoutRepositoryImpl.java
settlement-service/src/main/java/com/example/settlement/infrastructure/scheduler/PayoutPollScheduler.java
settlement-service/src/main/java/com/example/settlement/presentation/controller/PayoutAccountController.java
settlement-service/src/main/java/com/example/settlement/presentation/controller/PayoutCallbackController.java
settlement-service/src/main/java/com/example/settlement/presentation/controller/PayoutController.java
settlement-service/src/main/java/com/example/settlement/presentation/dto/PayoutAccountRequest.java
settlement-service/src/main/java/com/example/settlement/presentation/dto/PayoutAccountResponse.java
settlement-service/src/main/java/com/example/settlement/presentation/dto/PayoutCallbackRequest.java
settlement-service/src/main/java/com/example/settlement/presentation/dto/PayoutResponse.java
settlement-service/src/main/resources/application.yml
settlement-service/src/main/resources/db/migration/V2__create_payout.sql
settlement-service/src/test/java/com/example/settlement/http/MockBankTransferClientTest.java
settlement-service/src/test/java/com/example/settlement/integration/PayoutConvergenceIntegrationTest.java
settlement-service/src/test/java/com/example/settlement/integration/PayoutPollIntegrationTest.java
settlement-service/src/test/java/com/example/settlement/integration/PayoutQueryIntegrationTest.java
settlement-service/src/test/java/com/example/settlement/integration/PayoutTracerIntegrationTest.java
```

### (2) Module denylist — seven non-settlement module dirs diff 0

`git diff BASE...HEAD --name-only -- payment-service order-service product-service merchant-limit-service risk-management-service user-service api-gateway` = **empty**. `MODULE_DIFF` empty. **PASS.**

**Cancel-core denylist (belt-and-suspenders)** — grep of changed paths for `CancelTxWriter | CancelPaymentService | RecoveryScheduler | StockRelease | cancel_event_outbox | cancel_request | ProductStockHttpClient | OutboxDataSourceConfig | payment-service/**/db/migration` = **0 hits** (`CANCEL_CORE_DENYLIST_CLEAN`). The payment cancel TX/idempotency/scheduler/outbox and stock path are diff 0 — trivially, since the whole `payment-service/` tree is diff 0.

### (3) Flyway — exactly settlement V2, V1 unchanged, no payment migration

- `settlement-service/.../db/migration/` contains exactly: `V1__create_settlement_core.sql`, `V2__create_payout.sql`.
- `V2__create_payout.sql` present (`MISSING_V2` not triggered).
- No settlement V-file `^V([3-9]|[1-9][0-9])__` exists (`UNEXPECTED_SETTLEMENT_MIGRATION` empty). **No V3+.**
- `V1` unchanged: `git diff BASE...HEAD -- 'settlement-service/.../V1*'` = empty.
- No payment migration added: `git diff BASE...HEAD --name-only -- 'payment-service/.../db/migration/*'` = empty (`PAYMENT_MIGRATION` empty). **PASS.**

> Per spec §8, payout reads net from settlement's own row and never calls payment — so, unlike settlement reconcile's 03-03 gate, there is **no PaymentSettlement allowlist token**; the confinement regex is simply `^(settlement-service/|\.planning/|docs/)`.

**Task 1 verdict: `INV01_PASS`.**

---

## Task 2 — 4-module no-regression suite → all green

Command (after `find . -path '*/build/*' -name '* [0-9].sql' -delete`):
`./gradlew :payment-service:test :order-service:test :product-service:test :settlement-service:test`

**`BUILD SUCCESSFUL in 18m 13s` (exit 0).**

| Module | tests | failures | errors | skipped | classes |
|--------|------:|---------:|-------:|--------:|--------:|
| payment-service | 319 | 0 | 0 | 0 | 67 |
| order-service | 56 | 0 | 0 | 0 | 15 |
| product-service | 83 | 0 | 0 | 0 | 26 |
| settlement-service | 43 | 0 | 0 | 0 | 13 |
| **total** | **501** | **0** | **0** | **0** | **121** |

### Regression-guard coverage confirmed (ran, green)

- **Payment cancel core / stock:** `CancelPaymentServiceTest`, `CancelTxWriterTest`, `CancelTxWriterPayloadTest`, `PendingRecoveryServiceTest`, `ProcessingRecoveryServiceTest`, `ProcessingRecoveryOutboxIT`, `StockReleaseRetryServiceTest`, `CancelEventPublisherBeanSelectionTest` — all green ⇒ payout perturbed nothing.
- **Settlement Phase 1/2/3 (ledger/reconcile/config/immutability/query):** `SaleLedgerIntegrationTest`, `SettlementCancelTracerIntegrationTest`, `SettlementReconcileIntegrationTest`, `SettlementIdempotencyIntegrationTest`, `SettlementQueryIntegrationTest`, `MerchantSettlementConfigIntegrationTest` — all green ⇒ existing settlement code untouched.
- **New payout ITs (this slice):** `PayoutTracerIntegrationTest`, `PayoutPollIntegrationTest`, `PayoutQueryIntegrationTest`, `PayoutConvergenceIntegrationTest`, `MockBankTransferClientTest` — all green.

**Task 2 verdict: no regression; payout addition is beans-only.**

---

## INV-01 conclusion

**SATISFIED.** Payout slice is confined to `settlement-service/` (+ `.planning/` · `docs/`); the seven other module dirs are diff 0; the payment cancel core, stock path, and existing settlement ledger/reconcile code are untouched; settlement added exactly Flyway `V2` (V1 unchanged, no V3+, no payment migration); and the full four-module suite is green (501/501).
