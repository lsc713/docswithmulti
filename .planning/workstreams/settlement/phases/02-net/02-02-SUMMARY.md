---
phase: 02-net
plan: 02
subsystem: settlement-service
status: complete
tags: [settlement, fee-calc, bigdecimal, half-up, merchant-config, rate, upsert]
requires:
  - "settlement-service module + V1 merchant_settlement_config table (Phase 1, existing)"
  - "GlobalExceptionHandler IllegalArgumentException→400 mapping (Phase 1, existing)"
provides:
  - "SettlementFeeCalculator: pure fee/VAT/net BigDecimal calculator (HALF_UP scale 2) [FEE-02]"
  - "MerchantSettlementConfig entity/repo: findRate→Optional (active only) [FEE-01]"
  - "PUT /v1/settlements/config/{merchantId} rate upsert with feeRate validation"
affects:
  - "PersistenceConfig (+1 bean: merchantSettlementConfigRepository)"
tech-stack:
  added: []          # zero new external deps
  patterns:
    - "pure static domain calculator (no Spring/I/O) unit-tested to HALF_UP tie boundary"
    - "native INSERT..ON DUPLICATE KEY UPDATE upsert (mirror SettlementJpaRepository.ensureRow)"
    - "Optional rate lookup — empty (unset/inactive) ⇒ calculator not invoked ⇒ finalize deferred (no default 0%)"
    - "in-service validation → IllegalArgumentException → 400 (reuse existing handler, no new ErrorCode)"
key-files:
  created:
    - settlement-service/src/main/java/com/example/settlement/domain/service/SettlementFeeCalculator.java
    - settlement-service/src/test/java/com/example/settlement/domain/SettlementFeeCalculatorTest.java
    - settlement-service/src/main/java/com/example/settlement/application/interfaces/MerchantSettlementConfigRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantSettlementConfigJpaEntity.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantSettlementConfigJpaRepository.java
    - settlement-service/src/main/java/com/example/settlement/infrastructure/persistence/MerchantSettlementConfigRepositoryImpl.java
    - settlement-service/src/main/java/com/example/settlement/application/service/SettlementConfigService.java
    - settlement-service/src/main/java/com/example/settlement/presentation/controller/SettlementConfigController.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/SettlementConfigRequest.java
    - settlement-service/src/main/java/com/example/settlement/presentation/dto/SettlementConfigResponse.java
    - settlement-service/src/test/java/com/example/settlement/integration/MerchantSettlementConfigIntegrationTest.java
  modified:
    - settlement-service/src/main/java/com/example/settlement/infrastructure/config/PersistenceConfig.java
decisions:
  - "net NOT floored — negative net returned as-is when cancel > gross (RESEARCH A7, spec §4 has no floor)"
  - "fee/vat/net computed pure, NOT persisted in Phase 2 — settlement.fee/vat/net stay DEFAULT 0 (FINALIZE = Phase 3)"
  - "rate seeded via write path (PUT/repo.upsert) in tests, no Flyway prod-data seed"
  - "no in-service ADMIN check on PUT — cross-merchant/forged-rate writes blocked at deploy by NetworkPolicy (gateway-only ingress), same class as payment/product"
  - "validation throws IllegalArgumentException (reuse existing 400 handler) rather than adding a new ErrorCode"
metrics:
  duration: ~25m
  completed: 2026-08-04
  tasks: 2
  files: 12
requirements: [FEE-01, FEE-02]
---

# Phase 2 Plan 2: Fee/VAT/net calculator + merchant rate config Summary

Pure BigDecimal fee/VAT/net calculator (HALF_UP scale 2, negative net unfloored, nothing persisted) plus the `merchant_settlement_config` rate source it reads — entity/repo owning V1's table, `findRate → Optional` (active-only), and a thin validated `PUT /v1/settlements/config/{merchantId}` upsert. Settlement-only; no payment/cancel-core change; no overlap with plan 02-01.

## Tasks Completed

| Task | Name | Commit | Files |
| ---- | ---- | ------ | ----- |
| 1 (RED) | Failing calculator unit tests | 5149b52 | SettlementFeeCalculatorTest.java |
| 1 (GREEN) | Pure SettlementFeeCalculator [FEE-02] | c3e2551 | SettlementFeeCalculator.java |
| 2 | Config entity/repo + read/upsert + PUT [FEE-01] | 04103ee | entity/repo/impl/service/controller/2 DTOs + PersistenceConfig + IT |

## Tests

- `SettlementFeeCalculatorTest`: **6 passed** — basic formula (96370.00), exact `.xx5` HALF_UP tie (10.00×0.3505=3.505→3.51, distinct from HALF_EVEN), spec boundary (12345×0.0335=413.5575→413.56), vat-from-rounded-fee (3.51→0.35), negative-net unfloored (−403.63), scale-2 invariant.
- `MerchantSettlementConfigIntegrationTest`: **5 passed** (Testcontainers MySQL 8.0, RANDOM_PORT + java.net.http.HttpClient) — PUT→findRate round-trip, PUT overwrite, unset→empty, inactive→empty, invalid feeRate (−0.01 / 1.0 / scale>4)→400 with no write.
- **Total: 11 tests, 0 failures.** Entity boot-validated against V1 DDL (`ddl-auto=validate`) — SpringBootTest context started clean.

## Deviations from Plan

None — plan executed as written. Validation uses `IllegalArgumentException` (already mapped to 400 by the existing `GlobalExceptionHandler`), avoiding a shared `ErrorCode.java` edit that would collide with the concurrent 02-01 session.

## Full-suite note (honest)

Ran the two targeted suites only (`--tests '*SettlementFeeCalculatorTest*'` and `--tests '*MerchantSettlementConfigIntegrationTest*'`), both green. Deliberately did NOT run the full `:settlement-service:test` because a sibling executor (plan 02-01) is editing `SettlementRepository`/`SettlementJpaRepository`/`application.yml` in the SAME worktree concurrently — a full build could false-red on their in-flight files, not mine. My additions are import-isolated (new package-private classes + one additive bean), so full-suite validation belongs to the orchestrator after both wave-1 plans land.

## Parallel-safety confirmation

Touched ONLY this plan's declared files (10 new + PersistenceConfig, additive bean block). Did NOT stage or edit `SettlementRepository*`, settlement `application.yml`, any payment-service file, or `.planning/`. An untracked `payment-service/.../PaymentEventOutboxConfig.java` and `.planning/config.json` were present in the worktree (other sessions) and left untouched.

## Threat Flags

None beyond the plan's registered T-02-04 (feeRate tampering — mitigated by validation + documented NetworkPolicy deploy-gate) and T-02-05 (rate-unset → Optional.empty, no crash, no default-0% payout — confirmed by the unset/inactive IT cases).

## Self-Check: PASSED

- Files exist: SettlementFeeCalculator.java, MerchantSettlementConfigJpaEntity.java, MerchantSettlementConfigRepository.java, SettlementFeeCalculatorTest.java, MerchantSettlementConfigIntegrationTest.java — all FOUND.
- Commits: 5149b52, c3e2551, 04103ee — all present on feat/settlement-aggregation.
