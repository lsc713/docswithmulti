# Coding Conventions

**Analysis Date:** 2026-07-28

## Naming Patterns

**Files:**
- Service: `{Domain}Service.java` - UseCase implementation
- Port: `{Domain}Port.java` - External system interface
- Policy: `{Domain}Policy.java` - Business rule encapsulation
- Repository: `{Domain}Repository.java` - Data access interface
- DTO: `{Domain}Request.java` / `{Domain}Response.java` - API contracts
- Command: `{Domain}Command.java` - UseCase input (record)
- Exception: `{Domain}Exception.java` - Custom exceptions
- Fixture: `{Domain}Fixture.java` - Test data factories

**Functions:**
- UseCase entry: `execute()`, `cancel()`, `confirm()`
- Query: `find()`, `get()`, `load()` (with Optional or throw)
- Persistence: `save()`, `store()`, `update()`
- Validation: `validate()`, `verify()`, `check()`
- Conversion: `toCommand()`, `toResponse()`, `toDomain()`
- Publishing: `publish()`, `dispatch()`
- State transition: `toProcessing()`, `toCompleted()` (on entities)

**Variables:**
- camelCase for all local variables and fields
- `sut` for System Under Test in unit tests
- Descriptive names: `cancelAmount`, `requestHash`, `paymentId` (not `amt`, `hash`, `id`)
- Boolean prefix: `isActive`, `canCancel`, `isCancellable`

**Types (Classes):**
- Entity: `Payment`, `CancelRequest` (no suffix)
- Value Object: `Money`, `CancelAmount` (no suffix)
- Enum: `CancelStatus`, `PaymentStatus`
- Exception: `PaymentNotFoundException`, `CancelPeriodExceededException`
- Interface (UseCase): `CancelPaymentUseCase`
- Interface (Port): `RiskManagementPort`, `PgCancelPort`
- Implementation: `CancelPaymentService`, `MerchantLimitHttpClient`
- Config: `PersistenceConfig`, `KafkaConfig`

## Code Style

**Formatting:**
- Java 21 with records for immutable DTOs and commands
- No explicit formatter configured; follow Effective Java conventions
- 4-space indentation (standard Java)
- Line breaks: one statement per line, align conditions with early return

**Linting:**
- No explicit linter (ESLint/Checkstyle); rely on IDE defaults
- Build enforces 80% code coverage (JaCoCo) at `payment-service` level
- ArchUnit validates layer dependencies (in `build.gradle`)

**Import Organization:**
```
1. java.* (standard library)
2. javax.* (Jakarta/XML)
3. Third-party (spring, lombok, etc.)
4. Local imports (com.example.*)
5. Static imports (last, if any)
```

Example order from `CancelPaymentService`:
```java
import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.*;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.CancelDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
```

## Error Handling

**Patterns:**
- Exceptions are checked at entry points only (presentation controller)
- Domain layer throws domain exceptions for rule violations
- Application layer throws application exceptions for missing resources
- Infrastructure layer throws infrastructure exceptions for external failures

**Example hierarchy:**
```
BusinessException (common/exception)
├── InvalidCancelAmountException (domain/exception)
├── CancelPeriodExceededException (domain/exception)
├── PaymentNotFoundException (application/exception)
├── IdempotentDuplicationException (application/exception)
└── RiskServiceException (infrastructure/exception)
```

**Throw pattern — early validation:**
```java
// File: CancelPaymentService
public CancelRequest cancel(CancelPaymentCommand command) {
    try {
        Payment payment = paymentRepository.findByPaymentKey(command.paymentKey())
            .orElseThrow(() -> new PaymentNotFoundException(command.paymentKey()));
        
        List<PaymentItem> items = paymentItemRepository.findAllByPaymentIdOrderByIdAsc(payment.getId());
        
        payment.validateCancellable();  // Throws InvalidPaymentStatusException
        validateTargetItemsActive(items, command.cancelPaymentItemIds());
        // ... continue only if valid
    } finally {
        cancelHistoryRecorder.flush();
    }
}
```

**Exception wrapping in ports:**
```java
// File: MerchantLimitHttpClient
public MerchantCancelLimit fetchDailyLimit(Long merchantId, LocalDate date) {
    try {
        return restTemplate.getForObject(...);
    } catch (RestClientException e) {
        throw new MerchantLimitServiceException(merchantId, date, e);
    }
}
```

## Logging

**Framework:** SLF4J (via Lombok `@Slf4j`)

**Patterns:**
- Inject via `@Slf4j` on class
- Log at ENTRY/DECISION points: `log.info()`, `log.debug()` 
- Exception context: `log.error("Failed to X", e)`
- Structured: Include IDs for tracing: `log.info("PaymentId={}, Status={}", payment.getId(), status)`
- No logging in domain layer

**Example:**
```java
@Slf4j
@Service
public class CancelPaymentService implements CancelPaymentUseCase {
    @Override
    public CancelRequest cancel(CancelPaymentCommand command) {
        try {
            log.debug("Cancelling payment: paymentKey={}", command.paymentKey());
            Payment payment = paymentRepository.findByPaymentKey(command.paymentKey())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentKey()));
            // ...
        } catch (RiskServiceException e) {
            log.error("Risk check failed for merchantId={}", payment.getMerchantId(), e);
            throw e;
        } finally {
            cancelHistoryRecorder.flush();
        }
    }
}
```

## Comments

**When to Comment:**
- Complex algorithms or non-obvious business logic
- State machine transitions: document each state and valid paths
- Intent behind workarounds or temporary solutions
- Algorithm rationale (e.g., why retry with exponential backoff)

**JSDoc/JavaDoc:**
- Mandatory on all public classes and public methods
- Korean for clarity (business team readability)
- Include state transitions for entities
- Link to external docs when relevant (e.g., error-catalog.md)

**Example:**
```java
/**
 * CancelRequest 도메인 엔티티
 *
 * 상태 전이:
 * PENDING → PROCESSING → COMPLETED (또는 FAILED)
 * FAILED → PENDING (raiseToPending — 재시도)
 * COMPLETED, FAILED는 최종 상태 (단, FAILED는 PENDING 재진입 가능)
 *
 * 멱등성: (payment_id, request_hash) UK로 중복 방어
 * request_hash = SHA-256(paymentKey + paymentItemIds 오름차순 정렬)
 */
public class CancelRequest {
    
    /** PENDING → PROCESSING */
    public void toProcessing() {
        if (status != CancelStatus.PENDING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PROCESSING);
        }
        this.status = CancelStatus.PROCESSING;
        this.pgPendingSince = Instant.now();
    }
}
```

## Function Design

**Size:**
- Target 10 lines max for business logic
- Exceptions: setup code, data mapping, framework boilerplate may be longer
- Extract helpers for repeated validation

**Parameters:**
- Avoid more than 3 parameters; use Command/DTO if more needed
- Use records for immutable command objects (Java 21)

**Return Values:**
- Return Optional for optional lookups: `Optional<Payment>`
- Return void for imperative updates with side effects: `void updateStatus()`
- Return entities/DTOs for created/fetched resources
- Throw exceptions instead of null for missing required data

**Example pattern:**
```java
// Command record (immutable input)
public record CancelPaymentCommand(String paymentKey, String cancelReason, List<Long> cancelPaymentItemIds) {}

// UseCase entry (returns result or throws)
@Override
public CancelRequest cancel(CancelPaymentCommand command) { ... }

// Query (returns Optional)
public Optional<Payment> findByPaymentKey(String key) { ... }

// Mutation (void with side effects)
public void updateStatus(Long id, PaymentStatus status) { ... }
```

## Module Design

**Exports (Public API):**
- Export interfaces, not implementations from application layer
- Domain entities public for infrastructure reconstruction
- Exceptions public and documented in error-catalog.md

**Barrel Files:**
- Package-level exports in `package-info.java` if needed
- No wildcard imports in source; list explicit imports for clarity

**Example module boundary:**
```
payment-service/
├── application/
│   ├── usecase/              # Public: CancelPaymentUseCase interface
│   ├── interfaces/           # Public: RiskManagementPort, PgCancelPort
│   ├── service/              # Private: CancelPaymentService impl
│   ├── dto/                  # Public: PgCancelResult
│   └── exception/            # Public: PaymentNotFoundException
├── infrastructure/           # Private: all adapters
└── domain/                   # Public: entities, policies, exceptions
```

**Pattern: Static Factory Methods**

All entities use static factory methods, never direct `new`:

```java
// ✓ Good — from PaymentFixture
public static Payment completedPayment() {
    return Payment.of(
        "pay_test_001", 1L, 1L, "TOSS",
        BigDecimal.valueOf(100000), "KRW", 90,
        LocalDateTime.of(2026, 1, 1, 0, 0, 0)
    );
}

// ✓ Good — from CancelRequest domain entity
public static CancelRequest create(Long paymentId, String requestHash, BigDecimal cancelAmount, ...) {
    return new CancelRequest(paymentId, requestHash, cancelAmount, ...);
}

// Infrastructure reconstruction (package-private constructor)
public static CancelRequest reconstruct(Long id, Long paymentId, String requestHash, ...) {
    CancelRequest r = new CancelRequest(...);
    r.id = id;  // Set DB-generated ID
    return r;
}
```

**Pattern: Immutability**

Value objects are final and immutable:

```java
public final class Money {
    private final BigDecimal amount;
    private final String currency;
    
    private Money(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }
    
    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }
    
    // Operations return new instances
    public Money add(Money other) {
        return new Money(this.amount.add(other.amount), this.currency);
    }
}
```

**Pattern: Early Return**

Avoid nested conditionals; use early validation:

```java
// ✗ Bad — nested conditions
public void cancel(CancelCommand command) {
    if (payment != null) {
        if (payment.isCancellable()) {
            if (limit.isAvailable(command.amount())) {
                // process
            }
        }
    }
}

// ✓ Good — early returns
public void cancel(CancelCommand command) {
    Payment payment = validatePaymentExists();
    validateCancellable(payment);
    validateLimitAvailable(limit, command.amount());
    // process
}
```

## Architecture Layers (Intra-Module)

See `docs/conventions/architecture.md` for full layer structure. Quick reference:

```
presentation → application → domain
infrastructure → domain

Dependencies must flow inward only.
```

**Domain Layer (Pure Java):**
- Entities, Value Objects, Domain Services, Policies
- No Spring, no JPA annotations
- File: `src/main/java/com/example/{module}/domain/{entity,service,policy,exception}`

**Application Layer (UseCase Orchestration):**
- UseCase interfaces, Service implementations, Ports (external contracts)
- No JPA entity mapping; DTOs only
- File: `src/main/java/com/example/{module}/application/{usecase,service,interfaces,dto,exception}`

**Infrastructure Layer (Adapters):**
- JPA, HTTP, Kafka, Redis implementations
- File: `src/main/java/com/example/{module}/infrastructure/{persistence,http,messaging,cache,config}`

**Presentation Layer (API Boundary):**
- Controllers, Request/Response DTOs, validation
- File: `src/main/java/com/example/{module}/presentation/{controller,dto}`

---

*Convention analysis: 2026-07-28*
