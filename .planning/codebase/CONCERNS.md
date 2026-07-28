# Codebase Concerns

**Analysis Date:** 2026-07-28

## Unimplemented Critical Features

### Missing ProcessingRecoveryService Implementations

**Issue:** Two port methods required for payment recovery are stubbed and throw `UnsupportedOperationException`.

**Files:** 
- `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java:83` — `isCharged()` method
- `payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java:61` — `getStatus()` method

**Called by:**
- `ProcessingRecoveryService.recoverOne()` line 57 calls `pgCancelPort.getStatus()`
- `CompensationRetryService` may need `isCharged()` for compensation logic verification

**Impact:** 
- ProcessingRecoveryService scheduler cannot recover stale PROCESSING transactions
- PG cancellation status cannot be queried, blocking retry/recovery logic
- PROCESSING state requests stuck for >5 minutes cannot be resolved, requiring manual intervention
- Compensation retry logic incomplete

**Fix approach:** 
1. Implement `PgCancelHttpClient.getStatus()` with HTTP call to PG provider's status endpoint
2. Implement `RiskManagementHttpClient.isCharged()` with query to risk-management-service to verify if limit was actually deducted
3. Add tests for both methods in `PgCancelHttpClientCardinalityTest` and `ProcessingRecoveryServiceTest`

---

## Product Service — Unimplemented Module

**Issue:** Product service (`product-service/` module) has no implementation.

**Files:** 
- `product-service/build.gradle` exists but `src/main/java/` directory is empty
- Declared in STATUS.md as unimplemented: "[ ] product-service (상품/SKU/재고)"

**Blocked by:** 
- No SKU/inventory management available
- Cannot integrate inventory updates with payment cancellation
- No restock flow when cancellations occur

**Expected usage:** 
- Kafka consumer listening to `payment.cancelled` events
- Update SKU inventory on cancel completion
- Validate inventory availability before payment processing (future)

**Fix approach:** 
1. Create Spring Boot service structure matching payment-service pattern
2. Implement domain entities for Product, SKU, Inventory with JPA/Flyway
3. Implement Kafka consumer for `payment.cancelled` events 
4. Add restock transaction logic with tests
5. Declare in STATUS.md when complete

---

## Known Architectural Issues

### Daily Limit Resolution — HTTP-Outside-TX Risk Pattern

**Issue:** While designed correctly to avoid connection pool exhaustion, the three-tier lookup (Redis → DB snapshot → HTTP) has ordering constraints that could cause data inconsistency if violated.

**Files:** 
- `risk-management-service/src/main/java/com/example/riskmanagement/application/service/ValidateAndReserveService.java:100-120` — `resolveDailyLimit()` method
- `risk-management-service/src/main/java/com/example/riskmanagement/infrastructure/client/MerchantLimitClient.java` — HTTP client

**Current safeguard:** 
ValidateAndReserveService correctly performs HTTP call **outside transaction** to avoid blocking DB connection pool when merchant-limit-service is slow.

**Potential violation:** 
If future code refactors `resolveDailyLimit()` back into transaction or wraps entire `execute()` in TX, merchant-limit HTTP call will hold DB connection. PR #47 documented this causes 15x throughput collapse with 100ms latency injection.

**Safe practices:** 
- `resolveDailyLimit()` must remain TX-external
- HTTP must complete before `transactionTemplate.execute()` call
- Configuration flags `risk.limit.cache.enabled` and `risk.limit.snapshot.enabled` are for testing only; never disable snapshot in production

**Fix approach:** 
Add architectural test verifying `ValidateAndReserveService` does not call `TransactionTemplate` before completing all HTTP calls. Document in `docs/conventions/architecture.md`.

---

### Timezone Handling — Performance Micro-Optimization

**Issue:** `LocalDate.now(ZoneId.of("Asia/Seoul"))` is called on every cancel request, incurring string parsing overhead.

**Files:** 
- `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java:101` — KST date resolution
- Multiple services call this pattern for daily limit windowing

**Performance impact:** 
Minor (ZoneId lookup is cached by JVM after first parse), but avoidable with static constant or cached ZoneId.

**Fix approach:** 
1. Create constant: `private static final ZoneId KST = ZoneId.of("Asia/Seoul")`
2. Use in all date operations: `LocalDate.now(KST)`
3. Apply same pattern across risk-management-service and order-service

---

## Transaction Safety Concerns

### Compensation Path Exception Handling

**Issue:** Compensation failure handling has inconsistent exception chains.

**Files:** 
- `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java:104-107`
  ```java
  try {
      riskManagementPort.validateAndReserve(...);
  } catch (Exception e) {
      tryCompensate(cancelRequest, payment.getMerchantId(), cancelAmount);  // Silently catches exception
      markFailed(cancelRequest, e.getMessage());
      throw e;  // Re-throws validateAndReserve exception, not compensation exception
  }
  ```

**Problem:** 
If `tryCompensate()` fails (its own exception caught and logged), the original validateAndReserve exception is thrown, losing compensation failure context. Caller has no visibility that compensation was attempted and failed.

**Impact:** 
- Compensation failures may not be detected until scheduled `compensation-retry` runs
- If compensation-retry also fails, issue goes unresolved until manual intervention
- Monitoring/alerting cannot distinguish "reserve failed" from "reserve + compensate failed"

**Fix approach:** 
1. Create composite exception type: `CancelWithCompensationException(reserveException, compensationException)`
2. Throw composite when both fail: `throw new CancelWithCompensationException(e, compensationException)`
3. Update error handling in controllers to expose both failure points
4. Add metrics for "compensation was required" and "compensation failed" separately

---

## Distributed System Resilience

### Processing Recovery — Incomplete Error Scenarios

**Issue:** ProcessingRecoveryService has gaps in handling edge cases.

**Files:** 
- `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java:86-115`

**Specific gaps:**

1. **Line 95:** `cancelRequest.incrementPgRetryCount()` incremented in memory; if `save()` fails, retry count is lost
   ```java
   cancelRequest.incrementPgRetryCount();
   cancelRequestRepository.save(cancelRequest);  // May fail
   ```
   Fix: Use atomic increment at DB level, not object mutation + save

2. **Line 121-135:** `markPgPending()` flag may not persist if timeout path executes concurrently
   - Two threads: one sets `pgPendingSince`, another may be in compensateAndFail
   - Second call to `compensateAndFail()` doesn't re-check timeout
   - Result: PENDING state recorded twice or lost

3. **Line 69:** `handlePgPending()` assumes single recovery run per stale record
   - If scheduler runs concurrently on two instances, both may call `handlePgPending()`
   - No idempotency guard (unlike Kafka offset commits)

**Fix approach:** 
1. Add idempotency check at start of `recoverOne()`: `if (CancelRequest.isRecoveryInProgress()) return;`
2. Change retry count to atomic DB update with SQL: `UPDATE cancel_request SET pg_retry_count = pg_retry_count + 1 WHERE ...`
3. Add distributed lock per `cancelRequestId` to prevent concurrent recovery attempts
4. Test recovery under concurrent scheduler runs with Testcontainers MySQL

---

## Outbox Poller Configuration Complexity

**Issue:** Dual-datasource architecture for outbox polling introduces connection pool management complexity.

**Files:** 
- `payment-service/src/main/java/com/example/payment/infrastructure/config/OutboxDataSourceConfig.java` — Manual DataSource configuration
- `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisherIT.java` — Integration tests must configure both pools

**Complexity introduced:**
1. Two `HikariDataSource` instances with separate pool sizing
2. `@Qualifier` required on all poller/TX3 components
3. Flyway migrations must be applied to both pools (if separate DB user/permissions)
4. Connection pool exhaustion could manifest differently on each pool

**Mitigation in place:** 
ValidateAndReserveService moves HTTP outside TX to prevent poller-specific pool starvation.

**Future risk:** 
If poller performance degrades (batch size tuning, retry backoff), tuning one pool may not reflect in the other.

**Fix approach:** 
1. Document pool sizing strategy in `docs/infrastructure/datasource-config.md`: "Main pool = (max concurrent requests) + 5 buffer; Outbox pool = 2-3 (single-threaded poller)"
2. Add metrics for each pool: `HikariPool.Active`, `HikariPool.Pending`, `HikariPool.Idle`
3. Alert on pool saturation (Active + Pending >= max pool size)
4. Test pool exhaustion scenarios: "what if poller queries take 10s each"

---

## Test Coverage Gaps

### Recovery Schedulers — Limited Failure Scenario Testing

**Issue:** ProcessingRecoveryService and PendingRecoveryService have complex state machines but tests don't cover all failure paths.

**Files:** 
- `payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryServiceTest.java:87-116` — Only tests isApproved, isFailed, isPending success paths
- Missing: PG query timeout, PG returns PENDING for >1 hour, concurrent recovery runs

**Untested scenarios:**
1. PG service timeout → `pgCancelPort.getStatus()` throws timeout exception → scheduler continues
2. Payment record deleted between PROCESSING check and PG query (data inconsistency)
3. Two scheduler instances recover same CancelRequest simultaneously
4. Kafka send fails in TX3 of recovery → PROCESSING not rolled back, retry loop

**Impact:** 
- Production recovery logic untested until failures occur
- Edge cases (network timeouts, race conditions) discovered in incidents

**Fix approach:** 
1. Expand ProcessingRecoveryServiceTest with `@Nested` classes for each failure scenario
2. Use `@MockBean` with `willThrow()` for timeout simulation
3. Add chaos test using Testcontainers to inject network delays
4. Add concurrent recovery test with `ExecutorService` + `CountDownLatch`

---

## Cache Invalidation Risks

### Redis Daily Limit Cache — TTL and Consistency

**Issue:** Redis cache has implicit assumptions about time-based invalidation.

**Files:** 
- `risk-management-service/src/main/java/com/example/riskmanagement/infrastructure/cache/DailyLimitCache.java` — Cache TTL set to "KST midnight"
- Consumed by `ValidateAndReserveService.resolveDailyLimit()` line 75

**Risk scenarios:**
1. Redis instance crashes at 23:59 KST, server time drifts forward during restart → cache key expires but DB row doesn't, limits misaligned
2. Merchant limit updated at 23:58 KST but Redis entry already cached → update invisible until midnight
3. Daylight saving time transition (if applicable): KST doesn't shift, but `LocalDate.now()` boundary may cause duplicate/missing cache entries

**Mitigation in place:** 
Three-tier lookup (Redis → DB snapshot → HTTP) allows fallback if cache misses, so inconsistency doesn't cause hard failures.

**Fix approach:** 
1. Add test: "cache entry expires at KST midnight, not UTC midnight"
2. Add logging when cache miss forces DB snapshot/HTTP call
3. Document in `docs/kafka-design.md` that `merchant.limit.updated` Kafka event should clear Redis entries immediately (event-driven invalidation)

---

## Security Considerations

### Error Messages Leak Payment Details

**Issue:** Some error responses may expose payment/merchant information to callers.

**Files:** 
- `docs/error-catalog.md` line 84 — `INTERNAL_ERROR` correctly suppresses message
- But custom exceptions may not follow same pattern

**Specific cases:**
- `RiskServiceException` line 51-52 includes HTTP response code in message
- `PaymentNotFoundException` may expose payment key existence

**Current mitigation:** 
Controllers use `GlobalExceptionHandler` to map exceptions to generic error codes.

**Fix approach:** 
1. Audit all `throw new *Exception()` statements for information leakage
2. Add integration test: "error response contains no payment_key, no merchant_id, no amounts"
3. Update CLAUDE.md with rule: "All custom exceptions must not include domain identifiers in message"

---

## Performance Bottlenecks

### Hot Merchant Cancellations — Lock Contention

**Issue:** Multiple concurrent cancel requests for same merchant hit database lock on `merchant_cancel_usage` row.

**Files:** 
- `risk-management-service/src/main/java/com/example/riskmanagement/application/service/ValidateAndReserveService.java:20-28` — Comments note this was designed to address lock contention
- `risk-management-service/src/main/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageAtomicDeductIT.java` — Atomic deduct test

**Current design:** 
Uses atomic DB UPDATE instead of row-level locks: `tryDeduct()` performs `UPDATE merchant_cancel_usage SET used_amount = used_amount + ? WHERE merchant_id = ? AND kst_date = ? AND used_amount + ? <= daily_limit` — single SQL statement, microsecond-level lock.

**Ceiling:** 
Per-merchant throughput capped at 1,000s TPS when hot merchants all cancel simultaneously. Fixed at `daily_limit` window level (cannot scale to 10,000 TPS with single-row lock at any throughput).

**Scaling path:** 
1. Partition merchant_cancel_usage by merchant ID range (sharding)
2. Or move to eventual consistency model with compensation-based correction
3. Or implement per-merchant circuit breaker that fails fast when limit exhausted

**Fix approach:** 
1. Document in `sysdesign/cancel-design.md` section "Scaling beyond 1000s TPS": "Hot merchant lock contention is architectural ceiling; sharding required"
2. Add load test scenario: 100 concurrent cancels for same merchant_id, measure TPS ceiling
3. File follow-up task when TPS demand exceeds ceiling

---

## Dependencies at Risk

### Spring Boot 4.0.5 — Recent Major Version

**Issue:** Project uses Spring Boot 4.0.5 (released May 2025), a relatively new major version.

**Files:** 
- `build.gradle` line 3: `'org.springframework.boot' version '4.0.5'`

**Risks:**
- SB 4.x is relatively recent; critical bugs may emerge in first 6-12 months
- Spring Data JPA QueryDSL integration may have compatibility issues
- Some libraries may not yet support SB 4.x

**Current exposure:** 
None observed in codebase as of 2026-07-28, but version is only 12 months old.

**Fix approach:** 
1. Monitor Spring Boot security advisories monthly
2. Have upgrade plan for critical CVEs (e.g., SB 4.1.x if released)
3. Test regularly against Spring Boot 4.1.0 snapshots for forward compatibility

---

## Missing Critical Features

### Idempotency for Kafka Consumer Recovery

**Issue:** order-service consumer for `payment.cancelled` events has no retry-specific idempotency mechanism.

**Files:** 
- `order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledConsumer.java` 
- Uses at-least-once delivery (can receive same event multiple times)

**Assumption:** 
Business logic is idempotent (receiving same cancel event twice = same result). But not all consumers may be idempotent by design.

**Fix approach:** 
1. Add `processed_cancel_event` table to order-service DB: `(cancel_request_id, kafka_offset, processed_at) UK(cancel_request_id)`
2. Check table before processing: if event already processed, skip
3. Test: send same event twice via Kafka manual replay, verify idempotency
4. Document in `docs/kafka-design.md` section "Consumer Idempotency"

---

## Documentation Gaps

### Architecture Documentation Out of Sync

**Issue:** Some architecture decisions are documented in comments but not in formal docs.

**Files:** 
- `ValidateAndReserveService.java:20-28` — TX-outside-HTTP design well-commented
- But `docs/architecture.md` doesn't explicitly call out this constraint

**Impact:** 
Future developers may refactor without understanding why HTTP is outside TX.

**Fix approach:** 
1. Create `docs/conventions/architecture.md` section: "HTTP Calls Must Be Outside Transactions"
2. Add examples of correct/incorrect patterns
3. Reference in CLAUDE.md with "read this before refactoring risk-management-service"

---

*Concerns audit: 2026-07-28*
