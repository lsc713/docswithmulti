# Codebase Structure

**Analysis Date:** 2026-07-28

## Directory Layout

```
/Users/juho/Documents/docswithmulti/
├── .claude/                         # Claude Code configuration (project-scoped)
│   ├── skills/                      # Project skills
│   ├── agents/                      # Agent definitions
│   └── oncall/                      # Oncall automation configs
├── .planning/
│   └── codebase/                    # Codebase map documents (this directory)
│       ├── ARCHITECTURE.md
│       ├── STRUCTURE.md
│       ├── CONVENTIONS.md
│       ├── TESTING.md
│       ├── STACK.md
│       ├── INTEGRATIONS.md
│       └── CONCERNS.md
├── docs/                            # Design, conventions, architecture docs (git-tracked)
│   ├── architecture.md              # System overview + layer patterns
│   ├── architecture/                # HTML diagram (mermaid renders)
│   │   └── index.html
│   ├── conventions/
│   │   └── architecture.md          # Layer structure + exception hierarchy
│   ├── domain-rules.md              # Business rules (cancel period, limits, states)
│   ├── db-schema.md                 # DB design per service (independent DBs)
│   ├── api-spec.md                  # REST API request/response specs
│   ├── error-catalog.md             # All error codes + HTTP status mapping
│   ├── kafka-design.md              # Kafka topic structure + Consumer groups
│   ├── load-test/                   # Performance testing docs + results
│   │   ├── measurement-journey.md
│   │   ├── k3s-scaleout-results.md
│   │   ├── saturation-diagnosis.md
│   │   └── publish-pattern-benchmark.md
│   └── superpowers/                 # GSD phase execution docs
│       ├── plans/                   # Implementation plans per phase
│       └── specs/                   # Design specs per phase
├── sysdesign/                       # System design deep-dives (design-phase outputs)
│   ├── cancel-design.md             # Payment cancel flow: TX1/TX2/TX3, idempotence, recovery
│   └── detail/                      # Per-component sub-designs
├── infra/                           # Infrastructure as Code + load testing
│   ├── docker-compose.yml           # Local dev: MySQL, Kafka, Redis, services
│   ├── k8s/                         # Kubernetes manifests (optional future)
│   ├── k3s-scaleout/                # k3s cluster test topology + load test
│   │   └── terraform/               # Infrastructure setup
│   └── load-test/                   # k6 load test scripts
├── k6/                              # k6 load testing source
│   ├── seed/                        # Database seed scripts (merchants, products)
│   ├── helpers/                     # k6 helper functions
│   └── tests/                       # Test scenarios (.js)
├── common-observability/            # Shared library: query observability, OTEL config
│   ├── src/main/java/com/example/common/observability/
│   │   ├── querycount/              # Query counter filter + reader
│   │   └── ...
│   ├── build.gradle
│   └── db/migration/                # Flyway migrations (if any)
├── payment-service/                 # Core cancel orchestration
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/payment/
│   │   │   │   ├── presentation/
│   │   │   │   │   ├── controller/  # REST endpoints
│   │   │   │   │   │   └── PaymentCancelController
│   │   │   │   │   └── dto/         # Request/response DTOs
│   │   │   │   ├── application/
│   │   │   │   │   ├── service/     # Use case implementations
│   │   │   │   │   │   ├── CancelPaymentService
│   │   │   │   │   │   └── ...
│   │   │   │   │   ├── usecase/     # Use case interfaces
│   │   │   │   │   ├── interfaces/  # Repository abstractions, external contracts
│   │   │   │   │   │   ├── PaymentRepository
│   │   │   │   │   │   ├── CancelRequestRepository
│   │   │   │   │   │   ├── RiskManagementClient
│   │   │   │   │   │   └── ...
│   │   │   │   │   └── exception/   # Application-layer exceptions (resource not found, etc)
│   │   │   │   ├── domain/
│   │   │   │   │   ├── entity/      # Domain entities: Payment, PaymentItem, CancelRequest
│   │   │   │   │   ├── service/     # Domain services (multi-entity orchestration)
│   │   │   │   │   ├── policy/      # Policy objects (cancelability rules)
│   │   │   │   │   └── exception/   # Domain exceptions (business rule violations)
│   │   │   │   ├── infrastructure/
│   │   │   │   │   ├── persistence/ # JPA repositories, query implementations
│   │   │   │   │   │   ├── CancelRequestRepositoryImpl
│   │   │   │   │   │   ├── CancelRequestJpaEntity
│   │   │   │   │   │   ├── PaymentJpaEntity
│   │   │   │   │   │   ├── CancelTxWriter (TX3 logic)
│   │   │   │   │   │   └── converter/ # Entity ↔ JPA entity mappers
│   │   │   │   │   ├── messaging/   # Kafka producers
│   │   │   │   │   │   ├── CancelEventProducer
│   │   │   │   │   │   └── MerchantLimitUpdateListener (consumer)
│   │   │   │   │   ├── http/        # External HTTP clients
│   │   │   │   │   │   ├── RiskManagementRestClient
│   │   │   │   │   │   ├── PgServiceRestClient (mocked in local dev)
│   │   │   │   │   │   └── ...
│   │   │   │   │   ├── scheduler/   # Scheduled recovery tasks
│   │   │   │   │   │   ├── PendingRecoveryScheduler
│   │   │   │   │   │   ├── ProcessingRecoveryScheduler
│   │   │   │   │   │   ├── CompensationRetryScheduler
│   │   │   │   │   │   └── FailedKafkaPublisherScheduler
│   │   │   │   │   ├── adapter/     # Spring adapters (RestTemplate, JdbcTemplate, etc)
│   │   │   │   │   ├── config/      # Spring @Configuration classes
│   │   │   │   │   └── exception/   # Infrastructure exceptions (HTTP failures)
│   │   │   │   └── common/
│   │   │   │       └── exception/   # BusinessException base class
│   │   │   └── resources/
│   │   │       ├── application.yml  # Spring Boot config
│   │   │       ├── application-local.yml
│   │   │       └── logback-spring.xml
│   │   └── test/
│   │       ├── java/com/example/payment/
│   │       │   ├── domain/          # Domain unit tests (pure logic)
│   │       │   ├── application/     # Application tests (mocked infra)
│   │       │   ├── infrastructure/  # Infra tests (Testcontainers, DB)
│   │       │   ├── presentation/    # Controller tests (MockMvc)
│   │       │   └── architecture/    # ArchUnit tests (dependency rules)
│   │       └── resources/
│   │           ├── test-application.yml
│   │           └── fixtures/        # Test data
│   ├── db/migration/                # Flyway migrations (V1__create_payment_core.sql, etc)
│   ├── build.gradle
│   └── PaymentServiceApplication.java # Spring Boot entry point
├── order-service/                   # OrderItem sync on cancel completion
│   ├── src/main/java/com/example/order/
│   │   ├── presentation/            # (Minimal; internal APIs only)
│   │   ├── application/
│   │   ├── domain/                  # Order, OrderItem entities
│   │   └── infrastructure/
│   │       ├── messaging/           # Kafka Consumer: payment.cancelled
│   │       ├── persistence/         # Order, OrderItem repositories
│   │       └── ...
│   ├── db/migration/
│   ├── build.gradle
│   └── OrderServiceApplication.java
├── merchant-limit-service/          # Merchant daily cancel limit master data
│   ├── src/main/java/com/example/merchantlimit/
│   │   ├── presentation/
│   │   │   └── controller/InternalMerchantLimitController  # GET /merchants/{id}/cancel-limit
│   │   ├── application/
│   │   ├── domain/                  # Merchant, MerchantCancelLimit entities
│   │   ├── infrastructure/
│   │   │   ├── messaging/           # Kafka Producer: merchant.limit.updated
│   │   │   ├── persistence/         # Merchant repositories
│   │   │   └── ...
│   │   └── ...
│   ├── db/migration/
│   ├── build.gradle
│   └── MerchantLimitApplication.java
├── risk-management-service/         # Merchant limit validation + compensation
│   ├── src/main/java/com/example/riskmanagement/
│   │   ├── presentation/
│   │   │   └── controller/InternalCancelLimitController
│   │   │       ├── POST /internal/cancel-limit/validate-and-reserve
│   │   │       ├── POST /internal/cancel-limit/compensate
│   │   │       └── GET  /internal/cancel-limit/check
│   │   ├── application/
│   │   │   ├── service/ValidateAndReserveService  # Daily_limit tier-1/2/3 logic
│   │   │   ├── interfaces/DailyLimitCache (Redis), MerchantLimitClient (HTTP)
│   │   │   └── ...
│   │   ├── domain/
│   │   │   └── entity/MerchantCancelUsage  # Track daily consumption
│   │   ├── infrastructure/
│   │   │   ├── cache/RedisDailyLimitCache
│   │   │   ├── persistence/MerchantCancelUsageRepository (FOR UPDATE)
│   │   │   ├── http/MerchantLimitRestClient
│   │   │   ├── messaging/MerchantLimitUpdateListener (consumer)
│   │   │   └── ...
│   │   └── ...
│   ├── db/migration/
│   ├── build.gradle
│   └── RiskManagementApplication.java
├── product-service/                 # Product/SKU management (not yet implemented)
│   ├── src/main/java/com/example/product/
│   ├── db/migration/
│   ├── build.gradle
│   └── ProductServiceApplication.java
├── build.gradle                     # Root Gradle build (multi-module setup)
├── settings.gradle                  # Gradle module registration
├── docker-compose.yml               # Local dev orchestration (MySQL, Kafka, Redis, service containers)
├── CLAUDE.md                        # Project instructions (this file loaded first)
├── .gitignore                       # Git ignore rules
└── .env                             # Environment variables (not git-tracked; listed in .gitignore)
```

## Directory Purposes

**docs/:**
- Single source of truth for architecture, business rules, conventions
- Read-first before coding; updated during design phases
- Includes both human-readable markdown and generated HTML diagrams

**sysdesign/:**
- Detailed flow diagrams and design decisions for complex features
- `cancel-design.md` is mandatory reading before implementing any cancel-related code

**infra/:**
- Docker Compose for local development + k3s terraform for production-like load testing
- Load test scenarios in k6 directory

**Common services structure:**
- Each `*-service` is independent Spring Boot application with its own database
- All follow same layered directory pattern: presentation → application → domain ← infrastructure

## Key File Locations

**Entry Points:**
- `payment-service/src/main/java/com/example/payment/PaymentServiceApplication.java` — Main Spring Boot app (port 8080)
- `payment-service/src/main/java/com/example/payment/presentation/controller/PaymentCancelController.java` — Cancel API endpoint
- `order-service/src/main/java/com/example/order/OrderServiceApplication.java` — Port 8081
- `merchant-limit-service/src/main/java/com/example/merchantlimit/MerchantLimitApplication.java` — Port 8082
- `risk-management-service/src/main/java/com/example/riskmanagement/RiskManagementApplication.java` — Port 8083

**Configuration:**
- `build.gradle` — Root multi-module Gradle config
- `docker-compose.yml` — MySQL, Kafka, Redis, services (local dev)
- `payment-service/src/main/resources/application.yml` — Service-specific Spring Boot config
- `CLAUDE.md` — Project conventions and constraints (read first)

**Core Logic:**
- `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java` — Main cancel orchestration
- `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelTxWriter.java` — TX3 (complete) logic
- `risk-management-service/src/main/java/com/example/riskmanagement/application/service/ValidateAndReserveService.java` — Merchant limit validation with tier-1/2/3 fallback
- `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/` — Recovery schedulers (pending, processing, compensation-retry)

**Testing:**
- `payment-service/src/test/java/com/example/payment/` — Unit + integration tests
- `payment-service/src/test/java/com/example/payment/architecture/` — ArchUnit dependency rules
- `k6/tests/` — Load test scripts for K6

**Database:**
- `payment-service/db/migration/` — Flyway SQL migrations (V1__create_payment_core.sql, etc.)
- `docs/db-schema.md` — Schema documentation + index strategy

## Naming Conventions

**Files:**
- Java classes: `PascalCase` (e.g., `CancelPaymentService.java`, `MerchantCancelUsage.java`)
- Test files: `*Test.java` or `*IntegrationTest.java` depending on scope
- SQL migrations: `VN__short_description.sql` (e.g., `V1__create_payment_core.sql`, `V2__add_cancel_history_index.sql`)
- DTOs: `*Request.java`, `*Response.java` (e.g., `CancelPaymentRequest.java`, `CancelPaymentResponse.java`)
- JPA entities: `*JpaEntity.java` (e.g., `PaymentJpaEntity.java`); domain: `*.java` (e.g., `Payment.java`)

**Directories:**
- Package structure mirrors directory tree: `com.example.{module}.{layer}.{subdomain}`
  - Example: `payment-service/src/main/java/com/example/payment/domain/entity/`
- `*Repository`: Data access abstractions; interfaces in `application/interfaces`, impls in `infrastructure/persistence`
- `*Service`: Use cases (application), domain logic (domain), or adapters (infrastructure)
- `*Client`: External HTTP client (infrastructure)
- `*Listener`: Kafka consumer (infrastructure/messaging)
- `*Scheduler`: Scheduled task (infrastructure/scheduler)

**Methods:**
- Domain entities: `cancel()`, `complete()`, `isActive()`, `addUsedAmount()` (action verbs, no getters exposed in public API)
- Repositories: `findById()`, `findAllByPaymentIdForUpdate()`, `save()`, `delete()`
- Services: `execute()`, `validate()`, `reserve()`, `compensate()`

**Constants & Enums:**
- `UPPER_SNAKE_CASE` (e.g., `CANCEL_PERIOD_EXPIRED`, `ErrorCode.MERCHANT_LIMIT_EXCEEDED`)
- Enum values: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` (CancelRequest state)

## Where to Add New Code

**New Feature (Cancel-related):**
- Primary code: `payment-service/src/main/java/com/example/payment/application/service/`
- Domain logic: `payment-service/src/main/java/com/example/payment/domain/` (if new entity/invariant)
- Tests: `payment-service/src/test/java/com/example/payment/{layer}/` (mirror production structure)
- Migration: `payment-service/db/migration/VN__*.sql` (increment V number)

**New Endpoint (REST):**
- Controller: `{service}/src/main/java/com/example/{module}/presentation/controller/`
- DTO: `{service}/src/main/java/com/example/{module}/presentation/dto/`
- Use case service: `{service}/src/main/java/com/example/{module}/application/service/`

**New Validator/Policy:**
- Domain layer: `{service}/src/main/java/com/example/{module}/domain/policy/`
- Example: `CancelPolicyEngine` could validate cancel-ability rules

**New Kafka Topic Consumer:**
- Listener class: `{service}/src/main/java/com/example/{module}/infrastructure/messaging/{TopicName}Listener.java`
- Configuration: `{service}/src/main/java/com/example/{module}/infrastructure/config/KafkaConsumerConfig.java`

**New Scheduled Job:**
- Scheduler class: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/{JobName}Scheduler.java`
- Redis lock config: Already in place (`infrastructure/config/RedisConfig.java`)

**New Shared Library Code:**
- Location: `common-observability/src/main/java/com/example/common/observability/`
- Don't add business logic here; reserve for cross-cutting concerns (logging, metrics, query counting)

**New Service (5th+ microservice):**
- Clone from `payment-service` or `merchant-limit-service` (most complete examples)
- Create new module directory: `{new-service}/`
- Mirror all layers: presentation → application → domain ← infrastructure
- Add to `settings.gradle` for Gradle multi-module build
- Add entry in `docker-compose.yml` for local dev

## Special Directories

**docs/superpowers/:**
- Generated output from GSD phase execution (plans + specs)
- Do NOT edit manually; regenerate via `/gsd-plan-phase` + `/gsd-execute-phase`
- Consumed by follow-up phases

**build/, .gradle/, .idea/:**
- Generated; git-ignored
- `build/` contains compiled classes, test results
- `.gradle/` contains Gradle cache
- `.idea/` contains IntelliJ metadata

**db/migration/:**
- Flyway versioned SQL scripts; applied once to DB, never modified
- To change schema: add new `VN__*.sql` file (increment N)
- Do NOT edit existing migration files (breaks other developers' DBs)

**src/test/resources/**
- `test-application.yml` — Overrides for test environment (in-memory DB, disabled schedulers, etc)
- `fixtures/` — Seed data (merchants, products) for integration tests

**logs/:**
- Runtime logs (git-ignored)
- Checked into repo during measurement-journey, removed after analysis

---

*Structure analysis: 2026-07-28*
