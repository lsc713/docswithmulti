<!-- refreshed: 2026-07-28 -->
# Architecture

**Analysis Date:** 2026-07-28

## System Overview

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                         REST API Clients                                     │
└──────────────────────────┬──────────────────────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  payment-service │  │   order-service  │  │ merchant-limit   │
│  (port 8080)     │  │  (port 8081)     │  │   -service       │
│  `payment-*/src` │  │  `order-*/src`   │  │  (port 8082)     │
└────────┬─────────┘  └────────┬─────────┘  │  `merchant-*/src`│
         │                     │            └──────────────────┘
         │ HTTP sync           │ Kafka async
         │ (cancel flow)       │ (event listener)
         │                     │
    ┌────▼──────────────┐      │
    │ risk-management   │      │
    │   -service        │      │
    │   (port 8083)     │      │
    │`risk-*/src`       │      │
    └────┬──────────────┘      │
         │ HTTP sync           │
         │ (limit check)       │
         │                     │
    ┌────▼──────────────┐      │
    │ merchant-limit    │      │
    │   -service        │      │
    │ (port 8082)       │      │
    │`merchant-*/src`   │      │
    └────┬──────────────┘      │
         │                     │
         └──────────┬──────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  Kafka Broker        │
         │  (3 brokers)         │
         │ `infra/docker-       │
         │  compose.yml`        │
         └──────────────────────┘
                    │
    ┌───────────────┼────────────────┐
    │               │                │
    ▼               ▼                ▼
payment_service  order_service  risk_service
(MySQL 8.0)      (MySQL 8.0)    (MySQL 8.0)
merchant_service  (independent  (independent
(MySQL 8.0)       DB)           DB)

External:
  ├── Redis (ElastiCache Multi-AZ) — distributed locks, daily_limit cache
  ├── PG사 HTTP API — payment cancellation
  └── CircuitBreaker (Resilience4j) — fault tolerance
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| **payment-service** | Core cancel request orchestration, TX1/TX2/TX3, scheduler recovery | `payment-service/src/main/java/com/example/payment` |
| **risk-management-service** | Merchant limit validation, used_amount reservation, compensation | `risk-management-service/src/main/java/com/example/riskmanagement` |
| **order-service** | OrderItem state sync on cancel completion via Kafka | `order-service/src/main/java/com/example/order` |
| **merchant-limit-service** | Daily cancel limit master data, limit change event publishing | `merchant-limit-service/src/main/java/com/example/merchantlimit` |
| **product-service** | Product/SKU/inventory management (not yet implemented) | `product-service/src/main/java/com/example/product` |
| **common-observability** | Query count observation, OpenTelemetry config | `common-observability/src/main/java/com/example/common/observability` |

## Pattern Overview

**Overall:** Clean Architecture (Hexagonal) + Outbox/AFTER_COMMIT variants

**Key Characteristics:**
- **Unidirectional dependency flow**: presentation → application → domain ← infrastructure
- **Layered isolation**: domain logic never touches Spring/JPA; testable without framework
- **Transactional boundaries**: TX1 (PENDING), TX2 (PROCESSING), TX3 (COMPLETED) — each separate DB transaction for recovery resilience
- **Idempotent request hashing**: Server-generated `request_hash = SHA-256(paymentKey + sorted paymentItemIds)` replaces UUID-based Idempotency-Key
- **Distributed lock strategy**: Redis-based (no ShedLock; shedlock table removed)
- **Messaging variants**: Main branch uses TX3 inline Kafka publish; variants/outbox and variants/aftercommit branches explore alternatives

## Layers

**Presentation Layer:**
- Purpose: REST API entry points, input validation, response formatting
- Location: `*/src/main/java/com/example/{module}/presentation/controller`, `presentation/dto`
- Contains: `@RestController`, request/response DTOs, API contract definitions
- Depends on: application layer only
- Used by: HTTP clients (API consumers)

**Application Layer:**
- Purpose: Orchestrate use cases, coordinate domain services, manage transactions
- Location: `*/src/main/java/com/example/{module}/application/service`, `application/usecase`, `application/interfaces`
- Contains: Use case services, repository interfaces (abstractions), external system contracts
- Depends on: domain layer (logically) and infrastructure layer (via interfaces)
- Used by: presentation and infrastructure (circular dependency broken via interfaces)

**Domain Layer:**
- Purpose: Business rules, state machines, invariants — framework-free pure logic
- Location: `*/src/main/java/com/example/{module}/domain/entity`, `domain/service`, `domain/policy`, `domain/exception`
- Contains: Entities, value objects, domain services, policies, domain-specific exceptions
- Depends on: nothing (no external dependencies)
- Used by: application and infrastructure layers

**Infrastructure Layer:**
- Purpose: External system adaptations (database, Kafka, HTTP, cache)
- Location: `*/src/main/java/com/example/{module}/infrastructure/persistence`, `infrastructure/messaging`, `infrastructure/http`, `infrastructure/config`, `infrastructure/adapter`
- Contains: JPA repositories (implementations), Kafka producers/consumers, HTTP clients, Spring bean configs
- Depends on: domain and application layers (via interfaces)
- Used by: application layer (inversion of control)

**Common Layer:**
- Purpose: Shared exception hierarchy and constants
- Location: `*/src/main/java/com/example/{module}/common/exception`
- Contains: `BusinessException` (parent of all custom exceptions), error code constants
- Depends on: nothing
- Used by: all layers (imports only)

## Data Flow

### Primary Request Path: Payment Cancellation

1. **Client Request** → `POST /v1/payments/{paymentKey}/cancel` (`payment-service/presentation/controller/PaymentCancelController`)
2. **Pre-TX Validation** (`payment-service/application/service/CancelPaymentService`):
   - Load Payment entity + PaymentItem list (ORDER BY id ASC)
   - Generate `request_hash = SHA-256(paymentKey + sorted itemIds)`
   - Check idempotency: existing CancelRequest with same hash?
     - COMPLETED → return cached response (200)
     - PENDING/PROCESSING → return in-flight response
     - FAILED → UPDATE to PENDING + insert history (retry)
   - Validate Payment.isActive() (COMPLETED | PARTIAL_CANCELLED)
   - Validate cancel period not expired: `payment.createdAt + cancelPeriodDays >= today`
   - Validate all requested PaymentItems are ACTIVE (not already cancelled)

3. **TX1: CancelRequest PENDING INSERT** (`payment-service/infrastructure/persistence/CancelRequestRepositoryImpl`)
   - Insert: `CancelRequest(payment_id, request_hash, status=PENDING, cancel_amount, canceller_type, cancelled_by)`
   - UK constraint on `(payment_id, request_hash)` blocks duplicate inserts at DB level
   - Post-TX (separate): Insert to `cancel_request_history(status=PENDING)` for audit trail

4. **HTTP: risk-management-service Call** (`payment-service/infrastructure/http/RiskManagementRestClient`):
   - POST `/internal/cancel-limit/validate-and-reserve` with `merchantId, cancelRequestId, cancelAmount`
   - Risk-service flow:
     a. Check Redis cache: `daily_limit:{merchantId}:{kstDate}`
     b. If Redis miss → query `merchant_cancel_usage.daily_limit` (DB snapshot)
     c. If DB snapshot missing → HTTP to merchant-limit-service for fresh daily_limit
     d. FOR UPDATE on `merchant_cancel_usage` (pessimistic lock for accuracy over throughput)
     e. Validate: `used_amount + cancelAmount ≤ dailyLimit`
     f. Pre-deduct: `used_amount += cancelAmount`
     g. Insert `cancel_usage_history(cancelRequestId)` for idempotent replay (UK prevents double-deduction)
   - On failure (timeout, CB OPEN): payment-service attempts compensate API → or inserts `compensation_retry` for scheduler

5. **TX2: CancelRequest PROCESSING UPDATE** (`payment-service/infrastructure/persistence/CancelRequestRepositoryImpl`):
   - Update: `CancelRequest(status=PROCESSING)` where id = ?
   - Post-TX (separate): Insert to `cancel_request_history(status=PROCESSING)`
   - TX2 failure → PENDING retained → pending-recovery scheduler will detect in 5+ minutes

6. **PG사 HTTP Call** (`payment-service/infrastructure/http/PgServiceRestClient`):
   - POST `https://pg.example.com/api/cancel` with paymentKey
   - On timeout → CancelRequest stays PROCESSING → processing-recovery scheduler handles
   - On explicit failure → attempt compensate API (restore used_amount)

7. **TX3: Complete Cancel (PaymentItem + Payment + CancelRequest + Outbox)** (`payment-service/infrastructure/persistence/CancelTxWriter`):
   - FOR UPDATE re-fetch PaymentItems (pessimistic lock to catch concurrent cancels of other items)
   - Mark cancellation targets: `PaymentItem.status = CANCELLED`
   - Recalculate Payment state based on all items:
     - All CANCELLED → `Payment.status = CANCELLED`
     - Some CANCELLED → `Payment.status = PARTIAL_CANCELLED`
   - Update: `CancelRequest(status=COMPLETED)`
   - Insert: `cancel_event_outbox(cancelRequestId, status=PENDING)` OR publish `ApplicationEvent` for AFTER_COMMIT listener
   - Commit all atomically
   - Post-TX (separate): Insert to `cancel_request_history(status=COMPLETED)`

8. **Kafka Event Publishing**:
   - **Branch main** (`TX3 inline publish`): `kafkaTemplate.send()` called directly in TX3 writer
     - If failed → exception caught → insert `failed_kafka_event` → failed-kafka-publisher scheduler retries
   - **Branch variant/outbox**: cancel_event_outbox already in TX3 → outbox-publisher scheduler polls and publishes
   - **Branch variant/aftercommit**: `@TransactionalEventListener(AFTER_COMMIT)` fires after TX3 commits → publish then
   - Topic: `payment.cancelled` (partition key: `paymentKey` for order grouping)
   - Payload: `{ cancelRequestId, paymentKey, merchantId, cancelledItems[], cancelledAt }`

9. **Kafka Consumer: order-service** (`order-service/infrastructure/messaging/PaymentCancelledConsumer`):
   - Receive `payment.cancelled` event
   - Check idempotency: `SELECT processed_cancel_event WHERE cancelRequestId = ?` (UK)
   - If exists → no-op + ack (already processed)
   - If new → fetch OrderItems by mapping in event → update OrderItem.status = CANCELLED → INSERT `processed_cancel_event(cancelRequestId)` → ack

### Secondary Flows

**Merchant Limit Change (Kafka Event from merchant-limit-service):**
- merchant-limit-service: Limit change → `@TransactionalEventListener(AFTER_COMMIT)` publishes `merchant.limit.updated { merchantId }`
- risk-management-service Consumer: Receives → calls merchant-limit-service API for fresh daily_limit → updates Redis cache + `merchant_cancel_usage` row

**Scheduler Recovery (payment-service):**
- **pending-recovery** (60s interval, Redis lock): Find CancelRequest.PENDING older than 5 min → call risk `/internal/cancel-limit/check?cancelRequestId` → charged=true? → call compensate API (or insert compensation_retry) → FAILED
- **processing-recovery** (60s interval, Redis lock): Find CancelRequest.PROCESSING older than 5 min → GET PG사 status → success? → retry TX3 → else compensate or keep processing if stale
- **compensation-retry** (30s interval, Redis lock): Find compensation_retry with next_retry_at passed → call risk `/internal/cancel-limit/compensate` → success: DONE, else retry with exponential backoff
- **failed-kafka-publisher** (30s interval, Redis lock): Find failed_kafka_event with status=PENDING → retry Kafka send → PUBLISHED, else increment retry_count

**State Management:**

CancelRequest state machine:
```
PENDING  ──(risk success)──> PROCESSING ──(TX3 commit)──> COMPLETED
  ▲         (risk fail)  ↓  (PG fail)  ↓  (recovery)
  └─────────────────── FAILED ←────────────────────────┘
```

Payment state machine:
```
COMPLETED  ──(partial cancel)──> PARTIAL_CANCELLED ──(final cancel)──> CANCELLED
```

PaymentItem state machine:
```
ACTIVE ──(cancel)──> CANCELLED  (no reverse; never PARTIAL_CANCELLED individually, Payment tracks overall)
```

## Key Abstractions

**CancelRequest (Entity):**
- Purpose: Track each cancellation request through TX1/TX2/TX3 lifecycle
- Examples: `payment-service/domain/entity/CancelRequest`, `infrastructure/persistence/CancelRequestJpaEntity`
- Pattern: Domain entity (pure logic) + JPA entity (DB mapping) separate; repository interface in application, impl in infrastructure

**Payment (Entity):**
- Purpose: Aggregation root for payment record + cancellation state
- Invariant: `status` reflects union of all PaymentItem states
- Method: `isActive()` encodes business rule: COMPLETED | PARTIAL_CANCELLED = cancellable; CANCELLED = not cancellable

**PaymentItem (Entity):**
- Purpose: Individual item within payment (1-N to Payment)
- Lifecycle: ACTIVE → CANCELLED (no intermediate states)

**MerchantCancelUsage (Entity):**
- Purpose: Track merchant's daily cancel consumption against limit
- Location: `risk-management-service/domain/entity/MerchantCancelUsage`
- Concurrency: FOR UPDATE in TX to ensure accurate deduction; cancel_usage_history UK for replay protection

**Repository Interfaces (Application):**
- Location: `*/application/interfaces/*Repository`
- Pattern: Spring Data JPA–inspired contracts; implementations in infrastructure
- Example: `PaymentRepository`, `CancelRequestRepository`, `MerchantCancelUsageRepository`
  - Methods: `findById()`, `findAllByPaymentIdForUpdate()`, `save()`, etc.
  - Implementation: `PaymentRepositoryImpl extends CrudRepository` + custom `@Query` for FOR UPDATE

**Domain Services:**
- Location: `domain/service/*.java`
- Purpose: Orchestrate multiple entities; encode multi-step business rules
- Example: `CancelLimitDomainService` (risk-service) — validates limit, coordinates history recording

**Use Case Services (Application):**
- Location: `application/service/*.java`
- Purpose: Coordinate infrastructure calls (HTTP, Kafka, DB) around domain logic
- Example: `CancelPaymentService` orchestrates payment-service → risk-service → order-service flow
- Pattern: Service receives domain entities/DTOs, calls repositories + external clients, returns response DTOs

**External Clients (Infrastructure):**
- Location: `infrastructure/http/*RestClient`, `infrastructure/messaging/*`
- Purpose: Encapsulate external API contracts (REST, Kafka) behind interfaces
- Example: `RiskManagementRestClient` implements `MerchantLimitClient`; hides Feign/RestTemplate details

## Entry Points

**REST API (payment-service port 8080):**
- Location: `payment-service/src/main/java/com/example/payment/presentation/controller`
- Main endpoints:
  - `POST /v1/payments/{paymentKey}/cancel` → `PaymentCancelController.cancel()` → `CancelPaymentService.execute()`
  - Others: retrieve payment, payment items (fetch for UI)

**REST API (order-service port 8081):**
- `POST /internal/orders/{orderId}/sync-cancel` → sync OrderItem states after Kafka loss or replay

**REST API (risk-management-service port 8083):**
- Internal only (payment-service client):
  - `POST /internal/cancel-limit/validate-and-reserve` → `ValidateAndReserveUseCase`
  - `POST /internal/cancel-limit/compensate` → `CompensateUseCase`
  - `GET /internal/cancel-limit/check?cancelRequestId=...` → `CheckChargeUseCase`

**Kafka Consumer (payment-service):**
- Topic: `merchant.limit.updated` → updates Redis cache on merchant limit change

**Kafka Consumer (order-service):**
- Topic: `payment.cancelled` → syncs OrderItem states

**Kafka Producer (payment-service):**
- Topic: `payment.cancelled` (outbound) — publishes cancel completion
- Topic: `payment.cancelled.retry` (via error handler) — failed message retry

**Kafka Producer (merchant-limit-service):**
- Topic: `merchant.limit.updated` (outbound) — publishes limit changes

**Schedulers (payment-service, Redis-locked):**
- `@Scheduled(fixedDelay=60s)` pending-recovery
- `@Scheduled(fixedDelay=60s)` processing-recovery
- `@Scheduled(fixedDelay=30s)` compensation-retry
- `@Scheduled(fixedDelay=30s)` failed-kafka-publisher

## Architectural Constraints

- **Threading:** Single-threaded event loop per service instance; Kafka partitions handle parallelism; FOR UPDATE serializes high-concurrency paths
- **Global state:** Redis cache for daily_limit (per merchantId:kstDate key); no in-memory shared state across instances
- **Circular imports:** None by design; infrastructure adapts to application interfaces; application depends on domain only
- **Module isolation:** Each service has its own DB; inter-service communication via HTTP or Kafka only
- **Transaction isolation:** REPEATABLE_READ (MySQL default); FOR UPDATE for critical paths
- **Time zone:** All DB timestamps in UTC; KST calculated client-side or server with `ZoneId.of("Asia/Seoul")`
- **Idempotency:** Three levels:
  1. request_hash UK at INSERT (prevents duplicate CancelRequest rows)
  2. cancelRequestId UK in history tables (prevents double-deduction or double-compensation)
  3. Consumer UK in Kafka listeners (prevents duplicate OrderItem updates)

## Anti-Patterns

### Anti-Pattern: Calling merchant-limit directly after Redis miss without DB snapshot

**What happens:** Code skips `merchant_cancel_usage.daily_limit` check and immediately calls `MerchantLimitRestClient.getDailyLimit()`

**Why it's wrong:** If Redis AND merchant-limit-service are both down, cancels fail unnecessarily. DB snapshot is a fallback that prevents this cascade.

**Do this instead:** 
- Tier 1: Redis (fast cache)
- Tier 2: `merchantCancelUsageRepository.findByMerchantIdAndDate()` (DB snapshot)
- Tier 3: `merchantLimitClient.getDailyLimit()` (only if snapshot missing)
See `risk-management-service/application/service/ValidateAndReserveService.java` for pattern.

### Anti-Pattern: Including history INSERT in the same TX as entity updates

**What happens:** `cancel_request_history` INSERT fails inside TX1/TX2/TX3 → entire transaction rolls back → state not recorded

**Why it's wrong:** History is audit trail; its failure should not rollback business-critical state changes.

**Do this instead:** Execute history INSERT **outside the transaction boundary**, after main TX commits. Pattern at `payment-service/infrastructure/persistence/CancelRequestRepositoryImpl` uses `@Transactional` on main INSERT, then separate non-TX history call.

### Anti-Pattern: Directly comparing Payment.status == COMPLETED in callers

**What happens:** New code forgets that PARTIAL_CANCELLED is also cancellable → fails for multi-item cancels

**Why it's wrong:** Scatters cancel-ability logic across codebase; hard to maintain when status enum changes.

**Do this instead:** Use domain method `payment.isActive()` defined once in domain entity. All callers invoke the method. See `payment-service/domain/entity/Payment.java`.

### Anti-Pattern: Storing fresh data in `cancel_event_outbox` from application layer parameters instead of re-querying in TX3

**What happens:** Application layer passes `List<PaymentItemDTO>` into TX3 → TX3 INSERT uses those, not latest DB state → concurrent cancels miss updates

**Why it's wrong:** Time passes between application layer assembly and TX3 execution; data ages, concurrent deletes happen, outbox event is stale.

**Do this instead:** TX3 re-fetches all entities FOR UPDATE, recalculates state, then constructs outbox row. See `CancelTxWriter.completeCancel()`.

## Error Handling

**Strategy:** Layer-specific exception hierarchy; common layer catches `BusinessException` and maps to HTTP status

**Patterns:**

Domain exceptions (domain layer):
```java
// Thrown when business rule violated
throw new InvalidPaymentStatusException("Payment is not cancellable", errorCode);
throw new CancelPeriodExpiredException(...);
```

Application exceptions (application layer):
```java
// Thrown when precondition missing, not a domain violation
throw new PaymentNotFoundException(...);
throw new IdempotentDuplicationException(...);
```

Infrastructure exceptions (infrastructure layer):
```java
// Wrapped from external call failures
catch (FeignException e) {
    throw new RiskManagementServiceException(e);
}
```

Controller-level error mapping (`presentation/GlobalExceptionHandler`):
```java
@ExceptionHandler(BusinessException.class)
public ResponseEntity<ErrorResponse> handle(BusinessException ex) {
    return ResponseEntity
        .status(ex.getHttpStatus())
        .body(new ErrorResponse(ex.getErrorCode(), ex.getMessage()));
}
```

See `docs/error-catalog.md` for all error codes and HTTP mappings.

## Cross-Cutting Concerns

**Logging:** SLF4J with MDC for request correlation; Grafana for live monitoring

**Validation:** `@NotNull`, `@NotBlank` in DTOs; domain entities validate in constructor (fail fast)

**Authentication:** None (internal services only; API gateway handles external auth in production)

**Metrics:** Micrometer + OpenTelemetry exporter; key metrics at `docs/architecture.md` § Monitoring Metrics

**Query Observability:** Common-observability module (`common-observability/src`) injects query counter filter; readable via actuator `/actuator/querycount`

---

*Architecture analysis: 2026-07-28*
