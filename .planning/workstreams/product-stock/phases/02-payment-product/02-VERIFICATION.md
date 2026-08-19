---
phase: 02-payment-product
verified: 2026-07-31T00:00:00Z
status: passed
score: 6/6 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 2: 결제 예약 통합 (payment↔product) Verification Report

**Phase Goal:** 결제 생성 시 payment가 product로 SKU 재고를 동기 예약하고, 실패면 결제 거부. sku_id/quantity(V16) 관통 + 예약 후 TX 실패 보상.
**Verified:** 2026-07-31
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | **취소 코어 불변 (최우선)** — CancelPaymentService/CancelTxWriter/CancelDomainService/scheduler 3종/messaging/outbox 무변경 | ✓ VERIFIED | Core-unchanged gate(정확한 파일명 regex) → NO MATCH. Phase 2 7커밋(033b451…c62b03a) 전부 payment-service만 touch. `PaymentItem.of`(6인자)/`reconstruct`(8인자) 하위호환 오버로드가 skuId=null,quantity=1로 위임 → 취소 코어 테스트 시그니처 보존. gateway/user 소스 무변경. |
| 2 | **RSV-01 동기 예약 + fail-closed** — reserve(TX 밖) 성공 후에만 persist, 재고부족(409)·CB OPEN·장애 → 결제 거부·payment 미생성 | ✓ VERIFIED (behavioral) | `CreatePaymentService`: paymentKey→`productStockPort.reserve`(TX 밖)→`paymentCreateTxWriter.persist`. `ProductStockHttpClient` catch 계층: 409→StockInsufficientException(409), CB OPEN/5xx→ProductServiceException(503), Error 전파. **재실행 fresh green**: `CreatePaymentReserveIntegrationTest`(실 MySQL) — A: 200→payment 1행, B: 409→0행, C: CB OPEN(transitionToOpenState)→503·0행. `ProductStockHttpClientTest#reserve_circuitBreakerOpen_throwsProductServiceException`. |
| 3 | **RSV-02 sku_id/quantity 관통 + V16** — 요청~payment_item(V16) 영속, 신규생성 필수검증 | ✓ VERIFIED (behavioral) | 관통: `CreatePaymentItemRequest`(@NotNull Long skuId, @Positive int quantity)→`PaymentController`→`CreatePaymentCommand.Item`→`PaymentCreateTxWriter`(`PaymentItem.of` 8인자)→`PaymentItemJpaEntity`(@Column sku_id/quantity)→V16 DDL. 통합테스트 jdbc assert: sku_id=500, quantity=2 DB 왕복. V16: `sku_id BIGINT NULL, quantity INT NOT NULL DEFAULT 1`. |
| 4 | **RSV-03 예약 후 persist 실패 보상 + 재시도** — release best-effort, 실패 시 V17 적재 후 스케줄러 재시도 | ✓ VERIFIED (behavioral) | `CreatePaymentService.compensateReserve`: persist catch(RuntimeException)→release→실패 시 `stockReleaseRetryRepository.enqueue`→원예외 재던짐. V17 `stock_release_retry`(payment_key UK, items_json, attempt_count, next_retry_at, status). `StockReleaseRetryService.retryOne`: 역직렬화→release→markDone/실패 시 markRetryLater(<5)/exhaust(≥5, MAX=5, 백오프 attempt*60s). `StockReleaseRetryScheduler`: Redisson tryLock, @Scheduled(30s). RepositoryImpl 실 JPA(UK 멱등 enqueue). **재실행 fresh green**: `CreatePaymentCompensationTest`(3케이스), `StockReleaseRetryServiceTest`(4케이스). |
| 5 | **reserve @Transactional 밖 + TxWriter 분리** — TX 안 HTTP 안티패턴 회피, 자기호출 프록시 우회 | ✓ VERIFIED | `CreatePaymentService`에 @Transactional 없음(비-TX 오케스트레이터). `PaymentCreateTxWriter` 별도 @Component + persist()만 @Transactional. reserve는 persist TX 앞·밖에서 호출. |
| 6 | **레이어 규약 + 무-신규-의존성** — domain 순수 POJO, 신규 외부 의존성 0 | ✓ VERIFIED | `PaymentItem` Spring/JPA 어노테이션 없음(순수 POJO). `ProductStockPort` application/interfaces. build.gradle 의존성 변경 0(Resilience4j/RestTemplate/Redisson/Jackson 기존 재사용). |

**Score:** 6/6 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `V16__add_sku_and_quantity_to_payment_item.sql` | sku_id BIGINT NULL, quantity INT NOT NULL DEFAULT 1 | ✓ VERIFIED | 정확히 일치 |
| `V17__create_stock_release_retry.sql` | payment_key UK, items_json, attempt_count, next_retry_at, status | ✓ VERIFIED | UK + idx_stock_release_status_next 포함 |
| `ProductStockPort.java` + `ProductStockHttpClient.java` | reserve/release, 전용 CB, fail-closed | ✓ VERIFIED | productServiceCircuitBreaker 주입, catch 계층 복제 |
| `PaymentCreateTxWriter.java` + 재구조화 `CreatePaymentService` | @Transactional persist + 비-TX 오케스트레이터 | ✓ VERIFIED | 분리 확인 |
| `StockReleaseRetryService/Scheduler` | compensation-retry 동형, 30s Redis 락 | ✓ VERIFIED | MAX=5 백오프, Redisson tryLock |
| `CreatePaymentReserveIntegrationTest.java` | 200→저장, 409/CB OPEN→거부 | ✓ VERIFIED | 실 MySQL 3시나리오 |

### Key Link Verification

| From | To | Via | Status |
|------|----|----|--------|
| CreatePaymentService | ProductStockPort.reserve | paymentKey→reserve(TX 밖)→persist 순서 | ✓ WIRED |
| ProductStockHttpClient | ErrorCode | 409→StockInsufficientException, CB OPEN→ProductServiceException | ✓ WIRED |
| Request→Controller→Command→TxWriter→JpaEntity | V16 DDL | sku_id/quantity 관통 (jdbc 왕복 증명) | ✓ WIRED (data flows) |
| CreatePaymentService catch | StockReleaseRetryRepository.enqueue | release 실패 시 적재 | ✓ WIRED |
| StockReleaseRetryScheduler | ProductStockPort.release | retryAll→retryOne (Redis 락, 30s) | ✓ WIRED |
| PaymentItem.of/reconstruct 하위호환 오버로드 | 취소 코어 | 6/8인자 시그니처 보존 (core-unchanged 보호) | ✓ WIRED |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| fail-closed (200/409/CB OPEN) 실 MySQL | `test --rerun-tasks CreatePaymentReserveIntegrationTest` | BUILD SUCCESSFUL (fresh) | ✓ PASS |
| 보상 (persist 실패→release→enqueue) | `... CreatePaymentCompensationTest` | BUILD SUCCESSFUL (fresh) | ✓ PASS |
| 재시도 (markDone/markRetryLater/exhaust) | `... StockReleaseRetryServiceTest` | BUILD SUCCESSFUL (fresh) | ✓ PASS |
| HTTP catch 계층 (409/CB OPEN/5xx) | `... ProductStockHttpClientTest` | BUILD SUCCESSFUL (fresh) | ✓ PASS |
| 전체 스위트 | `:payment-service:test` | BUILD SUCCESSFUL (291 tests) | ✓ PASS |

### Core-Unchanged Gate (최우선)

```
git diff --name-only $(git merge-base HEAD origin/main)..HEAD | grep -E 'CancelPaymentService|CancelTxWriter|CancelDomainService|ProcessingRecoveryScheduler|PendingRecoveryScheduler|CompensationRetryScheduler|CancelEventOutboxPublisher|infrastructure/messaging|cancel_event_outbox'
→ NO MATCH (취소 코어 무변경, 신규 StockReleaseRetryScheduler는 취소 코어 아님 — 정상 미매치)
```

### Requirements Coverage

| Requirement | Source Plan | Status | Evidence |
|-------------|-------------|--------|----------|
| RSV-01 (동기 예약+실패 거부) | 02-01 | ✓ SATISFIED | Truth #2 + 통합테스트 A/B/C |
| RSV-02 (sku_id+quantity V16) | 02-01 | ✓ SATISFIED | Truth #3 + jdbc 왕복 assert |
| RSV-03 (예약 후 TX 실패 보상) | 02-02 | ✓ SATISFIED | Truth #4 + Compensation/Retry 테스트 |

### Anti-Patterns Found

None. 변경된 payment-service 파일에 TODO/FIXME/XXX/HACK/PLACEHOLDER 디버트 마커 0건. Known Stubs 없음(모든 경로 테스트 증명). 신규 외부 의존성 0.

### Human Verification Required

None — 모든 트루스가 프로그래밍적으로 검증 가능하며, behavior-dependent 트루스(fail-closed 상태전이·보상 클린업·재시도 정리)는 재실행 fresh green 테스트로 증명됨.

### Gaps Summary

없음. Phase 2 목표(결제 생성 시 동기 SKU 재고 예약, 실패 시 fail-closed 거부, sku_id/quantity V16 관통, 예약 후 TX 실패 보상+재시도)가 코드베이스에 실제로 구현·배선·테스트되었으며, 최우선 제약인 취소 코어 불변이 게이트로 확인됨.

---

_Verified: 2026-07-31_
_Verifier: Claude (gsd-verifier)_
