# 01-03 VERIFICATION — INV-01 Gate (payout-reserve Phase 1 / 01-hold)

**Result: INV-01 SATISFIED — PASS**
Executed on worktree `/Users/juho/Documents/docswithmulti-reserve`, branch `feat/settlement-payout-reserve`.

Clone of payout 02-03 INV-01 gate with the single documented delta: Flyway assertion **flipped** from
"no V-file above settlement V2" to "V3 PRESENT + no V4+ + V1/V2 unmodified + no payment migration".
Confinement regex and 7-module diff-0 denylist are verbatim.

---

## Refs

| Ref | Value |
|-----|-------|
| BASE = `git merge-base HEAD main` | `44b9abb378e602cb825aeebfe6039b5d89c54035` |
| HEAD | `af4d886fcd607502733153f67916fe077f1dcc0c` |
| main | `28f079a01681abc934757e33bd8c44e779ceeebb` |

Three-dot `BASE...HEAD` correctly excludes main's post-branch commits (cancel-approval `28f079a`, `9043d5a` incl. payment V20) — none leak into the reserve diff.

---

## Task 1 — INV-01 merge-base gate → `INV01_PASS`

### (1) Confinement — settlement-only
`git diff BASE...HEAD --name-only` — EVERY path matches `^(settlement-service/|\.planning/|docs/)`.
`grep -vE '^(settlement-service/|\.planning/|docs/)'` → **empty** (no NON_SETTLEMENT_DIFF).

Changed paths (39):
- `.planning/workstreams/payout-reserve/...` — ROADMAP, 01-01/01-02 PLAN+SUMMARY, 01-03 PLAN
- `docs/error-catalog.md`, `docs/superpowers/specs/2026-08-05-settlement-payout-reserve-design.md`
- `settlement-service/src/main/java/...` — Reserve/MerchantReserveConfig entities, JPA entities/repos/impls, ReserveConfigService, ReserveQueryService, ReserveCalculator (domain), PayoutService, exceptions (InvalidReserveConfig/ReserveConfigNotFound/ReserveNotFound), ErrorCode, PersistenceConfig, controllers (ReserveConfig/ReserveQuery), DTOs
- `settlement-service/src/main/resources/db/migration/V3__create_reserve.sql`
- `settlement-service/src/test/java/...` — ReserveConfig/ReserveHold/ReserveQuery ITs, ReserveCalculator unit

### (2) Module denylist (belt-and-suspenders) — diff 0
`git diff BASE...HEAD --name-only -- payment-service order-service product-service merchant-limit-service risk-management-service user-service api-gateway` → **empty**. All 7 non-settlement module dirs: diff 0.

**Cancel-core denylist explicit grep** (CancelTxWriter · CancelPaymentService · recovery/StockRelease schedulers · cancel_event_outbox/cancel_request persistence · ProductStockHttpClient · OutboxDataSourceConfig) → **0 matches**. Cancel core untouched.

### (3) Flyway V3-only (the flip vs payout 02-03)
settlement migration dir = `V1__create_settlement_core.sql`, `V2__create_payout.sql`, `V3__create_reserve.sql`.

| Assertion | Result |
|-----------|--------|
| V3 `V3__create_reserve.sql` PRESENT | PASS |
| No V4+ (`^V([4-9]\|[1-9][0-9])__`) | PASS (none) |
| V1/V2 diff 0 (unmodified) | PASS (not in diff) |
| No payment migration (`payment-service/.../db/migration/*`) | PASS (empty) |

Gate script (verbatim from plan `<verify>`) emitted **`INV01_PASS`**.

---

## Task 2 — 4-module no-regression suite

`find . -path '*/build/*' -name '* [0-9].sql' -delete` (cloud-sync dup cleanup) then
`./gradlew :payment-service:test :order-service:test :product-service:test :settlement-service:test`
→ **BUILD SUCCESSFUL** (exit 0). (A transient MySQL "Connection refused" appeared during Testcontainers lifecycle in an early module; non-fatal — build passed. `settlement-service:test` reported UP-TO-DATE, i.e. results valid for current HEAD inputs.)

Counts from JUnit XML (`build/test-results/test/*.xml`):

| Module | tests | failures | errors | skipped |
|--------|------:|---------:|-------:|--------:|
| payment-service | 319 | 0 | 0 | 0 |
| order-service | 56 | 0 | 0 | 0 |
| product-service | 83 | 0 | 0 | 0 |
| settlement-service | 70 | 0 | 0 | 0 |
| **Total** | **528** | **0** | **0** | **0** |

**Settlement key classes (all green):**
- **New reserve:** ReserveConfig (4), ReserveHold `payout=net−reserve + HELD row / unset·cap-exhausted → net·no-row` (3), ReserveQuery HOLD-04 (2), ReserveCalculator unit (6)
- **Payout hardening (no regression):** PAY-02 409-return-existing / double-payout guard (6), Retry FAILED<max→resubmit / >=max→DEAD+alert-once (6), Convergence webhook↔poll order-agnostic + idempotent (3), Poll backstop (2), Query (5), Tracer (2) — approve change did NOT regress 409/retry, payout=net backward-compat preserved
- **Settlement core:** SALE/CANCEL ledger, idempotency, query, reconcile→finalize tracer — green

Payment cancel core + stock reserve/release + convergence/idempotency ITs (319 green) confirm reserve wiring disturbs nothing.

---

## Conclusion

INV-01 **SATISFIED**: reserve hold diff is confined to `settlement-service/` (+ `.planning/` · `docs/`);
all 7 non-settlement modules + cancel core diff 0; settlement Flyway = V1+V2+**V3** (V3 legitimately added,
V1/V2 unmodified, no V4+, no payment migration); 4-module suite 528/528 green.
