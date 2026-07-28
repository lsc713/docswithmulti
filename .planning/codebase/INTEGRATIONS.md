# External Integrations

**Analysis Date:** 2026-07-28

## APIs & External Services

**Payment Gateway (PG):**
- Service: External Payment Gateway (PG provider, e.g., Toss Payments, KISA)
  - SDK/Client: `RestTemplate` via Spring Boot
  - Implementation: `payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java`
  - Endpoint: `POST {external.pg.url}/v1/payments/{paymentKey}/cancel`
  - Auth: None specified (likely API key in production via env var or header)
  - Profile: Active only when `!local` (local uses mock implementation)
  - Resilience: Circuit breaker (Resilience4j) with 50% failure threshold, 10-call sliding window

**Risk Management Service (Internal HTTP):**
- Service: Microservice within the system
  - SDK/Client: `RestTemplate` via Spring Boot
  - Implementation: `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java`
  - Endpoints:
    - `POST {external.risk-management.url}/internal/cancel-limit/validate-and-reserve` - Reserve daily cancel limit
    - `POST {external.risk-management.url}/internal/cancel-limit/compensate` - Restore limit on cancellation failure
  - Config: `external.risk-management.url` (default: `http://localhost:8083`)
  - Resilience: Circuit breaker with exception filtering (ignores `MerchantCancelLimitNotFoundException`)

**Merchant Limit Service (Internal HTTP):**
- Service: Microservice within the system
  - SDK/Client: HTTP calls via RestTemplate
  - Implementation: Consumed by risk-management-service
  - Endpoint: `http://localhost:8082` (risk-management-service calls this via circuit breaker)
  - Config: `external.merchant-limit.base-url` (default: `http://localhost:8082`)
  - Role: Authoritative source for merchant daily cancel limits

**Order Service (Internal Async):**
- Service: Microservice within the system
  - Integration: Kafka consumer (event-driven)
  - Consumes: `payment.cancelled` topic
  - Config: `kafka.topic.payment-cancelled` (default: `payment.cancelled`)
  - Retry topic: `payment.cancelled.retry` (dead-letter queue: `payment.cancelled.DLQ`)

## Data Storage

**Databases:**
- Type/Provider: MySQL 8.0 (per-module sharding)
- Connection:
  - `payment_db`: `jdbc:mysql://localhost:3311/payment_db` (credentials: payment/payment)
  - `order_db`: `jdbc:mysql://localhost:3307/order_db` (credentials: order_user/order)
  - `merchant_db`: `jdbc:mysql://localhost:3308/merchant_db` (credentials: merchant/merchant)
  - `risk_db`: `jdbc:mysql://localhost:3309/risk_db` (credentials: risk/risk)
  - `product_db`: `jdbc:mysql://localhost:3310/product_db` (credentials: product/product)
- Client: Spring Data JPA with Hibernate (dialect: `org.hibernate.dialect.MySQLDialect`)
- Configuration:
  - HikariCP connection pooling: max 30 connections (default), 30s timeout
  - Flyway: Automatic schema versioning (disabled baseline, validate mode)
  - Batch size: 20, fetch size: 50
  - Time zone: UTC

**File Storage:**
- Not in scope (payment cancellation system does not manage files)

**Caching:**
- Type/Provider: Redis 7.2
- Connection: `localhost:6379` (configurable via `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`)
- Usage:
  - Distributed locking via Redisson (lock keys: `lock:scheduler:*`, `lock:merchant-limit:*`)
  - Daily cancel limit cache (risk-management-service): `daily_limit:{merchantId}:{kstDate}`
- Client: Redisson spring-boot-starter (4.3.1) for locking, Spring Data Redis for cache ops
- Persistence: Not required (cache invalidates on daily reset)

## Authentication & Identity

**Auth Provider:**
- Not in scope (system operates with trusted service-to-service calls within a private network)

**Service-to-Service:**
- payment-service → risk-management-service: No authentication (internal network)
- risk-management-service → merchant-limit-service: No authentication (internal network)
- payment-service → PG: Likely API key or OAuth (configured in production via `external.pg.url` env var, secrets not exposed in code)
- order-service ← payment-service: Kafka topic consumption (no auth, trusted broker)

## Monitoring & Observability

**Error Tracking:**
- Mechanism: Structured logging via SLF4j + Logback (Spring Boot default)
- Log levels: `INFO` for business logic, `DEBUG` for framework
- Exception handling: Custom exception hierarchy (e.g., `RiskServiceException`, `PgServiceException`)
- Resilience4j circuit breaker integration: Emits metrics on failures

**Logs:**
- Approach: Application logs → stdout (Docker) or file (local dev)
- Log levels per module configured in `application.yml`:
  - `com.example.payment`: INFO
  - `com.example.riskmanagement`: INFO
  - `com.example.merchantlimit`: INFO
  - `org.hibernate.SQL`: INFO

**Metrics:**
- Framework: Micrometer (registry: Prometheus)
- Exposure: `/actuator/prometheus` endpoint on each service
- Key metrics:
  - `http.server.requests` - Request latency histogram (p95, p99 percentiles)
  - `http.client.requests` - Outbound HTTP calls to PG and risk-management services
  - `cancel.event.e2e.latency` - End-to-end cancellation latency (order-service)
  - Kafka consumer lag (via kafka-exporter, separate monitoring)
  - Resilience4j circuit breaker metrics (state, call counts, exceptions)

**Health Checks:**
- Endpoint: `/actuator/health` (Spring Boot Actuator)
- Includes: database connectivity, Kafka broker availability, Redis connection

**Distributed Tracing:**
- Optional: OpenTelemetry Java Agent (configured via `OTEL_JAVAAGENT` environment variable)
- Integration: Tempo backend (in observability stack, `infra/load-test/observability/docker-compose.yml`)

## CI/CD & Deployment

**Hosting:**
- Local: Docker Compose (development)
- Production: Kubernetes (k3s validated in load-test topology, `infra/load-test/deploy/`)
- Container images: Built per-module using Dockerfile (multistage: Gradle build → runtime)

**CI Pipeline:**
- Tool: Not detected (no `.github/workflows/`, `.gitlab-ci.yml`, etc. in scope)
- Build: `./gradlew build` (local)
- Test: `./gradlew :payment-service:test` (module-specific)
- Coverage: JaCoCo 0.8.12 (minimum 80% line coverage enforced)

**Environment Configuration:**
- Development: `application.yml` with sensible defaults (`localhost` services)
- Production: Environment variable overrides (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `external.*`)
- Secrets: Not detected in codebase (credentials passed via env vars or external secrets management)

## Environment Configuration

**Required env vars:**
- Datasource:
  - `SPRING_DATASOURCE_URL` - JDBC connection string (e.g., `jdbc:mysql://mysql-payment:3306/payment_db`)
  - `SPRING_DATASOURCE_USERNAME` - Database user
  - `SPRING_DATASOURCE_PASSWORD` - Database password
- Kafka:
  - `SPRING_KAFKA_BOOTSTRAP_SERVERS` - Comma-separated broker list (e.g., `kafka1:29092,kafka2:29093,kafka3:29094`)
- Redis:
  - `SPRING_DATA_REDIS_HOST` - Redis hostname
  - `SPRING_DATA_REDIS_PORT` - Redis port (default: 6379)
- External Services:
  - `EXTERNAL_RISK_MANAGEMENT_URL` - Risk management base URL (payment-service)
  - `EXTERNAL_MERCHANT_LIMIT_BASE_URL` - Merchant limit service URL (risk-management-service)
  - `external.pg.url` - Payment gateway URL (not env var in local config, but production likely uses env var)
- Observability:
  - `OTEL_JAVAAGENT` - Path to OpenTelemetry Java agent JAR (optional)
  - `LOADTEST_QUERYCOUNT_ENABLED` - Enable query counting (load-test mode)
- Feature Toggles (risk-management-service):
  - `RISK_LIMIT_CACHE_ENABLED` - Enable Redis cache for daily limits (default: true)
  - `RISK_LIMIT_SNAPSHOT_ENABLED` - Enable DB snapshot fallback (default: true)
- Cancellation Modes (payment-service):
  - `CANCEL_PUBLISH_MODE` - Publication strategy: `INLINE` | `INLINE_ASYNC` | `OUTBOX` (default: INLINE)
  - `CANCEL_OUTBOX_POLL_MS` - Outbox polling interval (default: 10000)
  - `CANCEL_OUTBOX_BATCH_SIZE` - Outbox batch size (default: 1000)

**Secrets location:**
- Not exposed in code
- Convention: Environment variables or external secret management (Spring Cloud Config, Vault, AWS Secrets Manager)
- CI/CD: GitHub Actions secrets, GitLab CI/CD secrets, or equivalent

## Webhooks & Callbacks

**Incoming:**
- Not detected (payment cancellation is request-driven, not event-driven inbound)

**Outgoing:**
- Kafka topics published by payment-service:
  - `payment.cancelled` - Published when cancel transaction completes (TX3, inline in transaction)
  - Partition key: `cancelRequestId` (ensures cancel request ordering)
  - Consumed by: order-service for inventory sync

- Kafka topics published by merchant-limit-service:
  - `merchant.limit.updated` - Published when daily limit changes (Outbox pattern, not inline)
  - Partition key: `merchantId`
  - Consumed by: risk-management-service (cache invalidation)

**Kafka Topic Configuration:**
- Replication factor: 3
- Min in-sync replicas: 2
- Retention: Default (Kafka broker config)

---

*Integration audit: 2026-07-28*
