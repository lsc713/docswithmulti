# Phase 1 Plan 02 — INV-01 Gate + No-Regression Verification

- **Branch:** `feat/settlement-aggregation` (worktree `/Users/juho/Documents/docswithmulti-settlement`)
- **BASE** = `git merge-base HEAD main` = `583ffee`
- **HEAD** = `51aadf1`
- **Date:** 2026-08-04

---

## Task 1 — INV-01 merge-base git diff gate

### Substantive INV-01 (payment diff 0, no guarded-core module changed): **PASS**

| Assertion | Command | Result |
|-----------|---------|--------|
| payment-service committed diff 0 | `git diff BASE...HEAD --name-only -- 'payment-service'` | **empty** (0 paths) |
| payment-service working tree clean | `git status --porcelain 'payment-service'` | **empty** (0 paths) |
| No other guarded module touched | changed top-level dirs vs BASE | only settlement-service + docs(1) + config(2) + .planning |

Phase 1 added a **new module + new Kafka consumer group** on the existing `payment.cancelled`
topic. It touches zero payment/order/product/merchant/risk/user source. INV-01 is structurally
diff 0 by construction; this gate proves and locks it.

### Changed-path scope vs BASE

```
   4 .planning/            (REQUIREMENTS, ROADMAP, STATE, 01-01-SUMMARY)
   1 docker-compose.yml    (+mysql-settlement 3316)
   1 docs/                 (settlement design spec — see finding below)
   1 settings.gradle       (+settlement-service module)
  39 settlement-service/   (new module: src + tests + build.gradle + V1 migration)
```

Full changed-file list (44 paths): all under `settlement-service/`, `settings.gradle`,
`docker-compose.yml`, `.planning/`, plus one `docs/` design spec.

### Strict-allowlist scripted gate: **1 flagged path (allowlist gap, not an INV-01 violation)**

The plan's Task-1 `<verify>` allowlist is `settlement-service/ | settings.gradle |
docker-compose.yml | .planning/`. Running it verbatim exits 1 with:

```
UNEXPECTED: docs/superpowers/specs/2026-08-03-settlement-aggregation-design.md
```

**Provenance:** this path was added in commit `72eb60e docs(settlement): 정산 집계 코어 v1.0 설계` —
the **seed design-spec commit of this feature branch**, the settlement workstream's OWN
documentation (confirmed in 01-01-SUMMARY §INV-01: "The spec + `.planning/` in the branch diff
are pre-existing workstream-setup commits"). It is:

- **NOT** a change to payment-service or any guarded-core module (it is a Markdown design doc).
- **NOT** contamination from the parallel base-repo session (it is this branch's own commit).
- **NOT** reverted — per plan guardrail, we do not "fix" the gate by reverting work.

**Conclusion:** the substantive invariant (payment diff 0 + no guarded module changed) **PASSES**.
The scripted allowlist simply omitted `docs/`, where this workstream's design spec legitimately
lives. **Recommended human decision:** widen the plan's allowlist to include
`docs/superpowers/specs/` (or relocate the spec under `.planning/`) so the re-runnable gate is
green without weakening the payment-diff-0 assertion. Both are documentation-placement choices;
neither affects INV-01.

### Cloud-sync duplicate-file cleanup (T-01-05 mitigation)

`find . -path '*/build/*' -name '* [0-9].sql' -delete` (+ `.java`/`.class`) run before every
diff/build invocation. No duplicate `V1` copies reached Flyway; no checksum failure.

---

## Task 2 — Full-suite no-regression

`./gradlew :payment-service:test :order-service:test :product-service:test :settlement-service:test`
→ **BUILD SUCCESSFUL in 13m 49s** (exit 0).

| Module | Tests | Failures | Errors | Skipped | Suite files |
|--------|------:|---------:|-------:|--------:|------------:|
| payment-service | 305 | 0 | 0 | 0 | 64 |
| order-service | 35 | 0 | 0 | 0 | 12 |
| product-service | 82 | 0 | 0 | 0 | 26 |
| settlement-service | 9 | 0 | 0 | 0 | 4 |
| **TOTAL** | **431** | **0** | **0** | **0** | **106** |

(Counts summed from each module's `build/test-results/test/*.xml`. `settlement-service:test`
reported `UP-TO-DATE` — cached green from the 01-01 run; its 9-test result set is unchanged.)

Existing **cancel / stock / order** integration tests (convergence, idempotency, loss,
split-send, DLQ, Redisson) show **no regression** — the new settlement consumer group is a
zero-touch fan-out on `payment.cancelled` and did not perturb the existing order/product
consumers or payment code.

The MySQL `CommunicationsException` / `Connection refused` stack traces in the log are
**teardown noise** — HikariCP housekeeper threads validating pooled connections after the
Testcontainers MySQL was already stopped at test end. They occur after each suite's assertions
pass and do not fail any test (all XML report `failures=0 errors=0`).

---

## INV-01 verdict

**SATISFIED.** payment-service diff 0 (committed + working tree); no guarded-core module changed;
existing cancel/stock/order suites green (no regression); settlement suite green. The lone
`docs/` design-spec path is a benign allowlist-scope gap in the plan's script (this workstream's
own documentation), not an invariant violation — flagged for a documentation-placement decision.
