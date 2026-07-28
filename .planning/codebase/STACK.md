# Technology Stack

**Analysis Date:** 2026-07-28

## Languages

**Primary:**
- Java 21 - All service modules (payment-service, order-service, merchant-limit-service, risk-management-service, product-service)

**Secondary:**
- YAML - Configuration files (application.yml, docker-compose.yml)
- SQL - Database migrations (Flyway migration scripts)

## Runtime

**Environment:**
- JDK 21 (eclipse-temurin:21-jre-alpine for production containers)
- Gradle 8 (build orchestration)

**Package Manager:**
- Gradle 8
- Lockfile: `gradle/wrapper/gradle-wrapper.properties` (Gradle wrapper version pinned)

## Frameworks

**Core:**
- Spring Boot 4.0.5 - Web framework, application lifecycle management
- Spring Framework 7.x - Dependency injection, AOP, configuration management
- Spring Data JPA - ORM abstraction layer, query generation

**Database:**
- Flyway 10.21.0 - Schema versioning and migrations (locations: `src/main/resources/db/migration/V*.sql`)
- Hibernate dialect (MySQL 8.0) - JPA implementation

**Testing:**
- JUnit 5 (spring-boot-starter-test) - Unit/integration test framework
- Mockito - Test doubles and mocking
- Testcontainers 1.19.7 (mysql, h2) - Containerized test dependencies
- JaCoCo 0.8.12 - Code coverage reporting

**Build/Dev:**
- Gradle plugins: spring-boot, dependency-management, flyway, jacoco
- ArchUnit 1.2.0 - Architecture rules testing

**Observability:**
- Micrometer registry-prometheus - Metrics collection and Prometheus exposure
- Spring Boot Actuator - Health, metrics, and info endpoints
- Resilience4j circuitbreaker 2.2.0 - Fault tolerance with metrics integration

## Key Dependencies

**Critical:**
- `org.springframework.boot:spring-boot-starter-web` - HTTP server (Tomcat embedded)
- `org.springframework.boot:spring-boot-starter-data-jpa` - ORM abstraction
- `org.springframework.kafka:spring-kafka` - Kafka producer/consumer integration
- `org.springframework.boot:spring-boot-starter-validation` - Bean validation
- `com.mysql:mysql-connector-j` - MySQL 8.0 JDBC driver

**Distributed Systems:**
- `org.redisson:redisson-spring-boot-starter:4.3.1` - Distributed locking via Redis (payment-service, merchant-limit-service)
- `org.springframework.boot:spring-boot-starter-data-redis` - Redis client (risk-management-service)
- `io.github.resilience4j:resilience4j-circuitbreaker:2.2.0` - Circuit breaker pattern (payment-service)
- `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` - Resilience4j Spring Boot 3 integration (risk-management-service)
- `org.springframework.boot:spring-boot-starter-aspectj` - AOP support for circuit breaker (risk-management-service)

**HTTP & REST:**
- `org.springframework.boot:spring-boot-starter-restclient` - RestTemplate builder and metrics customization (payment-service)

**Utility:**
- `org.projectlombok:lombok` - Boilerplate reduction (compilation only)
- `net.ttddyy:datasource-proxy:1.10` - Query counting for observability (common-observability module)

**Database Migration:**
- `org.flywaydb:flyway-core` - Migration engine
- `org.flywaydb:flyway-mysql` - MySQL dialect support

## Configuration

**Environment:**
- Properties: `src/main/resources/application.yml` per module
- Environment variable overrides: `SPRING_DATASOURCE_*`, `SPRING_KAFKA_*`, `SPRING_DATA_REDIS_*`, `CANCEL_PUBLISH_MODE`, `RISK_LIMIT_CACHE_ENABLED`, `RISK_LIMIT_SNAPSHOT_ENABLED`, `OTEL_JAVAAGENT` (observability)
- Profiles: `local` (default), non-local (production)

**Build:**
- `build.gradle` (root) - Shared plugin versions, dependency management, Java 21 target
- `{service}/build.gradle` - Service-specific dependencies and Flyway configuration

**Database:**
- MySQL 8.0 (5 independent per-module databases):
  - `payment_db` (payment-service, port 3311)
  - `order_db` (order-service, port 3307)
  - `merchant_db` (merchant-limit-service, port 3308)
  - `risk_db` (risk-management-service, port 3309)
  - `product_db` (product-service, port 3310)
- Flyway baseline: disabled (all versions tracked)
- DDL mode: `validate` (schema must pre-exist via Flyway)

**Kafka:**
- Bootstrap servers: `localhost:9092,localhost:9093,localhost:9094` (local) or Docker internal: `kafka1:29092,kafka2:29093,kafka3:29094`
- Serialization: StringSerializer for keys and values
- Producer acks: `all` (durability guarantee)
- Enable idempotence: true (duplicate prevention)
- Replication factor: 3, min in-sync replicas: 2

**Redis:**
- Connection: `localhost:6379` (default or `${SPRING_DATA_REDIS_*}`)
- Redisson distributed locks: scheduler lock keys prefixed with `lock:` (e.g., `lock:scheduler:pending-recovery`)

## Platform Requirements

**Development:**
- Java 21 JDK (for compilation)
- Docker + Docker Compose 3.8+ (for infrastructure: MySQL, Kafka, Redis, Zookeeper)
- Gradle 8 wrapper (included)
- Bash/shell for build scripts

**Production:**
- Java 21 JRE (eclipse-temurin:21-jre-alpine)
- Kubernetes (k3s topology tested in load-test) or Docker Compose
- External MySQL 8.0 (each module has independent DB)
- Kafka 3.x cluster (3 brokers, confluent images)
- Redis 7.2 (distributed locking + caching)

**Optional Observability:**
- Prometheus (scrapes `/actuator/prometheus` from each service)
- Grafana (dashboards — observability stack in `infra/load-test/observability/docker-compose.yml`)
- OpenTelemetry Java Agent (OTEL_JAVAAGENT for distributed tracing)

---

*Stack analysis: 2026-07-28*
