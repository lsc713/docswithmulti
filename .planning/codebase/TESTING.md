# Testing Patterns

**Analysis Date:** 2026-07-28

## Test Framework

**Runner:**
- JUnit 5 (Jupiter)
- Config: `build.gradle` — `useJUnitPlatform()` in all modules
- Version: bundled with Spring Boot 4.0.5

**Assertion Library:**
- AssertJ for fluent assertions
- `org.junit.jupiter.api.Assertions` for edge cases

**Mocking:**
- Mockito 4.x (via Spring Boot starter-test)
- `@ExtendWith(MockitoExtension.class)` for unit tests
- `@MockitoBean` for Spring integration tests

**Integration Testing:**
- Testcontainers 1.19.7 for MySQL (unit tests use H2 or mock)
- Spring Boot Test (`@SpringBootTest`)
- `@DynamicPropertySource` for runtime property overrides

**Run Commands:**
```bash
./gradlew build                          # Compile + all tests
./gradlew :payment-service:test          # Module unit tests only
./gradlew test                           # All tests (root runs subproject tests)
./gradlew :payment-service:jacocoTestReport  # Coverage report (HTML to build/reports/jacoco/html/)
```

## Test File Organization

**Location:**
- Mirror source structure: `src/test/java/com/example/{module}/{layer}/{package}/`
- Unit tests co-located with fixtures: both in `src/test/java/`
- Integration tests grouped separately in module

**Naming:**
- Unit test: `{Class}Test.java` (extends business logic class)
- Integration test: `{Scenario}IntegrationTest.java`
- Fixture: `{Domain}Fixture.java` (test data factory)

**Structure:**
```
payment-service/src/test/java/com/example/payment/
├── application/service/
│   ├── CancelPaymentServiceTest.java
│   ├── PendingRecoveryServiceTest.java
│   └── CancelTxWriterTest.java
├── domain/
│   └── service/CancelDomainServiceTest.java
├── infrastructure/persistence/
│   ├── CancelRequestJpaRepositoryTest.java
│   └── AbstractRepositoryTest.java
├── integration/
│   └── CancelFlowIntegrationTest.java
└── fixture/
    ├── PaymentFixture.java
    ├── PaymentItemFixture.java
    └── CancelRequestFixture.java
```

## Test Structure

**Unit Test Pattern:**
```java
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelPaymentService")
class CancelPaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock RiskManagementPort riskManagementPort;
    // ... more @Mock fields

    private CancelPaymentService sut;  // System Under Test

    @BeforeEach
    void setUp() {
        // Arrange: Initialize real dependencies and mocks
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        CancelDomainService domainService = new CancelDomainService(new CancelPeriodPolicy(clock));
        
        sut = new CancelPaymentService(
            paymentRepository, paymentItemRepository, cancelRequestRepository,
            cancelHistoryRecorder, compensationRetryRepository,
            riskManagementPort, pgCancelPort, domainService, cancelTxWriter
        );
    }

    @Test
    @DisplayName("should throw payment not found when payment missing")
    void shouldThrowPaymentNotFoundWhenPaymentMissing() {
        // Arrange
        when(paymentRepository.findByPaymentKey("pay_test_001"))
            .thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(PaymentNotFoundException.class, 
            () -> sut.cancel(command));
    }

    @Test
    @DisplayName("정상 취소 — risk·PG 모두 성공 시 COMPLETED 반환")
    void shouldCompleteCancelSuccessfully() {
        // Arrange
        Payment payment = PaymentFixture.completedPayment();
        when(paymentRepository.findByPaymentKey("pay_test_001"))
            .thenReturn(Optional.of(payment));
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(...));

        // Act
        CancelRequest result = sut.cancel(command);

        // Assert
        assertEquals(CancelStatus.COMPLETED, result.getStatus());
        verify(riskManagementPort).validateAndReserve(eq(payment.getMerchantId()), anyLong(), any(), any());
    }
}
```

**Integration Test Pattern (Testcontainers):**
```java
@Testcontainers
@SpringBootTest
@DisplayName("CancelFlow 통합 테스트")
class CancelFlowIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // Mock external systems (HTTP, Kafka, Redis)
    @MockitoBean RiskManagementPort riskManagementPort;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;

    // Inject actual repositories (direct DB access)
    @Autowired PaymentJpaRepository paymentJpaRepository;
    @Autowired CancelRequestJpaRepository cancelRequestJpaRepository;

    // Inject service under test
    @Autowired CancelPaymentService cancelPaymentService;

    @BeforeEach
    void insertTestData() {
        // Create real test data in MySQL
        Payment payment = paymentJpaRepository.save(PaymentFixture.completedPayment());
        // ...
    }

    @Test
    @DisplayName("멱등성 — 재시도는 새 INSERT 없이 기존 건 반환")
    void shouldReturnExistingWhenRetrying() {
        // Arrange: Setup external mocks and DB state
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));

        // Act: Call service
        CancelRequest result1 = cancelPaymentService.cancel(command);
        CancelRequest result2 = cancelPaymentService.cancel(command);  // Retry

        // Assert: Both return same state, only 1 DB row created
        assertThat(result1.getId()).isEqualTo(result2.getId());
        assertThat(cancelRequestJpaRepository.findAll()).hasSize(1);
    }
}
```

**Repository Test (AbstractRepositoryTest Pattern):**
```java
/**
 * 통합 테스트 공통 베이스.
 *
 * MySQL 컨테이너는 JVM당 1개 싱글톤으로 띄운다(정적 블록에서 1회 start).
 * @Testcontainers + @Container 는 클래스별 start/stop 하므로 multi-IT 클래스에서
 * 캐시된 Spring 컨텍스트가 죽은 컨테이너를 가리킨다. 싱글톤은 stop 하지 않고
 * Ryuk 이 JVM 종료 시 정리한다.
 */
@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
public abstract class AbstractRepositoryTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_test")
        .withUsername("test")
        .withPassword("test");

    static {
        MYSQL.start();  // 최초 로드 시 1회만
    }

    @DynamicPropertySource
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }
}

// Usage:
class CancelRequestJpaRepositoryTest extends AbstractRepositoryTest {
    @Autowired CancelRequestJpaRepository repository;

    @Test
    void shouldFindByPaymentIdAndRequestHash() {
        // ...
    }
}
```

## Mocking

**Framework:** Mockito

**Patterns:**

**1. Mock Setup (BeforeEach):**
```java
@BeforeEach
void setUp() {
    when(paymentRepository.findByPaymentKey("pay_001"))
        .thenReturn(Optional.of(payment));
    
    when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
        .thenReturn(new RiskReserveResult(...));
}
```

**2. Verify Behavior:**
```java
// Verify method called with exact args
verify(riskManagementPort).validateAndReserve(
    eq(payment.getMerchantId()),
    anyLong(),
    eq(BigDecimal.valueOf(30_000)),
    any());

// Verify NOT called
verify(riskManagementPort, never()).validateAndReserve(anyLong(), anyLong(), any(), any());

// Verify call order
InOrder inOrder = inOrder(cancelTxWriter);
inOrder.verify(cancelTxWriter).saveTx1(any());
inOrder.verify(cancelTxWriter).saveTx2(any());
inOrder.verify(cancelTxWriter).saveTx3(any(), any(), any());
```

**3. Answer/Stub for Complex Behavior:**
```java
when(cancelTxWriter.saveTx2(any())).thenAnswer(inv -> {
    CancelRequest cr = inv.getArgument(0);
    cr.toProcessing();  // Side effect
    return cr;
});

// Or throw exception
when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
    .thenThrow(new RiskServiceException("Limit exceeded"));
```

**4. TransactionTemplate Stub (for TX tests without real TX):**
```java
TransactionTemplate txTemplate = new TransactionTemplate() {
    @Override
    public <T> T execute(TransactionCallback<T> action) {
        return action.doInTransaction(null);  // Inline execution, no real TX
    }
};
sut = new ValidateAndReserveService(..., txTemplate, ...);
```

**What to Mock:**
- External HTTP/Kafka ports (I/O)
- Database repositories (persistence)
- Infrastructure adapters (Redis, event publishers)
- Clock (for time-dependent tests)

**What NOT to Mock:**
- Domain entities and value objects (test behavior)
- Domain services (test domain logic)
- Policy objects (test business rules)
- Static factory methods (use real Fixtures instead)

## Fixtures and Factories

**Test Data Pattern:**
```java
/**
 * Payment 도메인 엔티티 테스트 픽스처
 *
 * 테스트에서 공통으로 사용되는 Payment 객체를 생성한다.
 * Payment의 정적 팩토리 메서드를 활용한다.
 */
public class PaymentFixture {

    /**
     * 기본 완료 결제 (COMPLETED 상태)
     * - 결제일: 2026-01-01 00:00:00 UTC
     * - 총액: 100,000원
     * - 취소 기간: 90일
     */
    public static Payment completedPayment() {
        return Payment.of(
            "pay_test_001",
            1L,
            1L,
            "TOSS",
            BigDecimal.valueOf(100000),
            "KRW",
            90,
            LocalDateTime.of(2026, 1, 1, 0, 0, 0)
        );
    }

    /**
     * 취소 기간이 1일인 완료 결제
     */
    public static Payment completedPaymentWith1DayPeriod() {
        return Payment.of(...);
    }

    private PaymentFixture() {  // Prevent instantiation
    }
}
```

**Location:**
- `src/test/java/com/example/{module}/fixture/{Domain}Fixture.java`
- Organized by domain entity, not by test class
- Reused across multiple test classes

**Pattern: Variants by Purpose:**
```java
public class CancelRequestFixture {
    
    // Basic successful cancel
    public static CancelRequest pendingCancelRequest(Long id, Long paymentId) {
        return CancelRequest.reconstruct(
            id, paymentId, "hash-001", 
            BigDecimal.valueOf(30_000), "변심", List.of(1L),
            CancelStatus.PENDING, 0, null, null,
            Instant.now(), Instant.now());
    }

    // For retry scenarios
    public static CancelRequest failedCancelRequest(Long paymentId) {
        return CancelRequest.reconstruct(
            99L, paymentId, "hash-002",
            BigDecimal.valueOf(30_000), "변심", List.of(1L),
            CancelStatus.FAILED, 1, null, null,
            Instant.now(), Instant.now());
    }
}
```

## Coverage

**Requirements:**
- 80% line coverage enforced at `payment-service` module level (JaCoCo)
- Configured in `payment-service/build.gradle`:
```gradle
jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = 'LINE'
                value   = 'COVEREDRATIO'
                minimum = 0.80
            }
        }
    }
}
```

**View Coverage:**
```bash
./gradlew :payment-service:test           # Run tests
./gradlew :payment-service:jacocoTestReport  # Generate HTML report
open payment-service/build/reports/jacoco/html/index.html
```

**Coverage-Excluded Areas (acceptable gaps):**
- Configuration classes (Spring beans, properties)
- DTO constructors and simple getters
- Framework integration code (message converters, Spring Boot autoconfiguration)
- Fallback paths for external service failures (tested via integration tests)

## Test Types

**Unit Tests:**
- Scope: Single class behavior (one service or entity)
- Dependencies: All mocked or constructed in setUp
- Speed: < 100ms per test
- Location: Same package as source in `src/test/java/`
- Naming: `{ClassName}Test.java`

Example: `CancelPaymentServiceTest` tests `CancelPaymentService` with all repositories mocked.

**Integration Tests (Testcontainers):**
- Scope: Full flow through multiple layers (service → repository → DB)
- Dependencies: Real MySQL container, mocked external systems (HTTP, Kafka)
- Speed: 500ms - 2s per test (DB I/O)
- Location: `src/test/java/com/example/{module}/integration/`
- Naming: `{Scenario}IntegrationTest.java`

Example: `CancelFlowIntegrationTest` tests entire cancel flow with real database, mocked Risk/PG ports.

**E2E Tests:**
- Not currently used in this codebase
- Infrastructure: `infra/load-test/` contains k6 load tests (separate from unit/integration)

## Common Patterns

**Async Testing (Kafka messages):**
```java
@Test
void shouldPublishCancelledEvent() {
    // Arrange
    ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));

    // Act
    sut.cancel(command);

    // Assert
    verify(kafkaTemplate).send(topicCaptor.capture(), eq(cancelRequestId), messageCaptor.capture());
    assertThat(topicCaptor.getValue()).isEqualTo("payment.cancelled");
}
```

**Error Testing (Expected Exceptions):**
```java
@Test
void shouldThrowCancelPeriodExceeded() {
    // Arrange: Create entity with expired period
    Payment payment = PaymentFixture.completedPaymentWith1DayPeriod();
    Clock clock = Clock.fixed(Instant.parse("2026-01-03T00:00:00Z"), ZoneOffset.UTC);  // 2 days later
    CancelDomainService domainService = new CancelDomainService(new CancelPeriodPolicy(clock));

    // Act & Assert
    assertThrows(CancelPeriodExceededException.class, () -> 
        domainService.validateCancellablePeriod(payment));
}
```

**Optional Assertions (using AssertJ):**
```java
@Test
void shouldFindPaymentByKey() {
    Optional<Payment> result = repository.findByPaymentKey("pay_001");
    
    assertThat(result)
        .isPresent()
        .hasValueSatisfying(p -> assertThat(p.getMerchantId()).isEqualTo(1L));
}
```

**BigDecimal Comparison (exact match):**
```java
assertThat(usage.getUsedAmount())
    .isEqualByComparingTo(BigDecimal.valueOf(300_000));

// Not .isEqualTo() which uses Object.equals() (scale matters in BigDecimal)
```

**List/Set Assertions:**
```java
assertThat(cancelRequestRepository.findAll())
    .hasSize(1)
    .allSatisfy(cr -> assertThat(cr.getStatus()).isEqualTo(CancelStatus.PENDING));
```

**DisplayName Convention:**
- Use snake_case method names and `@DisplayName` with sentence case / Korean for readability
- Example: `shouldReturnExistingResultWhenCancelRequestCompleted` → `"should return existing result when cancel request completed"`
- Domain logic tests use Korean: `"정상 취소 — risk·PG 모두 성공 시 COMPLETED 반환"`

## Test Resources Configuration

**Path:** `src/test/resources/application.yml`

**Pattern (excludes external dependencies for unit tests):**
```yaml
spring:
  autoconfigure:
    exclude:
      - org.redisson.spring.starter.RedissonAutoConfigurationV4
      - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
      - org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
  
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-timeout: 30000
      initialization-fail-timeout: -1
  
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
```

This allows unit tests to run without Redis/Kafka running; integration tests use Testcontainers for MySQL override.

---

*Testing analysis: 2026-07-28*
