# Phase 1: 유보 정책 + 승인 홀드 — Research

**Researched:** 2026-08-06
**Domain:** Spring Boot 4 / JPA settlement-service — clone payout v1.0 config+approve to add merchant reserve policy + hold-at-approve
**Confidence:** HIGH (all findings from worktree source at file:line; no external deps)
**Worktree:** `/Users/juho/Documents/docswithmulti-reserve` (branch `feat/settlement-payout-reserve`, merge-base `main` = `baf99352`)

## Summary

Phase 1 is a **near-mechanical clone** of two existing payout v1.0 slices, both already in this worktree: (1) the `merchant_settlement_config` config stack → `merchant_reserve_config` (add `reserve_rate`/`reserve_cap`/`hold_days`), and (2) a read-only reserve computation + one extra durable INSERT wired into the middle of `PayoutService.approve()`. No new libraries, no new external ports, no scheduler (release is Phase 2). Everything needed already exists as a working pattern: native `ON DUPLICATE KEY` upsert, `COALESCE(SUM(...),0)` aggregation, deterministic transfer-ref (`PO-` → `RSV-`), KST `ZoneId`, `BusinessException`→`ErrorCode`→`GlobalExceptionHandler`, Testcontainers+`@MockitoBean BankTransferPort` IT harness.

The **one load-bearing constraint** is the approve-integration ordering. `approve()` at `PayoutService.java:74-107` is deliberately **non-`@Transactional`**: the `@GeneratedValue(IDENTITY)` save flushes the payout INSERT immediately, so a race loser surfaces `DataIntegrityViolationException` in the `catch` at line 95 and is converted to 409-return-existing. The reserve INSERT must slot in **after** the payout INSERT succeeds (line 103) and **before** `bankTransferPort.submit` (line 104) — as a separate durable write, never wrapped in a transaction. A loser DIVEs at the payout insert (line 94) and never reaches the reserve insert, so no orphan reserve row. The payout→net−reserve−then→submit ordering keeps every existing payout IT green because all their merchants have no reserve config → reserve=0 → payout=net (backward-compat holds by construction).

**Primary recommendation:** Clone the config stack verbatim (entity/JpaRepo-upsert/RepositoryImpl/port/Service/Controller/DTO/@Bean), add `merchant_reserve_config`+`reserve` in **Flyway V3**, add a `ReserveRepository` with a `COALESCE(SUM)` current-held query + `insertHeld`, and inject both new repos into `PayoutService` to compute reserve read-only and insert the HELD row between payout-insert and submit. Do NOT touch the `approve()` transaction boundary or the 409 catch.

## User Constraints (from spec + REQUIREMENTS + CLAUDE.md)

No CONTEXT.md exists yet for this phase. Constraints are drawn from the authoritative spec (`docs/superpowers/specs/2026-08-05-settlement-payout-reserve-design.md`), REQUIREMENTS.md, and CLAUDE.md guardrails.

### Locked Decisions (INV / spec §8 / CLAUDE.md)
- **settlement-only (INV-01)**: payment/order/product/merchant/risk/user/gateway diff **0**. Changes confined to `settlement-service/` (+`.planning/`·`docs/`). Flyway **V3 allowed** (settlement migration — only *payment* migrations are forbidden). merge-base git diff gate (clone payout 02-03).
- **payout core immutable**: `approve()` stays **non-`@Transactional`**; 409-race, `applyResult` convergence, retry/DEAD logic unchanged. Reserve is *additive wiring only* inside approve.
- **No module→DB direct access, no cross-DB FK** (CLAUDE.md). reserve tables live in `settlement_db`, keyed by client-supplied `merchant_id`/`settlement_id` (same class as `merchant_settlement_config`).
- **domain layer = no Spring/JPA annotations** (CLAUDE.md). `Reserve`/`MerchantReserveConfig` domain POJOs stay annotation-free; JPA mapping lives in `infrastructure/persistence` (mirror `Settlement`/`SettlementJpaEntity` split).
- **No hardcoded secrets; Flyway-only DDL; no test-free completion** (CLAUDE.md).
- **Money convention**: `BigDecimal` scale 2 HALF_UP, `DECIMAL(19,2)` KRW; rate `DECIMAL(5,4)`.
- **Reserve character**: time-based rolling holdback only. Cancel/refund drawdown is **out of scope (v4)**.

### Claude's Discretion
- Exact class/method names for the reserve clones, DTO field ordering, whether `current_held` SUM lives on `ReserveJpaRepository` (recommended) vs a service.
- Whether reserve-config GET reuses one controller with the PUT or a separate query controller (spec §7 lists `ReserveConfigController`).

### Deferred Ideas (OUT OF SCOPE — Phase 2 or v4)
- REL-01/02/03 release lifecycle (scheduler, `/v1/reserves/callback` webhook, retry/RELEASE_DEAD) → **Phase 2**.
- Cancel/refund reserve drawdown, partial/early release, effective-dated rate history, real bank/HMAC → **v4**.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| RCFG-01 | `PUT /v1/settlements/reserve-config/{merchantId}` `{reserveRate,reserveCap,holdDays}` upsert; validation → 400 | Clone `MerchantSettlementConfig*` stack (§Standard Stack). Validation clone of `SettlementConfigService.setRate` (`SettlementConfigService.java:28-40`) |
| RCFG-02 | `GET /v1/settlements/reserve-config/{merchantId}` (404 if none) | **No config GET exists today** — settlement config is PUT-only (`SettlementConfigController.java`). Clone GET shape from `PayoutAccountController.get` (`PayoutAccountController.java:38-43`); 404 via new `BusinessException` |
| HOLD-01 | Compute `reserve = min(round(net×rate,2,HALF_UP), max(0,cap−current_held))`, `payout.amount = net−reserve` | Read-only compute in `PayoutService.approve` before payout insert. `current_held` SUM pattern = `SettlementLineJpaRepository.sumByType` (`:31-37`) |
| HOLD-02 | reserve HELD row (settlement_id UK, hold_until, transfer_ref='RSV-'+id); reserve=0 → no row | New `reserve` table (V3). Deterministic ref mirrors `PO-` (`PayoutService.java:91`). Insert only when reserve>0 |
| HOLD-03 | Ordering payout INSERT→reserve INSERT→submit; approve non-@Transactional; no 409/retry regression | `PayoutService.java:74-107` — insert reserve between line 103 and line 104. Do NOT wrap in `@Transactional` (see §Common Pitfalls) |
| HOLD-04 | `GET /v1/settlements/{id}/reserve` (404 if none) | Clone `PayoutController.payout` GET (`PayoutController.java:31-35`) + new not-found exception |
| MOCK-01 | Reuse `BankTransferPort`/`MockBankTransferClient` | Phase 1 does **not** transfer reserve (no submit for reserve until Phase 2). Port reused only for the existing payout submit — no change needed |
| INV-01 | settlement-only, V3 only, payout core unchanged, 4-module no-regression | Clone payout 02-03 gate (`payout/phases/02-hardening/02-03-VERIFICATION.md`), **inverting** the "no settlement V3" assertion → "V3 present, no V4+, no payment migration" |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| reserve policy set/get (RCFG) | API/Backend (settlement-service) | Database (`merchant_reserve_config`) | Config is a settlement-owned original record; PUT/GET REST + native upsert |
| reserve compute (HOLD-01) | Application service (`PayoutService` extension) | Database (SUM current_held) | Pure read + arithmetic; belongs in approve flow, not domain (needs repo reads) |
| reserve HELD persistence (HOLD-02) | Database (`reserve` table) | Application service | Durable second write; UK enforces one-per-settlement |
| approve ordering (HOLD-03) | Application service (`PayoutService.approve`) | — | Non-transactional write ordering is the invariant; no other tier involved |
| reserve status query (HOLD-04) | API/Backend | Database | Read-only projection, clone payout GET |
| settlement-only gate (INV-01) | CI/verification (git diff) | — | Confinement proof, not runtime |

## Standard Stack

No new dependencies. Everything is already wired in `settlement-service`.

### Core (reuse in place)
| Component | Where (worktree) | Purpose in Phase 1 |
|-----------|------------------|--------------------|
| Native `ON DUPLICATE KEY` upsert | `MerchantSettlementConfigJpaRepository.java:16-24` | Clone target for reserve-config upsert (add 3 columns) |
| `COALESCE(SUM(amount),0)` native query | `SettlementLineJpaRepository.java:31-37` | Pattern for `current_held` SUM (BigDecimal, 0 when none) |
| Deterministic transfer_ref | `PayoutService.java:91` (`"PO-"+settlementId`) | Mirror as `"RSV-"+settlementId` (avoids auto-id circularity; UK guarantees uniqueness) |
| KST `ZoneId` + `LocalDate` | `SettlementWeek.java:18` (`ZoneId.of("Asia/Seoul")`) | `hold_until = LocalDate.now(KST).plusDays(holdDays)` |
| `LocalDate`↔`DATE` JPA mapping | `SettlementJpaEntity.java:26-30` (`period_start` `LocalDate`) | Proven mapping for `hold_until DATE` under ddl-auto=validate |
| `BusinessException`/`ErrorCode`/handler | `common/exception/ErrorCode.java`, `presentation/GlobalExceptionHandler.java:32-38` | Generic handler auto-maps new codes → no new handler method needed |
| Testcontainers + `@MockitoBean BankTransferPort` IT harness | `PayoutApproveHardeningIntegrationTest.java`, `MerchantSettlementConfigIntegrationTest.java` | Clone harness for reserve config IT + approve-hold IT |
| Mock Redisson (context boot) | `test/config/TestRedissonConfig.java` | Already global; no change (no new scheduler in Phase 1) |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Native `ON DUPLICATE KEY` upsert | JPA `save()` merge | Native upsert is the established idempotent pattern here (`updated_at` refresh, `active` restore); save() risks read-modify-write races |
| Native `COALESCE(SUM)` | Load all reserve rows + Java stream sum | DB-side SUM is O(1) round-trip and matches existing `sumByType`; Java-side is needless data transfer |
| Separate `RSV-` transfer_ref | Reuse payout `PO-` namespace | Spec §3 mandates RSV-/PO- separation so reserve release never collides with `uk_payout_settlement` |

**Installation:** None. `./gradlew :settlement-service:test` after implementation.

## Package Legitimacy Audit

**Not applicable.** Phase 1 installs zero external packages. All building blocks are existing settlement-service code and already-present dependencies (Spring Boot 4.0.5, Spring Data JPA, Flyway, MySQL driver, Testcontainers, Mockito, Redisson) declared in the module `build.gradle`. No npm/PyPI/crates surface.

## Architecture Patterns

### Data flow — approve with reserve hold (HOLD-01/02/03)

```
POST /v1/settlements/{id}/payout
        │
        ▼
PayoutService.approve(settlementId)          [NON-@Transactional — invariant]
        │
        ├─ settlementRepo.findById → FINALIZED? net>0?      (existing guards, lines 75-83)
        ├─ accountRepo.findActive → 400 if none             (existing, lines 84-85)
        ├─ payoutRepo.findBySettlementId → 409 if exists     (existing pre-check, lines 87-89)
        │
        ├─ [NEW] reserveConfigRepo.find(merchantId)          (read: rate/cap/holdDays, Optional)
        ├─ [NEW] reserveRepo.currentHeld(merchantId)         (read: COALESCE(SUM) HELD+RELEASING)
        ├─ [NEW] reserve = min(round(net×rate,2,HALF_UP), max(0, cap−held))   (0 if no config)
        ├─ [NEW] payoutAmount = net − reserve
        │
        ├─ payoutRepo.insertProcessing(..., payoutAmount, "PO-"+id)   ← DURABLE WRITE 1 (409 guard, line 94)
        │        └─ catch DIVE → 409-return-existing (loser stops HERE, never inserts reserve)
        │
        ├─ [NEW] if reserve>0: reserveRepo.insertHeld(         ← DURABLE WRITE 2 (after payout commit)
        │            id, merchantId, reserve, "HELD",
        │            hold_until=LocalDate.now(KST).plusDays(holdDays),
        │            "RSV-"+id)                                (uk_reserve_settlement guards re-approve)
        │
        └─ bankTransferPort.submit("PO-"+id, account, payoutAmount)   (existing, line 104)
```

**Key insight:** the reserve read (compute) happens **before** the payout insert (so `payoutAmount` is known at insert time), but the reserve **row insert** happens **after** the payout insert succeeds. This guarantees a race loser — who DIVEs at the payout insert — never writes a reserve row.

### `PayoutService` new dependencies (constructor injection)
`PayoutService.java:42-50` currently injects 4 repos. Add 2:
- `MerchantReserveConfigRepository reserveConfigRepo`
- `ReserveRepository reserveRepo`

Wire both `@Bean`s in `PersistenceConfig.java` (mirror the 6 existing beans at `:39-70`).

### Reserve compute (domain-service candidate)
The `min(round(net×rate,2,HALF_UP), max(0, cap−held))` arithmetic is pure and testable — a `domain/service/ReserveCalculator` static helper (mirror `SettlementFeeCalculator`, `domain/service/SettlementWeek`) is the lazy-correct home. Keeps `PayoutService` thin and gives a fast unit test for the cap/rounding edges without Testcontainers.

### Anti-Patterns to Avoid
- **Wrapping `approve()` in `@Transactional`** to make the two writes atomic — this breaks the 409 race (see Pitfall 1). The crash-window between the two writes is an **accepted edge** (spec §4), detectable by reconcile (out of scope).
- **Inserting the reserve row before the payout insert** — a loser would then orphan a reserve row.
- **A DB enum/CHECK on `reserve.status`** — status is `VARCHAR(20)` free string (mirror payout `DEAD`); new states in Phase 2 need no migration.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Idempotent config write | Read-then-update logic | Native `ON DUPLICATE KEY UPDATE` (`MerchantSettlementConfigJpaRepository.java:16-24`) | Existing pattern; atomic; refreshes `updated_at`/`active` |
| current_held aggregation | Load rows + Java sum | `COALESCE(SUM(amount),0)` native query (`SettlementLineJpaRepository.java:31-37`) | DB-side, null-safe, one round-trip |
| One-reserve-per-settlement guard | App-level check | `uk_reserve_settlement` UK + deterministic `RSV-`+id | DB enforces; re-approve/race safe |
| 400/404 error mapping | New `@ExceptionHandler` | New `ErrorCode` + `BusinessException` subclass; generic handler at `GlobalExceptionHandler.java:32-38` auto-maps | No handler code needed |
| KST today | Manual UTC offset math | `LocalDate.now(ZoneId.of("Asia/Seoul"))` (`SettlementWeek.java:18`) | Established KST source |

## Runtime State Inventory

Greenfield-within-service (two brand-new tables, no rename/migration of existing data). Not a rename/refactor phase — inventory categories are trivially empty:
- **Stored data:** None — `merchant_reserve_config`/`reserve` are new; no existing rows to migrate.
- **Live service config:** None — new REST routes only; scheduler lock keys unchanged (no new scheduler in Phase 1).
- **OS-registered state:** None.
- **Secrets/env vars:** None new (reserve release webhook secret is Phase 2). Existing `PAYOUT_WEBHOOK_SECRET`/`PAYOUT_BANK_URL` untouched.
- **Build artifacts:** None — no package rename; module gradle unchanged (no new deps).

## Common Pitfalls

### Pitfall 1: Wrapping `approve()` in `@Transactional` (BLOCKER-class)
**What goes wrong:** payout INSERT moves to commit-time flush; the `uk_payout_settlement` DIVE escapes the `catch` at `PayoutService.java:95` → race loser returns 500 instead of 409, and `submit` may already have fired (double-pay).
**Why it happens:** natural instinct to make payout+reserve writes atomic.
**How to avoid:** keep `approve()` non-`@Transactional`; reserve insert is a *separate* durable write after the payout insert. The two-write crash-window is explicitly accepted (spec §4, §8).
**Warning signs:** `PayoutApproveHardeningIntegrationTest.concurrentRace_exactlyOnePayoutAndOneSubmit` returns `{200,500}` instead of `{200,409}`, or `countPayout > 1`, or `submit` verified `times(2)`.

### Pitfall 2: Backward-compat regression on existing payout ITs
**What goes wrong:** existing payout ITs assert `payout.amount == net`. If reserve wiring changes amount for no-config merchants, they fail.
**Why it happens:** computing reserve>0 when config is absent (e.g. defaulting rate to 0.05, or NPE-defaulting).
**How to avoid:** config absent / `active=false` / rate 0 / cap reached ⇒ `reserve=0` ⇒ `payout=net` ⇒ **no reserve row**. All existing ITs seed merchants with **no** reserve config, so they must stay green unchanged.
**Assertions that must stay green (all use merchants without reserve config):**
- `PayoutTracerIntegrationTest` — `amountOf(ref).isEqualByComparingTo(net)` (`:97`), `submit(eq(ref),any(),eq(net))` (`:98`).
- `PayoutApproveHardeningIntegrationTest` — body `contains("24500.00")` (`:102`), `submit(eq("PO-"+id),any(),any())` `times(1)` (`:106,132`), `countPayout==1`.
- `PayoutConvergenceIntegrationTest`, `PayoutQueryIntegrationTest`, `PayoutRetryIntegrationTest`, `PayoutPollIntegrationTest` — payout amount/flow unchanged.
**Warning signs:** any of the above assert on a value ≠ net after wiring.

### Pitfall 3: ddl-auto=validate mismatch (V3 vs entity)
**What goes wrong:** boot fails if `reserve`/`merchant_reserve_config` entity column types don't exactly match V3 DDL.
**Why it happens:** `hold_until DATE` must map to `LocalDate` (not `Instant`); money `DECIMAL(19,2)`; rate `DECIMAL(5,4)`; `status VARCHAR(20)`.
**How to avoid:** mirror `SettlementJpaEntity` (`LocalDate periodStart` for DATE, `:26`) and `PayoutJpaEntity` (`amount precision=19 scale=2`, `status length=20`, `:33-40`). `merchant_id` PK = client-supplied `@Id` **no** `@GeneratedValue` (mirror `MerchantSettlementConfigJpaEntity.java:20-22`); `reserve.id` = `@GeneratedValue(IDENTITY)` (mirror `PayoutJpaEntity.java:23-24`).
**Warning signs:** `SchemaManagementException` on any settlement IT boot.

### Pitfall 4: BigDecimal SUM returns null / wrong scale
**What goes wrong:** `SUM` over zero rows returns SQL NULL → NPE in `max(0, cap−held)`.
**How to avoid:** `COALESCE(SUM(amount),0)` (exactly `SettlementLineJpaRepository.java:32`). Return `BigDecimal`; treat null-safe.

### Pitfall 5: cloud-sync duplicate files
**What goes wrong:** editing base repo `/Users/juho/Documents/docswithmulti` instead of the worktree corrupts a parallel session.
**How to avoid:** every read/write uses `/Users/juho/Documents/docswithmulti-reserve/**`. (Task-level hard constraint.)

## Code Examples (clone targets, verbatim from worktree)

### Config upsert to clone (add reserve_rate, reserve_cap, hold_days)
```java
// Source: MerchantSettlementConfigJpaRepository.java:16-24
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(value = """
    INSERT INTO merchant_settlement_config
        (merchant_id, fee_rate, active, created_at, updated_at)
    VALUES (:merchantId, :feeRate, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
    ON DUPLICATE KEY UPDATE fee_rate = :feeRate, active = TRUE, updated_at = CURRENT_TIMESTAMP(3)
    """, nativeQuery = true)
int upsert(@Param("merchantId") long merchantId, @Param("feeRate") BigDecimal feeRate);
```

### current_held SUM pattern
```java
// Source: SettlementLineJpaRepository.java:31-37 (adapt: filter status IN ('HELD','RELEASING'), scalar SUM)
@Query(value = """
    SELECT COALESCE(SUM(amount), 0)
      FROM reserve
     WHERE merchant_id = :merchantId AND status IN ('HELD','RELEASING')
    """, nativeQuery = true)
BigDecimal currentHeld(@Param("merchantId") long merchantId);
```

### Validation to clone (rate/cap/holdDays → 400)
```java
// Source: SettlementConfigService.java:28-40 — clone the shape, add cap>=0 & holdDays>=0 checks.
// Throwing IllegalArgumentException → 400 via GlobalExceptionHandler.java:40-44 ("INVALID_REQUEST"),
// OR a new BusinessException(INVALID_RESERVE_CONFIG) → 400 via GlobalExceptionHandler.java:32-38.
if (reserveRate.signum() < 0 || reserveRate.compareTo(BigDecimal.ONE) >= 0)
    throw new IllegalArgumentException("reserveRate는 0 이상 1 미만이어야 합니다: " + reserveRate);
if (reserveRate.scale() > 4)
    throw new IllegalArgumentException("reserveRate 소수 자릿수는 4 이하여야 합니다: " + reserveRate);
```
> **Discretion note:** existing settlement validation uses `IllegalArgumentException`→400 (`SettlementConfigService.setRate`). The spec/task suggest a dedicated `INVALID_RESERVE_CONFIG` ErrorCode. Either satisfies "→400"; a new `ErrorCode` gives a stable code string. Both auto-handled — no new handler method.

### Not-found exception to clone (404)
```java
// Source: PayoutNotFoundException.java — mirror for RESERVE_NOT_FOUND and RESERVE_CONFIG_NOT_FOUND
public class ReserveNotFoundException extends BusinessException {
    public ReserveNotFoundException(long settlementId) {
        super(ErrorCode.RESERVE_NOT_FOUND, "유보 건을 찾을 수 없습니다. settlementId=" + settlementId);
    }
}
```
New `ErrorCode` entries (append to `ErrorCode.java`):
```java
INVALID_RESERVE_CONFIG("INVALID_RESERVE_CONFIG", 400, "유보 정책 값이 올바르지 않습니다."),
RESERVE_NOT_FOUND("RESERVE_NOT_FOUND", 404, "유보 건을 찾을 수 없습니다."),
RESERVE_CONFIG_NOT_FOUND("RESERVE_CONFIG_NOT_FOUND", 404, "유보 정책을 찾을 수 없습니다."),
```

### GET clone targets
- Config GET: **none exists today** for settlement config (PUT-only, `SettlementConfigController.java`). Clone the GET shape from `PayoutAccountController.get` (`:38-43`) — `@GetMapping("/{merchantId}")` → service `.orElseThrow(RESERVE_CONFIG_NOT_FOUND)` → response record. New route `GET /v1/settlements/reserve-config/{merchantId}`.
- Reserve status GET: clone `PayoutController.payout` (`:31-35`) → `GET /v1/settlements/{id}/reserve`.

### Flyway V3 (new file `V3__create_reserve.sql`)
Per spec §3 — `merchant_reserve_config` (client PK, no auto-id) + `reserve` (auto-id, `uk_reserve_settlement`, `idx_reserve_status`, `idx_reserve_merchant`, `status VARCHAR(20)` no enum, `amount DECIMAL(19,2)`, `hold_until DATE`, `transfer_ref VARCHAR(120)`). **Never modify V1/V2** (CLAUDE.md — new version only).

## State of the Art

| Old (payout v1.0) | New (reserve Phase 1) | Impact |
|-------------------|-----------------------|--------|
| `payout.amount = net` (full net) | `payout.amount = net − reserve` | Only for merchants **with** reserve config; others unchanged |
| Single durable write in approve (payout insert) | Two durable writes (payout, then reserve HELD) | Accepted crash-window between them (reconcile-detectable, Phase 2/v4) |
| INV gate asserts **no** settlement V3 (payout 02-03) | INV gate asserts **V3 present**, no V4+, no payment migration | Delta: reserve legitimately adds V3 (§INV-01 Gate) |

**Deprecated/outdated:** none.

## INV-01 Gate (delta vs payout 02-03)

Clone `payout/phases/02-hardening/02-03-VERIFICATION.md` gate structure:
1. **merge-base confinement**: `git diff BASE...HEAD --name-only` (BASE=`git merge-base HEAD main` = `baf99352`) → every path under `settlement-service/` (+`.planning/`·`docs/`). `NON_SETTLEMENT_DIFF` empty.
2. **module denylist** (belt-and-suspenders): `git diff BASE...HEAD --name-only -- payment-service order-service product-service merchant-limit-service risk-management-service user-service api-gateway` → **empty**.
3. **Flyway** — **INVERTED from payout gate**: payout gate asserted "no settlement V-file above V2". Reserve Phase 1 asserts:
   - `settlement-service/.../db/migration/` = `V1`, `V2`, **`V3__create_reserve.sql`** — and **no `V4+`**.
   - `git diff BASE...HEAD --name-only -- 'payment-service/**/db/migration/*'` = **empty** (payment migration 0).
   - V1/V2 unchanged in diff.
4. **4-module no-regression**: `./gradlew :payment-service:test :order-service:test :product-service:test :settlement-service:test` → all green. Existing payout ITs green (backward-compat, §Pitfall 2).

> **Delta callout for planner:** the *only* substantive change from the payout gate is assertion 3 — flip "no V3" to "V3 required, no V4+". Everything else (confinement, denylist, no-payment-migration, no-regression) is verbatim.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Config validation may use either `IllegalArgumentException`→400 (existing style) or new `INVALID_RESERVE_CONFIG` ErrorCode | Code Examples | Low — both yield 400; planner/discuss picks one for code-string stability |
| A2 | `ReserveCalculator` as a `domain/service` static helper (mirror `SettlementFeeCalculator`) is the right home for compute | Architecture Patterns | Low — could inline in `PayoutService`; either testable |
| A3 | `current_held` excludes RELEASE_FAILED/RELEASE_DEAD (spec §10 open question, current = exclude) | HOLD-01 | Medium — spec flags this as re-examinable; Phase 1 has no release states yet so effectively HELD-only until Phase 2 |
| A4 | Reserve status query GET reuses `PayoutController`-style route on settlement id | HOLD-04 | Low — matches spec §7 `ReserveQueryController` |

## Open Questions

1. **ErrorCode style for config validation (A1)** — Known: both `IllegalArgumentException` and `BusinessException` reach 400. Unclear: whether a stable `INVALID_RESERVE_CONFIG` code is required by any consumer. Recommendation: add the `ErrorCode` (cheap, explicit) — matches spec intent.
2. **current_held membership of failed states (A3, spec §10)** — Deferred to Phase 2 (no release states exist in Phase 1). Recommendation: implement SUM as `status IN ('HELD','RELEASING')` now; revisit in Phase 2.
3. **reserve-balance endpoint `GET /v1/merchants/{id}/reserve-balance`** — marked **optional (선택)** in spec §5 / HOLD-04. Recommendation: planner may descope to a stretch task; `current_held` SUM already exists for it.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| MySQL 8.0 (Testcontainers) | settlement ITs | ✓ (existing ITs) | mysql:8.0 | — |
| Spring Boot / JPA / Flyway | whole phase | ✓ | Boot 4.0.5 | — |
| Redisson (mock in tests) | context boot | ✓ | `TestRedissonConfig` | — |
| Gradle | build/test | ✓ | wrapper | — |

**Missing dependencies:** none. Phase 1 adds no external dependency.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ + Mockito + Testcontainers (MySQL) |
| Config file | none — `@Testcontainers`+`@DynamicPropertySource` per IT class |
| Quick run | `./gradlew :settlement-service:test --tests '*Reserve*'` |
| Full suite | `./gradlew :settlement-service:test` |
| Phase gate | `./gradlew :payment-service:test :order-service:test :product-service:test :settlement-service:test` (INV-01 no-regression) |

### Phase Requirements → Test Map
| Req | Behavior | Type | Command | File Exists? |
|-----|----------|------|---------|-------------|
| RCFG-01/02 | PUT upsert + overwrite, GET 404, validation 400 | integration | `:settlement-service:test --tests '*ReserveConfig*'` | ❌ Wave 0 — clone `MerchantSettlementConfigIntegrationTest` |
| HOLD-01 | reserve=min(round(net×rate),cap−held); payout=net−reserve | integration + unit | `--tests '*ReserveHold*'` / `*ReserveCalculator*` | ❌ Wave 0 |
| HOLD-02 | HELD row (UK, hold_until KST, RSV- ref); reserve=0→no row | integration | `--tests '*ReserveHold*'` | ❌ Wave 0 |
| HOLD-03 | ordering + non-@Transactional; 409 race & retry no-regression | integration | `--tests '*PayoutApproveHardening*'` (must stay green) | ✅ exists |
| HOLD-04 | GET reserve 200/404 | integration | `--tests '*ReserveQuery*'` | ❌ Wave 0 |
| INV-01 | settlement-only, V3 only, no payment migration | manual gate + 4-module suite | git diff gate + full `:test` | ❌ Wave 0 (VERIFICATION doc) |

### Sampling Rate
- **Per task:** `:settlement-service:test --tests '*Reserve*'`
- **Per wave:** `:settlement-service:test` (includes all existing payout ITs — backward-compat proof)
- **Phase gate:** 4-module suite green + INV-01 git-diff gate.

### Wave 0 Gaps
- [ ] `ReserveConfigIntegrationTest` — clone `MerchantSettlementConfigIntegrationTest` (upsert/overwrite/GET-404/validation-400).
- [ ] `ReserveHoldIntegrationTest` — clone `PayoutApproveHardeningIntegrationTest` harness; assert payout=net−reserve, HELD row, hold_until=today(KST)+holdDays, RSV- ref; config-absent → payout=net + no row.
- [ ] `ReserveCalculatorTest` — unit (rounding HALF_UP, cap clamp, cap-exhausted→0, config-absent→0).
- [ ] `ReserveQueryIntegrationTest` — GET reserve 200/404.
- [ ] INV-01 VERIFICATION doc (clone payout 02-03, flip V3 assertion).
- [ ] No framework install needed.

## Security Domain

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V5 Input Validation | yes | rate `0≤r<1` scale≤4, cap≥0, holdDays≥0 → 400 (clone `SettlementConfigService.setRate`). Money `BigDecimal DECIMAL(19,2)`, rate `DECIMAL(5,4)` — precision enforced at DB + entity |
| V4 Access Control | partial (deploy-time) | No in-service ADMIN check — cross-merchant/forged writes blocked by NetworkPolicy gateway-ingress (existing class, `SettlementConfigController` javadoc `:15`). Same objective decision as payout/product ingress |
| V6 Cryptography | no (Phase 1) | Reserve release webhook signature is **Phase 2** (`/v1/reserves/callback`) — not in scope |
| V2/V3 Auth/Session | no | trusted-header model unchanged; no new auth surface |

| Threat | STRIDE | Mitigation |
|--------|--------|-----------|
| Forged/cross-merchant reserve-config write | Tampering/Elevation | NetworkPolicy gateway-only ingress (deploy gate, existing) |
| Double reserve (re-approve/race) | Tampering | `uk_reserve_settlement` UK + deterministic `RSV-`+id + loser DIVEs at payout insert |
| Negative/overflow reserve amount | Tampering | rate/cap validation 400 + `max(0, ...)` clamp + `DECIMAL(19,2)` |
| Overcap holdback | — | `current_held` recomputed per approve, `min(desired, cap−held)` |

## Sources

### Primary (HIGH confidence — worktree source, file:line verified this session)
- Spec: `docs/superpowers/specs/2026-08-05-settlement-payout-reserve-design.md` (§1-10)
- REQUIREMENTS.md / ROADMAP.md (payout-reserve workstream)
- `PayoutService.java:74-107` (approve ordering, 409 catch, non-@Transactional)
- `MerchantSettlementConfig*` stack (entity/JpaRepo/Impl/port/Service/Controller/DTO), `PersistenceConfig.java:39-70`
- `SettlementLineJpaRepository.java:31-37` (COALESCE(SUM))
- `SettlementWeek.java:18` (KST), `SettlementJpaEntity.java:26` (LocalDate↔DATE)
- `ErrorCode.java`, `GlobalExceptionHandler.java:32-44`, `PayoutNotFoundException.java`, `InvalidPayoutAccountException.java`, `PayoutNotPayableException.java`
- `PayoutJpaEntity.java`/`PayoutJpaRepository.java`/`PayoutRepositoryImpl.java` (entity/upsert/guarded-UPDATE patterns)
- `PayoutApproveHardeningIntegrationTest.java`, `MerchantSettlementConfigIntegrationTest.java`, `PayoutTracerIntegrationTest.java:97-98`, `TestRedissonConfig.java`
- `payout/phases/02-hardening/02-03-VERIFICATION.md` (INV gate to clone/invert)
- `V2__create_payout.sql`, migration dir (V1/V2 only — V3 free), `application.yml:48-70`

### Secondary / Tertiary
- None — no web/external lookups needed; all findings internal.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every clone target read at file:line; zero new deps.
- Architecture (approve ordering): HIGH — invariant documented in code (`PayoutService.java:96-99` ponytail comment) + spec §4/§8 + existing IT proves 409 behavior.
- Pitfalls: HIGH — backward-compat assertions located verbatim; ddl-validate mapping proven by existing entities.
- INV gate delta: HIGH — payout 02-03 VERIFICATION read directly; only V3 assertion flips.

**Research date:** 2026-08-06
**Valid until:** ~2026-09-05 (stable — internal codebase, no fast-moving external surface)
