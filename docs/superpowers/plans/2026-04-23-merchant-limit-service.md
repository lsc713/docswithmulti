# merchant-limit-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 가맹점별 일일 취소한도 원본 관리 서비스 구현 — internal API, 관리자 CRUD API, `merchant.limit.updated` Kafka Outbox 발행

**Architecture:** payment-service와 동일한 Hexagonal 구조(domain → application → infrastructure → presentation). 도메인은 순수 Java, JPA/Spring 의존 없음. Kafka Outbox 패턴으로 이벤트 유실 방지. Redis 분산락으로 Outbox 스케줄러 중복 실행 방지.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Data JPA, MySQL 8.0, Flyway, Apache Kafka, Spring Data Redis, Resilience4j, JUnit 5, Mockito, Testcontainers, Lombok

---

## 파일 구조

```
merchant-limit-service/
├── build.gradle
└── src/
    ├── main/
    │   ├── java/com/example/merchantlimit/
    │   │   ├── MerchantLimitApplication.java
    │   │   ├── common/exception/
    │   │   │   ├── BusinessException.java
    │   │   │   └── ErrorCode.java
    │   │   ├── domain/
    │   │   │   ├── entity/
    │   │   │   │   ├── Merchant.java
    │   │   │   │   ├── MerchantStatus.java
    │   │   │   │   ├── MerchantCancelLimit.java
    │   │   │   │   └── LimitHistory.java
    │   │   │   ├── service/
    │   │   │   │   └── MerchantLimitDomainService.java
    │   │   │   └── exception/
    │   │   │       ├── MerchantNotFoundException.java
    │   │   │       ├── MerchantSuspendedException.java
    │   │   │       └── InvalidLimitAmountException.java
    │   │   ├── application/
    │   │   │   ├── usecase/
    │   │   │   │   ├── GetCancelLimitUseCase.java
    │   │   │   │   └── UpdateCancelLimitUseCase.java
    │   │   │   ├── service/
    │   │   │   │   ├── GetCancelLimitService.java
    │   │   │   │   └── UpdateCancelLimitService.java
    │   │   │   └── interfaces/
    │   │   │       ├── MerchantRepository.java
    │   │   │       ├── MerchantCancelLimitRepository.java
    │   │   │       ├── LimitHistoryRepository.java
    │   │   │       └── LimitEventOutboxRepository.java
    │   │   ├── infrastructure/
    │   │   │   ├── persistence/
    │   │   │   │   ├── MerchantJpaEntity.java
    │   │   │   │   ├── MerchantJpaRepository.java
    │   │   │   │   ├── MerchantRepositoryImpl.java
    │   │   │   │   ├── MerchantCancelLimitJpaEntity.java
    │   │   │   │   ├── MerchantCancelLimitJpaRepository.java
    │   │   │   │   ├── MerchantCancelLimitRepositoryImpl.java
    │   │   │   │   ├── LimitHistoryJpaEntity.java
    │   │   │   │   ├── LimitHistoryJpaRepository.java
    │   │   │   │   ├── LimitHistoryRepositoryImpl.java
    │   │   │   │   ├── LimitEventOutboxJpaEntity.java
    │   │   │   │   ├── LimitEventOutboxJpaRepository.java
    │   │   │   │   └── LimitEventOutboxRepositoryImpl.java
    │   │   │   ├── messaging/
    │   │   │   │   ├── LimitEventKafkaProducer.java
    │   │   │   │   └── OutboxPublisherScheduler.java
    │   │   │   └── config/
    │   │   │       ├── PersistenceConfig.java
    │   │   │       ├── KafkaProducerConfig.java
    │   │   │       └── RedisLockConfig.java
    │   │   └── presentation/
    │   │       ├── controller/
    │   │       │   ├── InternalMerchantController.java
    │   │       │   └── AdminMerchantController.java
    │   │       ├── dto/
    │   │       │   ├── CreateMerchantRequest.java
    │   │       │   ├── PatchMerchantStatusRequest.java
    │   │       │   ├── UpdateLimitRequest.java
    │   │       │   ├── MerchantResponse.java
    │   │       │   ├── CancelLimitResponse.java
    │   │       │   ├── LimitHistoryPageResponse.java
    │   │       │   └── MerchantPageResponse.java
    │   │       └── GlobalExceptionHandler.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           └── V1__create_merchant_limit_core.sql
    └── test/
        └── java/com/example/merchantlimit/
            ├── domain/entity/
            │   ├── MerchantTest.java
            │   ├── MerchantCancelLimitTest.java
            │   └── MerchantLimitDomainServiceTest.java
            ├── application/service/
            │   ├── GetCancelLimitServiceTest.java
            │   └── UpdateCancelLimitServiceTest.java
            ├── infrastructure/persistence/
            │   ├── AbstractRepositoryTest.java
            │   ├── MerchantRepositoryImplTest.java
            │   └── MerchantCancelLimitRepositoryImplTest.java
            └── fixture/
                ├── MerchantFixture.java
                └── MerchantCancelLimitFixture.java
```

---

### Task 1: 프로젝트 기반 설정

**Files:**
- Create: `merchant-limit-service/build.gradle`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/MerchantLimitApplication.java`
- Create: `merchant-limit-service/src/main/resources/application.yml`
- Create: `merchant-limit-service/src/main/resources/db/migration/V1__create_merchant_limit_core.sql`

- [ ] **Step 1: build.gradle 작성**

```groovy
// merchant-limit-service/build.gradle
dependencies {
    // Kafka
    implementation 'org.springframework.kafka:spring-kafka'

    // Redis (분산락)
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
}
```

- [ ] **Step 2: main class 작성**

```java
// src/main/java/com/example/merchantlimit/MerchantLimitApplication.java
package com.example.merchantlimit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MerchantLimitApplication {
    public static void main(String[] args) {
        SpringApplication.run(MerchantLimitApplication.class, args);
    }
}
```

- [ ] **Step 3: application.yml 작성**

```yaml
# src/main/resources/application.yml
server:
  port: 8082

spring:
  application:
    name: merchant-limit-service

  datasource:
    url: jdbc:mysql://localhost:3306/merchant_limit_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
    username: merchant
    password: merchant
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQL8Dialect

  flyway:
    locations: classpath:db/migration
    baseline-on-migrate: false

  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5

  data:
    redis:
      host: localhost
      port: 6379

kafka:
  topic:
    merchant-limit-updated: merchant.limit.updated

outbox:
  scheduler:
    batch-size: 1000
    lock-key: lock:merchant-limit:outbox-publisher
    lock-ttl-seconds: 9

logging:
  level:
    com.example.merchantlimit: INFO
```

- [ ] **Step 4: DDL V1 작성**

```sql
-- src/main/resources/db/migration/V1__create_merchant_limit_core.sql

CREATE TABLE merchant
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_key       VARCHAR(64)  NOT NULL,
    name               VARCHAR(255) NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    cancel_period_days INT          NOT NULL DEFAULT 90,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_key (merchant_key)
);

CREATE TABLE merchant_cancel_limit
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT        NOT NULL,
    daily_limit DECIMAL(19,2) NOT NULL,
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_cancel_limit_merchant_id (merchant_id)
);

CREATE TABLE merchant_cancel_limit_history
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT        NOT NULL,
    old_limit   DECIMAL(19,2) NULL,
    new_limit   DECIMAL(19,2) NOT NULL,
    reason      VARCHAR(500)  NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    INDEX idx_limit_history_merchant_id (merchant_id)
);

CREATE TABLE limit_event_outbox
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    merchant_id  BIGINT      NOT NULL,
    payload      JSON        NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) NULL,

    PRIMARY KEY (id),
    INDEX idx_limit_outbox_status (status),
    INDEX idx_limit_outbox_status_created_at (status, created_at)
);
```

- [ ] **Step 5: 컴파일 확인**

```bash
./gradlew :merchant-limit-service:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add merchant-limit-service/
git commit -m "chore: merchant-limit-service 프로젝트 기반 설정"
```

---

### Task 2: 공통 예외 + Merchant 도메인 엔티티

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/common/exception/BusinessException.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/common/exception/ErrorCode.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/domain/entity/MerchantStatus.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/domain/entity/Merchant.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/domain/exception/MerchantSuspendedException.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/domain/entity/MerchantTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/example/merchantlimit/domain/entity/MerchantTest.java
package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.domain.exception.MerchantSuspendedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Merchant 도메인 엔티티")
class MerchantTest {

    @Test
    @DisplayName("ACTIVE 가맹점 생성")
    void create_active_merchant() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);

        assertThat(merchant.getMerchantKey()).isEqualTo("mct_001");
        assertThat(merchant.getName()).isEqualTo("패션몰A");
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.getCancelPeriodDays()).isEqualTo(90);
    }

    @Test
    @DisplayName("ACTIVE → INACTIVE 비활성화")
    void deactivate_active_merchant() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        merchant.deactivate();
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.INACTIVE);
    }

    @Test
    @DisplayName("ACTIVE → SUSPENDED 정지")
    void suspend_active_merchant() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        merchant.suspend();
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.SUSPENDED);
    }

    @Test
    @DisplayName("INACTIVE → ACTIVE 재활성화")
    void activate_inactive_merchant() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        merchant.deactivate();
        merchant.activate();
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
    }

    @Test
    @DisplayName("SUSPENDED 가맹점은 한도 변경 불가")
    void suspended_merchant_cannot_change_limit() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        merchant.suspend();
        assertThatThrownBy(merchant::validateLimitChangeable)
            .isInstanceOf(MerchantSuspendedException.class);
    }

    @Test
    @DisplayName("ACTIVE 가맹점은 한도 변경 가능")
    void active_merchant_can_change_limit() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        assertThatNoException().isThrownBy(merchant::validateLimitChangeable);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.domain.entity.MerchantTest"
```
Expected: FAIL (클래스 없음)

- [ ] **Step 3: 공통 예외 계층 구현**

```java
// src/main/java/com/example/merchantlimit/common/exception/ErrorCode.java
package com.example.merchantlimit.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 404
    MERCHANT_NOT_FOUND("MERCHANT_NOT_FOUND", 404, "가맹점 정보를 찾을 수 없습니다."),
    // 409
    MERCHANT_KEY_DUPLICATED("MERCHANT_KEY_DUPLICATED", 409, "이미 사용 중인 가맹점 키입니다."),
    // 422
    MERCHANT_CANCEL_LIMIT_NOT_FOUND("MERCHANT_CANCEL_LIMIT_NOT_FOUND", 422, "가맹점 취소한도가 설정되지 않았습니다."),
    INVALID_LIMIT_AMOUNT("INVALID_LIMIT_AMOUNT", 422, "한도는 1원 이상이어야 합니다."),
    MERCHANT_SUSPENDED("MERCHANT_SUSPENDED", 422, "정지된 가맹점의 한도를 변경할 수 없습니다."),
    // 500
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
```

```java
// src/main/java/com/example/merchantlimit/common/exception/BusinessException.java
package com.example.merchantlimit.common.exception;

import lombok.Getter;

@Getter
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
```

- [ ] **Step 4: MerchantStatus + Merchant + MerchantSuspendedException 구현**

```java
// src/main/java/com/example/merchantlimit/domain/entity/MerchantStatus.java
package com.example.merchantlimit.domain.entity;

public enum MerchantStatus {
    ACTIVE, INACTIVE, SUSPENDED
}
```

```java
// src/main/java/com/example/merchantlimit/domain/exception/MerchantSuspendedException.java
package com.example.merchantlimit.domain.exception;

import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;

public class MerchantSuspendedException extends BusinessException {
    public MerchantSuspendedException(long merchantId) {
        super(ErrorCode.MERCHANT_SUSPENDED,
            "정지된 가맹점의 한도를 변경할 수 없습니다. merchantId=" + merchantId);
    }
}
```

```java
// src/main/java/com/example/merchantlimit/domain/entity/Merchant.java
package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.domain.exception.MerchantSuspendedException;

public class Merchant {

    private Long id;
    private String merchantKey;
    private String name;
    private MerchantStatus status;
    private int cancelPeriodDays;

    private Merchant(String merchantKey, String name, int cancelPeriodDays) {
        this.merchantKey = merchantKey;
        this.name = name;
        this.status = MerchantStatus.ACTIVE;
        this.cancelPeriodDays = cancelPeriodDays;
    }

    public static Merchant create(String merchantKey, String name, int cancelPeriodDays) {
        return new Merchant(merchantKey, name, cancelPeriodDays);
    }

    public static Merchant reconstruct(Long id, String merchantKey, String name,
                                       MerchantStatus status, int cancelPeriodDays) {
        Merchant m = new Merchant(merchantKey, name, cancelPeriodDays);
        m.id = id;
        m.status = status;
        return m;
    }

    public void activate()   { this.status = MerchantStatus.ACTIVE; }
    public void deactivate() { this.status = MerchantStatus.INACTIVE; }
    public void suspend()    { this.status = MerchantStatus.SUSPENDED; }

    public void validateLimitChangeable() {
        if (this.status == MerchantStatus.SUSPENDED) {
            throw new MerchantSuspendedException(id != null ? id : 0L);
        }
    }

    public Long getId()              { return id; }
    public String getMerchantKey()   { return merchantKey; }
    public String getName()          { return name; }
    public MerchantStatus getStatus(){ return status; }
    public int getCancelPeriodDays() { return cancelPeriodDays; }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.domain.entity.MerchantTest"
```
Expected: BUILD SUCCESSFUL (6 tests passed)

- [ ] **Step 6: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): Merchant 도메인 엔티티 + 공통 예외 계층"
```

---

### Task 3: MerchantCancelLimit 도메인 엔티티

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/domain/entity/MerchantCancelLimit.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/domain/exception/InvalidLimitAmountException.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/domain/entity/LimitHistory.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/domain/entity/MerchantCancelLimitTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/example/merchantlimit/domain/entity/MerchantCancelLimitTest.java
package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.domain.exception.InvalidLimitAmountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MerchantCancelLimit 도메인 엔티티")
class MerchantCancelLimitTest {

    @Test
    @DisplayName("정상 한도로 생성")
    void create_with_valid_limit() {
        MerchantCancelLimit limit = MerchantCancelLimit.create(1L, BigDecimal.valueOf(5_000_000));

        assertThat(limit.getMerchantId()).isEqualTo(1L);
        assertThat(limit.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    @DisplayName("0원 한도 생성 시 예외")
    void create_with_zero_limit_throws() {
        assertThatThrownBy(() -> MerchantCancelLimit.create(1L, BigDecimal.ZERO))
            .isInstanceOf(InvalidLimitAmountException.class);
    }

    @Test
    @DisplayName("음수 한도 생성 시 예외")
    void create_with_negative_limit_throws() {
        assertThatThrownBy(() -> MerchantCancelLimit.create(1L, BigDecimal.valueOf(-1)))
            .isInstanceOf(InvalidLimitAmountException.class);
    }

    @Test
    @DisplayName("한도 변경 — 유효한 금액")
    void update_with_valid_limit() {
        MerchantCancelLimit limit = MerchantCancelLimit.create(1L, BigDecimal.valueOf(3_000_000));
        limit.update(BigDecimal.valueOf(5_000_000));
        assertThat(limit.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    @DisplayName("한도 변경 — 0원이면 예외")
    void update_with_zero_throws() {
        MerchantCancelLimit limit = MerchantCancelLimit.create(1L, BigDecimal.valueOf(3_000_000));
        assertThatThrownBy(() -> limit.update(BigDecimal.ZERO))
            .isInstanceOf(InvalidLimitAmountException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.domain.entity.MerchantCancelLimitTest"
```
Expected: FAIL

- [ ] **Step 3: InvalidLimitAmountException 구현**

```java
// src/main/java/com/example/merchantlimit/domain/exception/InvalidLimitAmountException.java
package com.example.merchantlimit.domain.exception;

import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;

import java.math.BigDecimal;

public class InvalidLimitAmountException extends BusinessException {
    public InvalidLimitAmountException(BigDecimal amount) {
        super(ErrorCode.INVALID_LIMIT_AMOUNT,
            "한도는 1원 이상이어야 합니다. 요청값=" + amount.toPlainString());
    }
}
```

- [ ] **Step 4: MerchantCancelLimit 구현**

```java
// src/main/java/com/example/merchantlimit/domain/entity/MerchantCancelLimit.java
package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.domain.exception.InvalidLimitAmountException;

import java.math.BigDecimal;

public class MerchantCancelLimit {

    private Long id;
    private Long merchantId;
    private BigDecimal dailyLimit;

    private MerchantCancelLimit(Long merchantId, BigDecimal dailyLimit) {
        validate(dailyLimit);
        this.merchantId = merchantId;
        this.dailyLimit = dailyLimit;
    }

    public static MerchantCancelLimit create(long merchantId, BigDecimal dailyLimit) {
        return new MerchantCancelLimit(merchantId, dailyLimit);
    }

    public static MerchantCancelLimit reconstruct(long id, long merchantId, BigDecimal dailyLimit) {
        MerchantCancelLimit l = new MerchantCancelLimit(merchantId, dailyLimit);
        l.id = id;
        return l;
    }

    public void update(BigDecimal newLimit) {
        validate(newLimit);
        this.dailyLimit = newLimit;
    }

    private static void validate(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidLimitAmountException(amount);
        }
    }

    public Long getId()              { return id; }
    public Long getMerchantId()      { return merchantId; }
    public BigDecimal getDailyLimit(){ return dailyLimit; }
}
```

- [ ] **Step 5: LimitHistory 구현 (단순 값 객체)**

```java
// src/main/java/com/example/merchantlimit/domain/entity/LimitHistory.java
package com.example.merchantlimit.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public class LimitHistory {

    private Long id;
    private Long merchantId;
    private BigDecimal oldLimit;   // null = 최초 설정
    private BigDecimal newLimit;
    private String reason;
    private Instant createdAt;

    private LimitHistory(Long merchantId, BigDecimal oldLimit,
                         BigDecimal newLimit, String reason) {
        this.merchantId = merchantId;
        this.oldLimit = oldLimit;
        this.newLimit = newLimit;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public static LimitHistory record(long merchantId, BigDecimal oldLimit,
                                      BigDecimal newLimit, String reason) {
        return new LimitHistory(merchantId, oldLimit, newLimit, reason);
    }

    public Long getId()              { return id; }
    public Long getMerchantId()      { return merchantId; }
    public BigDecimal getOldLimit()  { return oldLimit; }
    public BigDecimal getNewLimit()  { return newLimit; }
    public String getReason()        { return reason; }
    public Instant getCreatedAt()    { return createdAt; }
}
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.domain.entity.MerchantCancelLimitTest"
```
Expected: BUILD SUCCESSFUL (5 tests passed)

- [ ] **Step 7: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): MerchantCancelLimit + LimitHistory 도메인 엔티티"
```

---

### Task 4: MerchantLimitDomainService + fixture

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/domain/service/MerchantLimitDomainService.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/domain/exception/MerchantNotFoundException.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/domain/entity/MerchantLimitDomainServiceTest.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/fixture/MerchantFixture.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/fixture/MerchantCancelLimitFixture.java`

- [ ] **Step 1: fixture 작성**

```java
// src/test/java/com/example/merchantlimit/fixture/MerchantFixture.java
package com.example.merchantlimit.fixture;

import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantStatus;

public class MerchantFixture {

    public static Merchant active() {
        return Merchant.reconstruct(1L, "mct_001", "패션몰A", MerchantStatus.ACTIVE, 90);
    }

    public static Merchant suspended() {
        return Merchant.reconstruct(2L, "mct_002", "정지몰B", MerchantStatus.SUSPENDED, 90);
    }

    public static Merchant inactive() {
        return Merchant.reconstruct(3L, "mct_003", "비활성몰C", MerchantStatus.INACTIVE, 90);
    }
}
```

```java
// src/test/java/com/example/merchantlimit/fixture/MerchantCancelLimitFixture.java
package com.example.merchantlimit.fixture;

import com.example.merchantlimit.domain.entity.MerchantCancelLimit;

import java.math.BigDecimal;

public class MerchantCancelLimitFixture {

    public static MerchantCancelLimit of(long merchantId, long dailyLimit) {
        return MerchantCancelLimit.reconstruct(1L, merchantId, BigDecimal.valueOf(dailyLimit));
    }

    public static MerchantCancelLimit defaultLimit(long merchantId) {
        return of(merchantId, 5_000_000L);
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
// src/test/java/com/example/merchantlimit/domain/entity/MerchantLimitDomainServiceTest.java
package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.domain.exception.MerchantSuspendedException;
import com.example.merchantlimit.domain.exception.InvalidLimitAmountException;
import com.example.merchantlimit.domain.service.MerchantLimitDomainService;
import com.example.merchantlimit.fixture.MerchantCancelLimitFixture;
import com.example.merchantlimit.fixture.MerchantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MerchantLimitDomainService")
class MerchantLimitDomainServiceTest {

    private final MerchantLimitDomainService sut = new MerchantLimitDomainService();

    @Test
    @DisplayName("ACTIVE 가맹점 한도 변경 성공")
    void update_limit_for_active_merchant() {
        var merchant = MerchantFixture.active();
        var limit = MerchantCancelLimitFixture.defaultLimit(merchant.getId());

        sut.updateLimit(merchant, limit, BigDecimal.valueOf(8_000_000));

        assertThat(limit.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(8_000_000));
    }

    @Test
    @DisplayName("SUSPENDED 가맹점 한도 변경 시 예외")
    void update_limit_for_suspended_merchant_throws() {
        var merchant = MerchantFixture.suspended();
        var limit = MerchantCancelLimitFixture.defaultLimit(merchant.getId());

        assertThatThrownBy(() -> sut.updateLimit(merchant, limit, BigDecimal.valueOf(8_000_000)))
            .isInstanceOf(MerchantSuspendedException.class);
    }

    @Test
    @DisplayName("0원 한도로 변경 시 예외")
    void update_limit_to_zero_throws() {
        var merchant = MerchantFixture.active();
        var limit = MerchantCancelLimitFixture.defaultLimit(merchant.getId());

        assertThatThrownBy(() -> sut.updateLimit(merchant, limit, BigDecimal.ZERO))
            .isInstanceOf(InvalidLimitAmountException.class);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.domain.entity.MerchantLimitDomainServiceTest"
```
Expected: FAIL

- [ ] **Step 4: MerchantNotFoundException + MerchantLimitDomainService 구현**

```java
// src/main/java/com/example/merchantlimit/domain/exception/MerchantNotFoundException.java
package com.example.merchantlimit.domain.exception;

import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;

public class MerchantNotFoundException extends BusinessException {
    public MerchantNotFoundException(long merchantId) {
        super(ErrorCode.MERCHANT_NOT_FOUND,
            "가맹점 정보를 찾을 수 없습니다. merchantId=" + merchantId);
    }
}
```

```java
// src/main/java/com/example/merchantlimit/domain/service/MerchantLimitDomainService.java
package com.example.merchantlimit.domain.service;

import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;

import java.math.BigDecimal;

public class MerchantLimitDomainService {

    /**
     * 가맹점 상태 검증 후 한도 변경.
     * SUSPENDED → MerchantSuspendedException
     * 한도 0원 이하 → InvalidLimitAmountException
     */
    public void updateLimit(Merchant merchant, MerchantCancelLimit limit, BigDecimal newLimit) {
        merchant.validateLimitChangeable();
        limit.update(newLimit);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.domain.entity.MerchantLimitDomainServiceTest"
```
Expected: BUILD SUCCESSFUL (3 tests passed)

- [ ] **Step 6: 전체 도메인 테스트 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.domain.*"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): MerchantLimitDomainService + fixture"
```

---

### Task 5: Application 인터페이스 + GetCancelLimitService

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/application/interfaces/MerchantRepository.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/application/interfaces/MerchantCancelLimitRepository.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/application/interfaces/LimitHistoryRepository.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/application/interfaces/LimitEventOutboxRepository.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/application/usecase/GetCancelLimitUseCase.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/application/service/GetCancelLimitService.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/application/service/GetCancelLimitServiceTest.java`

- [ ] **Step 1: Application 인터페이스 작성**

```java
// src/main/java/com/example/merchantlimit/application/interfaces/MerchantRepository.java
package com.example.merchantlimit.application.interfaces;

import com.example.merchantlimit.domain.entity.Merchant;
import java.util.Optional;

public interface MerchantRepository {
    Merchant save(Merchant merchant);
    Optional<Merchant> findById(long id);
    Optional<Merchant> findByMerchantKey(String merchantKey);
    boolean existsByMerchantKey(String merchantKey);
}
```

```java
// src/main/java/com/example/merchantlimit/application/interfaces/MerchantCancelLimitRepository.java
package com.example.merchantlimit.application.interfaces;

import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import java.util.Optional;

public interface MerchantCancelLimitRepository {
    MerchantCancelLimit save(MerchantCancelLimit limit);
    Optional<MerchantCancelLimit> findByMerchantId(long merchantId);
}
```

```java
// src/main/java/com/example/merchantlimit/application/interfaces/LimitHistoryRepository.java
package com.example.merchantlimit.application.interfaces;

import com.example.merchantlimit.domain.entity.LimitHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LimitHistoryRepository {
    void save(LimitHistory history);
    Page<LimitHistory> findByMerchantId(long merchantId, Pageable pageable);
}
```

```java
// src/main/java/com/example/merchantlimit/application/interfaces/LimitEventOutboxRepository.java
package com.example.merchantlimit.application.interfaces;

import java.util.List;

public interface LimitEventOutboxRepository {
    void insertPending(long merchantId, String payload);
    List<PendingOutbox> findPendingBatch(int limit);
    void markPublished(long outboxId);

    record PendingOutbox(long id, long merchantId, String payload) {}
}
```

- [ ] **Step 2: GetCancelLimitUseCase + 실패하는 테스트 작성**

```java
// src/main/java/com/example/merchantlimit/application/usecase/GetCancelLimitUseCase.java
package com.example.merchantlimit.application.usecase;

import java.math.BigDecimal;

public interface GetCancelLimitUseCase {
    Result execute(long merchantId);

    record Result(long merchantId, BigDecimal dailyLimit, String merchantStatus) {}
}
```

```java
// src/test/java/com/example/merchantlimit/application/service/GetCancelLimitServiceTest.java
package com.example.merchantlimit.application.service;

import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.application.usecase.GetCancelLimitUseCase;
import com.example.merchantlimit.domain.exception.MerchantNotFoundException;
import com.example.merchantlimit.common.exception.ErrorCode;
import com.example.merchantlimit.fixture.MerchantCancelLimitFixture;
import com.example.merchantlimit.fixture.MerchantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCancelLimitService")
class GetCancelLimitServiceTest {

    @Mock MerchantRepository merchantRepository;
    @Mock MerchantCancelLimitRepository limitRepository;

    GetCancelLimitService sut;

    @BeforeEach
    void setUp() {
        sut = new GetCancelLimitService(merchantRepository, limitRepository);
    }

    @Test
    @DisplayName("정상 조회 — 한도 반환")
    void execute_returns_limit() {
        var merchant = MerchantFixture.active();
        var limit = MerchantCancelLimitFixture.defaultLimit(1L);
        when(merchantRepository.findById(1L)).thenReturn(Optional.of(merchant));
        when(limitRepository.findByMerchantId(1L)).thenReturn(Optional.of(limit));

        GetCancelLimitUseCase.Result result = sut.execute(1L);

        assertThat(result.merchantId()).isEqualTo(1L);
        assertThat(result.dailyLimit()).isEqualByComparingTo(limit.getDailyLimit());
        assertThat(result.merchantStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("가맹점 없으면 404")
    void execute_throws_when_merchant_not_found() {
        when(merchantRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.execute(99L))
            .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    @DisplayName("한도 미설정이면 422")
    void execute_throws_when_limit_not_found() {
        var merchant = MerchantFixture.active();
        when(merchantRepository.findById(1L)).thenReturn(Optional.of(merchant));
        when(limitRepository.findByMerchantId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.execute(1L))
            .isInstanceOf(com.example.merchantlimit.application.exception.MerchantCancelLimitNotFoundException.class);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.application.service.GetCancelLimitServiceTest"
```
Expected: FAIL

- [ ] **Step 4: MerchantCancelLimitNotFoundException + GetCancelLimitService 구현**

```java
// src/main/java/com/example/merchantlimit/application/exception/MerchantCancelLimitNotFoundException.java
package com.example.merchantlimit.application.exception;

import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;

public class MerchantCancelLimitNotFoundException extends BusinessException {
    public MerchantCancelLimitNotFoundException(long merchantId) {
        super(ErrorCode.MERCHANT_CANCEL_LIMIT_NOT_FOUND,
            "가맹점 취소한도가 설정되지 않았습니다. merchantId=" + merchantId);
    }
}
```

```java
// src/main/java/com/example/merchantlimit/application/service/GetCancelLimitService.java
package com.example.merchantlimit.application.service;

import com.example.merchantlimit.application.exception.MerchantCancelLimitNotFoundException;
import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.application.usecase.GetCancelLimitUseCase;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import com.example.merchantlimit.domain.exception.MerchantNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetCancelLimitService implements GetCancelLimitUseCase {

    private final MerchantRepository merchantRepository;
    private final MerchantCancelLimitRepository limitRepository;

    @Override
    @Transactional(readOnly = true)
    public Result execute(long merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        MerchantCancelLimit limit = limitRepository.findByMerchantId(merchantId)
            .orElseThrow(() -> new MerchantCancelLimitNotFoundException(merchantId));

        return new Result(merchantId, limit.getDailyLimit(), merchant.getStatus().name());
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.application.service.GetCancelLimitServiceTest"
```
Expected: BUILD SUCCESSFUL (3 tests passed)

- [ ] **Step 6: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): GetCancelLimitService + application 인터페이스"
```

---

### Task 6: UpdateCancelLimitService

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/application/usecase/UpdateCancelLimitUseCase.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/application/service/UpdateCancelLimitService.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/application/service/UpdateCancelLimitServiceTest.java`

- [ ] **Step 1: UpdateCancelLimitUseCase 정의**

```java
// src/main/java/com/example/merchantlimit/application/usecase/UpdateCancelLimitUseCase.java
package com.example.merchantlimit.application.usecase;

import java.math.BigDecimal;

public interface UpdateCancelLimitUseCase {
    Result execute(long merchantId, BigDecimal newLimit, String reason);

    record Result(long merchantId, BigDecimal dailyLimit) {}
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
// src/test/java/com/example/merchantlimit/application/service/UpdateCancelLimitServiceTest.java
package com.example.merchantlimit.application.service;

import com.example.merchantlimit.application.interfaces.*;
import com.example.merchantlimit.application.usecase.UpdateCancelLimitUseCase;
import com.example.merchantlimit.domain.exception.MerchantNotFoundException;
import com.example.merchantlimit.domain.exception.MerchantSuspendedException;
import com.example.merchantlimit.domain.service.MerchantLimitDomainService;
import com.example.merchantlimit.fixture.MerchantCancelLimitFixture;
import com.example.merchantlimit.fixture.MerchantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateCancelLimitService")
class UpdateCancelLimitServiceTest {

    @Mock MerchantRepository merchantRepository;
    @Mock MerchantCancelLimitRepository limitRepository;
    @Mock LimitHistoryRepository historyRepository;
    @Mock LimitEventOutboxRepository outboxRepository;

    UpdateCancelLimitService sut;

    @BeforeEach
    void setUp() {
        sut = new UpdateCancelLimitService(
            merchantRepository, limitRepository,
            historyRepository, outboxRepository,
            new MerchantLimitDomainService()
        );
    }

    @Test
    @DisplayName("한도 변경 성공 — outbox INSERT 확인")
    void execute_updates_limit_and_inserts_outbox() {
        var merchant = MerchantFixture.active();
        var limit = MerchantCancelLimitFixture.defaultLimit(1L);
        when(merchantRepository.findById(1L)).thenReturn(Optional.of(merchant));
        when(limitRepository.findByMerchantId(1L)).thenReturn(Optional.of(limit));
        when(limitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateCancelLimitUseCase.Result result =
            sut.execute(1L, BigDecimal.valueOf(8_000_000), "프로모션");

        assertThat(result.dailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(8_000_000));
        verify(historyRepository).save(any());
        verify(outboxRepository).insertPending(eq(1L), anyString());
    }

    @Test
    @DisplayName("한도 미설정이면 신규 생성 후 outbox INSERT")
    void execute_creates_new_limit_when_not_exists() {
        var merchant = MerchantFixture.active();
        when(merchantRepository.findById(1L)).thenReturn(Optional.of(merchant));
        when(limitRepository.findByMerchantId(1L)).thenReturn(Optional.empty());
        when(limitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sut.execute(1L, BigDecimal.valueOf(5_000_000), null);

        verify(limitRepository).save(any());
        verify(outboxRepository).insertPending(eq(1L), anyString());
    }

    @Test
    @DisplayName("가맹점 없으면 404")
    void execute_throws_when_merchant_not_found() {
        when(merchantRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sut.execute(99L, BigDecimal.valueOf(5_000_000), null))
            .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    @DisplayName("SUSPENDED 가맹점이면 422")
    void execute_throws_when_merchant_suspended() {
        var merchant = MerchantFixture.suspended();
        when(merchantRepository.findById(2L)).thenReturn(Optional.of(merchant));
        assertThatThrownBy(() -> sut.execute(2L, BigDecimal.valueOf(5_000_000), null))
            .isInstanceOf(MerchantSuspendedException.class);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.application.service.UpdateCancelLimitServiceTest"
```
Expected: FAIL

- [ ] **Step 4: UpdateCancelLimitService 구현**

```java
// src/main/java/com/example/merchantlimit/application/service/UpdateCancelLimitService.java
package com.example.merchantlimit.application.service;

import com.example.merchantlimit.application.interfaces.*;
import com.example.merchantlimit.application.usecase.UpdateCancelLimitUseCase;
import com.example.merchantlimit.domain.entity.LimitHistory;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import com.example.merchantlimit.domain.exception.MerchantNotFoundException;
import com.example.merchantlimit.domain.service.MerchantLimitDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class UpdateCancelLimitService implements UpdateCancelLimitUseCase {

    private final MerchantRepository merchantRepository;
    private final MerchantCancelLimitRepository limitRepository;
    private final LimitHistoryRepository historyRepository;
    private final LimitEventOutboxRepository outboxRepository;
    private final MerchantLimitDomainService domainService;

    @Override
    @Transactional
    public Result execute(long merchantId, BigDecimal newLimit, String reason) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        MerchantCancelLimit limit = limitRepository.findByMerchantId(merchantId)
            .orElse(null);

        BigDecimal oldLimit = limit != null ? limit.getDailyLimit() : null;

        if (limit == null) {
            // 최초 설정 — 도메인 검증 후 신규 생성
            merchant.validateLimitChangeable();
            limit = MerchantCancelLimit.create(merchantId, newLimit);
        } else {
            // 기존 한도 변경
            domainService.updateLimit(merchant, limit, newLimit);
        }

        MerchantCancelLimit saved = limitRepository.save(limit);

        historyRepository.save(LimitHistory.record(merchantId, oldLimit, newLimit, reason));

        String kstDate = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        String payload = buildPayload(merchantId, newLimit, kstDate);
        outboxRepository.insertPending(merchantId, payload);

        return new Result(merchantId, saved.getDailyLimit());
    }

    private String buildPayload(long merchantId, BigDecimal newLimit, String kstDate) {
        return String.format(
            "{\"merchantId\":%d,\"newLimit\":%s,\"kstDate\":\"%s\"}",
            merchantId, newLimit.toPlainString(), kstDate
        );
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.application.service.UpdateCancelLimitServiceTest"
```
Expected: BUILD SUCCESSFUL (4 tests passed)

- [ ] **Step 6: 전체 application 테스트 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.application.*" --tests "com.example.merchantlimit.domain.*"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): UpdateCancelLimitService (upsert + outbox + history)"
```

---

### Task 7: JPA 인프라 — Merchant + MerchantCancelLimit

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantJpaEntity.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantJpaRepository.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantRepositoryImpl.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantCancelLimitJpaEntity.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantCancelLimitJpaRepository.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantCancelLimitRepositoryImpl.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/config/PersistenceConfig.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/infrastructure/persistence/AbstractRepositoryTest.java`
- Create: `merchant-limit-service/src/test/java/com/example/merchantlimit/infrastructure/persistence/MerchantRepositoryImplTest.java`

- [ ] **Step 1: MerchantJpaEntity 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantJpaEntity.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchant",
    uniqueConstraints = @UniqueConstraint(name = "uk_merchant_key", columnNames = "merchant_key"))
public class MerchantJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_key", nullable = false, length = 64)
    private String merchantKey;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "cancel_period_days", nullable = false)
    private int cancelPeriodDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantJpaEntity() {}

    public static MerchantJpaEntity from(Merchant m) {
        MerchantJpaEntity e = new MerchantJpaEntity();
        e.id = m.getId();
        e.merchantKey = m.getMerchantKey();
        e.name = m.getName();
        e.status = m.getStatus().name();
        e.cancelPeriodDays = m.getCancelPeriodDays();
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    public Merchant toDomain() {
        return Merchant.reconstruct(id, merchantKey, name,
            MerchantStatus.valueOf(status), cancelPeriodDays);
    }

    public Long getId() { return id; }
    public String getMerchantKey() { return merchantKey; }
    public void setStatus(String status) { this.status = status; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 2: MerchantJpaRepository + MerchantRepositoryImpl 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantJpaRepository.java
package com.example.merchantlimit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface MerchantJpaRepository extends JpaRepository<MerchantJpaEntity, Long> {
    Optional<MerchantJpaEntity> findByMerchantKey(String merchantKey);
    boolean existsByMerchantKey(String merchantKey);
    Page<MerchantJpaEntity> findByStatus(String status, Pageable pageable);
}
```

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantRepositoryImpl.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.domain.entity.Merchant;

import java.util.Optional;

public class MerchantRepositoryImpl implements MerchantRepository {

    private final MerchantJpaRepository jpaRepository;

    public MerchantRepositoryImpl(MerchantJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Merchant save(Merchant merchant) {
        MerchantJpaEntity entity = MerchantJpaEntity.from(merchant);
        return jpaRepository.save(entity).toDomain();
    }

    @Override
    public Optional<Merchant> findById(long id) {
        return jpaRepository.findById(id).map(MerchantJpaEntity::toDomain);
    }

    @Override
    public Optional<Merchant> findByMerchantKey(String merchantKey) {
        return jpaRepository.findByMerchantKey(merchantKey).map(MerchantJpaEntity::toDomain);
    }

    @Override
    public boolean existsByMerchantKey(String merchantKey) {
        return jpaRepository.existsByMerchantKey(merchantKey);
    }
}
```

- [ ] **Step 3: MerchantCancelLimit JPA 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantCancelLimitJpaEntity.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "merchant_cancel_limit",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_merchant_cancel_limit_merchant_id",
        columnNames = "merchant_id"))
public class MerchantCancelLimitJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantCancelLimitJpaEntity() {}

    public static MerchantCancelLimitJpaEntity from(MerchantCancelLimit limit) {
        MerchantCancelLimitJpaEntity e = new MerchantCancelLimitJpaEntity();
        e.id = limit.getId();
        e.merchantId = limit.getMerchantId();
        e.dailyLimit = limit.getDailyLimit();
        e.updatedAt = Instant.now();
        return e;
    }

    public MerchantCancelLimit toDomain() {
        return MerchantCancelLimit.reconstruct(id, merchantId, dailyLimit);
    }

    public Long getId() { return id; }
}
```

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantCancelLimitJpaRepository.java
package com.example.merchantlimit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantCancelLimitJpaRepository
    extends JpaRepository<MerchantCancelLimitJpaEntity, Long> {
    Optional<MerchantCancelLimitJpaEntity> findByMerchantId(Long merchantId);
}
```

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/MerchantCancelLimitRepositoryImpl.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;

import java.util.Optional;

public class MerchantCancelLimitRepositoryImpl implements MerchantCancelLimitRepository {

    private final MerchantCancelLimitJpaRepository jpaRepository;

    public MerchantCancelLimitRepositoryImpl(MerchantCancelLimitJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MerchantCancelLimit save(MerchantCancelLimit limit) {
        return jpaRepository.save(MerchantCancelLimitJpaEntity.from(limit)).toDomain();
    }

    @Override
    public Optional<MerchantCancelLimit> findByMerchantId(long merchantId) {
        return jpaRepository.findByMerchantId(merchantId)
            .map(MerchantCancelLimitJpaEntity::toDomain);
    }
}
```

- [ ] **Step 4: PersistenceConfig 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/config/PersistenceConfig.java
package com.example.merchantlimit.infrastructure.config;

import com.example.merchantlimit.application.interfaces.*;
import com.example.merchantlimit.infrastructure.persistence.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.merchantlimit.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public MerchantRepository merchantRepository(MerchantJpaRepository jpa) {
        return new MerchantRepositoryImpl(jpa);
    }

    @Bean
    public MerchantCancelLimitRepository merchantCancelLimitRepository(
        MerchantCancelLimitJpaRepository jpa) {
        return new MerchantCancelLimitRepositoryImpl(jpa);
    }
}
```

- [ ] **Step 5: AbstractRepositoryTest + MerchantRepositoryImplTest 작성**

```java
// src/test/java/com/example/merchantlimit/infrastructure/persistence/AbstractRepositoryTest.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.infrastructure.config.PersistenceConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
@Import(PersistenceConfig.class)
public abstract class AbstractRepositoryTest {

    @Container
    public static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("merchant_limit_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
}
```

```java
// src/test/java/com/example/merchantlimit/infrastructure/persistence/MerchantRepositoryImplTest.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.domain.entity.MerchantStatus;
import com.example.merchantlimit.fixture.MerchantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

class MerchantRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired MerchantJpaRepository jpaRepository;

    @Test
    @DisplayName("가맹점 저장 후 merchantKey로 조회")
    void save_and_find_by_merchant_key() {
        jpaRepository.save(MerchantJpaEntity.from(MerchantFixture.active()));

        var found = jpaRepository.findByMerchantKey("mct_001");
        assertThat(found).isPresent();
        assertThat(found.get().getMerchantKey()).isEqualTo("mct_001");
    }

    @Test
    @DisplayName("merchantKey 중복 저장 시 DataIntegrityViolationException")
    void duplicate_merchant_key_throws() {
        jpaRepository.save(MerchantJpaEntity.from(MerchantFixture.active()));

        assertThatThrownBy(() ->
            jpaRepository.saveAndFlush(MerchantJpaEntity.from(MerchantFixture.active())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 merchantKey 조회 시 empty")
    void find_by_unknown_merchant_key_returns_empty() {
        assertThat(jpaRepository.findByMerchantKey("unknown")).isEmpty();
    }
}
```

- [ ] **Step 6: 컴파일 확인**

```bash
./gradlew :merchant-limit-service:compileJava compileTestJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): JPA 인프라 — Merchant + MerchantCancelLimit"
```

---

### Task 8: JPA 인프라 — LimitHistory + LimitEventOutbox

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitHistoryJpaEntity.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitHistoryJpaRepository.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitHistoryRepositoryImpl.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitEventOutboxJpaEntity.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitEventOutboxJpaRepository.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitEventOutboxRepositoryImpl.java`
- Modify: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/config/PersistenceConfig.java`

- [ ] **Step 1: LimitHistory JPA 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitHistoryJpaEntity.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.domain.entity.LimitHistory;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "merchant_cancel_limit_history",
    indexes = @Index(name = "idx_limit_history_merchant_id", columnList = "merchant_id"))
public class LimitHistoryJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "old_limit", precision = 19, scale = 2)
    private BigDecimal oldLimit;

    @Column(name = "new_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal newLimit;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LimitHistoryJpaEntity() {}

    public static LimitHistoryJpaEntity from(LimitHistory h) {
        LimitHistoryJpaEntity e = new LimitHistoryJpaEntity();
        e.merchantId = h.getMerchantId();
        e.oldLimit = h.getOldLimit();
        e.newLimit = h.getNewLimit();
        e.reason = h.getReason();
        e.createdAt = h.getCreatedAt();
        return e;
    }

    public LimitHistory toDomain() {
        LimitHistory h = LimitHistory.record(merchantId, oldLimit, newLimit, reason);
        return h;
    }

    public Long getId()             { return id; }
    public Long getMerchantId()     { return merchantId; }
    public BigDecimal getOldLimit() { return oldLimit; }
    public BigDecimal getNewLimit() { return newLimit; }
    public String getReason()       { return reason; }
    public Instant getCreatedAt()   { return createdAt; }
}
```

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitHistoryJpaRepository.java
package com.example.merchantlimit.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LimitHistoryJpaRepository
    extends JpaRepository<LimitHistoryJpaEntity, Long> {
    Page<LimitHistoryJpaEntity> findByMerchantIdOrderByCreatedAtDesc(
        Long merchantId, Pageable pageable);
}
```

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitHistoryRepositoryImpl.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.application.interfaces.LimitHistoryRepository;
import com.example.merchantlimit.domain.entity.LimitHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public class LimitHistoryRepositoryImpl implements LimitHistoryRepository {

    private final LimitHistoryJpaRepository jpaRepository;

    public LimitHistoryRepositoryImpl(LimitHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(LimitHistory history) {
        jpaRepository.save(LimitHistoryJpaEntity.from(history));
    }

    @Override
    public Page<LimitHistory> findByMerchantId(long merchantId, Pageable pageable) {
        return jpaRepository
            .findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
            .map(LimitHistoryJpaEntity::toDomain);
    }
}
```

- [ ] **Step 2: LimitEventOutbox JPA 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitEventOutboxJpaEntity.java
package com.example.merchantlimit.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "limit_event_outbox",
    indexes = {
        @Index(name = "idx_limit_outbox_status", columnList = "status"),
        @Index(name = "idx_limit_outbox_status_created_at", columnList = "status,created_at")
    })
public class LimitEventOutboxJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected LimitEventOutboxJpaEntity() {}

    public static LimitEventOutboxJpaEntity pending(long merchantId, String payload) {
        LimitEventOutboxJpaEntity e = new LimitEventOutboxJpaEntity();
        e.merchantId = merchantId;
        e.payload = payload;
        e.status = "PENDING";
        e.createdAt = Instant.now();
        return e;
    }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
    }

    public Long getId()         { return id; }
    public Long getMerchantId() { return merchantId; }
    public String getPayload()  { return payload; }
    public String getStatus()   { return status; }
}
```

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitEventOutboxJpaRepository.java
package com.example.merchantlimit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface LimitEventOutboxJpaRepository
    extends JpaRepository<LimitEventOutboxJpaEntity, Long> {
    List<LimitEventOutboxJpaEntity> findByStatusOrderByCreatedAtAsc(
        String status, Pageable pageable);
}
```

```java
// src/main/java/com/example/merchantlimit/infrastructure/persistence/LimitEventOutboxRepositoryImpl.java
package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LimitEventOutboxRepositoryImpl implements LimitEventOutboxRepository {

    private final LimitEventOutboxJpaRepository jpaRepository;

    public LimitEventOutboxRepositoryImpl(LimitEventOutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void insertPending(long merchantId, String payload) {
        jpaRepository.save(LimitEventOutboxJpaEntity.pending(merchantId, payload));
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        return jpaRepository
            .findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, limit))
            .stream()
            .map(e -> new PendingOutbox(e.getId(), e.getMerchantId(), e.getPayload()))
            .toList();
    }

    @Override
    public void markPublished(long outboxId) {
        jpaRepository.findById(outboxId).ifPresent(e -> {
            e.markPublished();
            jpaRepository.save(e);
        });
    }
}
```

- [ ] **Step 3: PersistenceConfig에 Bean 추가**

```java
// PersistenceConfig.java 에 아래 두 @Bean 추가

@Bean
public LimitHistoryRepository limitHistoryRepository(LimitHistoryJpaRepository jpa) {
    return new LimitHistoryRepositoryImpl(jpa);
}

@Bean
public LimitEventOutboxRepository limitEventOutboxRepository(
    LimitEventOutboxJpaRepository jpa) {
    return new LimitEventOutboxRepositoryImpl(jpa);
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew :merchant-limit-service:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): JPA 인프라 — LimitHistory + LimitEventOutbox"
```

---

### Task 9: Kafka Producer + Outbox 스케줄러

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/config/KafkaProducerConfig.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/config/RedisLockConfig.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/messaging/LimitEventKafkaProducer.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/messaging/OutboxPublisherScheduler.java`

- [ ] **Step 1: KafkaProducerConfig 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/config/KafkaProducerConfig.java
package com.example.merchantlimit.infrastructure.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
```

- [ ] **Step 2: RedisLockConfig 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/config/RedisLockConfig.java
package com.example.merchantlimit.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisLockConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
```

- [ ] **Step 3: LimitEventKafkaProducer 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/messaging/LimitEventKafkaProducer.java
package com.example.merchantlimit.infrastructure.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LimitEventKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topic.merchant-limit-updated}")
    private String topic;

    /**
     * merchantId를 파티션 키로 사용 — 같은 가맹점 이벤트 순서 보장
     */
    public void publish(long merchantId, String payload) {
        kafkaTemplate.send(topic, String.valueOf(merchantId), payload)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka 발행 실패. merchantId={}, payload={}", merchantId, payload, ex);
                    throw new RuntimeException("Kafka 발행 실패", ex);
                }
                log.debug("Kafka 발행 완료. merchantId={}, offset={}",
                    merchantId, result.getRecordMetadata().offset());
            });
    }
}
```

- [ ] **Step 4: OutboxPublisherScheduler 구현**

```java
// src/main/java/com/example/merchantlimit/infrastructure/messaging/OutboxPublisherScheduler.java
package com.example.merchantlimit.infrastructure.messaging;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final LimitEventOutboxRepository outboxRepository;
    private final LimitEventKafkaProducer kafkaProducer;
    private final StringRedisTemplate redisTemplate;

    @Value("${outbox.scheduler.batch-size:1000}")
    private int batchSize;

    @Value("${outbox.scheduler.lock-key}")
    private String lockKey;

    @Value("${outbox.scheduler.lock-ttl-seconds:9}")
    private long lockTtlSeconds;

    @Scheduled(fixedDelay = 10_000)
    public void publish() {
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "locked", Duration.ofSeconds(lockTtlSeconds));

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Outbox 스케줄러 락 획득 실패 — 다른 인스턴스가 실행 중");
            return;
        }

        try {
            List<LimitEventOutboxRepository.PendingOutbox> pending =
                outboxRepository.findPendingBatch(batchSize);

            for (LimitEventOutboxRepository.PendingOutbox outbox : pending) {
                try {
                    kafkaProducer.publish(outbox.merchantId(), outbox.payload());
                    outboxRepository.markPublished(outbox.id());
                } catch (Exception e) {
                    log.error("Outbox 발행 실패. outboxId={}", outbox.id(), e);
                }
            }

            if (!pending.isEmpty()) {
                log.info("Outbox 발행 완료. count={}", pending.size());
            }
        } finally {
            redisTemplate.delete(lockKey);
        }
    }
}
```

- [ ] **Step 5: 컴파일 확인**

```bash
./gradlew :merchant-limit-service:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): Kafka Producer + Outbox 스케줄러 (Redis 분산락)"
```

---

### Task 10: Internal API + Admin API + GlobalExceptionHandler

**Files:**
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/presentation/controller/InternalMerchantController.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/presentation/controller/AdminMerchantController.java`
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/presentation/dto/` (전체 DTO)
- Create: `merchant-limit-service/src/main/java/com/example/merchantlimit/presentation/GlobalExceptionHandler.java`

- [ ] **Step 1: DTO 작성**

```java
// src/main/java/com/example/merchantlimit/presentation/dto/CreateMerchantRequest.java
package com.example.merchantlimit.presentation.dto;

import jakarta.validation.constraints.*;

public record CreateMerchantRequest(
    @NotBlank @Size(max = 64) String merchantKey,
    @NotBlank @Size(max = 255) String name,
    @Min(1) @Max(365) int cancelPeriodDays
) {}
```

```java
// src/main/java/com/example/merchantlimit/presentation/dto/PatchMerchantStatusRequest.java
package com.example.merchantlimit.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record PatchMerchantStatusRequest(
    @NotNull String status
) {}
```

```java
// src/main/java/com/example/merchantlimit/presentation/dto/UpdateLimitRequest.java
package com.example.merchantlimit.presentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdateLimitRequest(
    @NotNull @DecimalMin("1") BigDecimal dailyLimit,
    String reason
) {}
```

```java
// src/main/java/com/example/merchantlimit/presentation/dto/MerchantResponse.java
package com.example.merchantlimit.presentation.dto;

import com.example.merchantlimit.domain.entity.Merchant;

public record MerchantResponse(
    long merchantId, String merchantKey, String name,
    String status, int cancelPeriodDays
) {
    public static MerchantResponse from(Merchant m) {
        return new MerchantResponse(
            m.getId(), m.getMerchantKey(), m.getName(),
            m.getStatus().name(), m.getCancelPeriodDays()
        );
    }
}
```

```java
// src/main/java/com/example/merchantlimit/presentation/dto/CancelLimitResponse.java
package com.example.merchantlimit.presentation.dto;

import java.math.BigDecimal;

public record CancelLimitResponse(
    long merchantId, BigDecimal dailyLimit, String merchantStatus
) {}
```

```java
// src/main/java/com/example/merchantlimit/presentation/dto/LimitHistoryPageResponse.java
package com.example.merchantlimit.presentation.dto;

import com.example.merchantlimit.domain.entity.LimitHistory;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LimitHistoryPageResponse(
    List<Item> content, long totalElements, int page, int size
) {
    public record Item(BigDecimal oldLimit, BigDecimal newLimit, String reason, Instant changedAt) {
        public static Item from(LimitHistory h) {
            return new Item(h.getOldLimit(), h.getNewLimit(), h.getReason(), h.getCreatedAt());
        }
    }

    public static LimitHistoryPageResponse from(Page<LimitHistory> page) {
        return new LimitHistoryPageResponse(
            page.getContent().stream().map(Item::from).toList(),
            page.getTotalElements(), page.getNumber(), page.getSize()
        );
    }
}
```

- [ ] **Step 2: InternalMerchantController 구현**

```java
// src/main/java/com/example/merchantlimit/presentation/controller/InternalMerchantController.java
package com.example.merchantlimit.presentation.controller;

import com.example.merchantlimit.application.usecase.GetCancelLimitUseCase;
import com.example.merchantlimit.presentation.dto.CancelLimitResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/merchants")
@RequiredArgsConstructor
public class InternalMerchantController {

    private final GetCancelLimitUseCase getCancelLimitUseCase;

    @GetMapping("/{merchantId}/cancel-limit")
    public ResponseEntity<CancelLimitResponse> getCancelLimit(
        @PathVariable long merchantId
    ) {
        GetCancelLimitUseCase.Result result = getCancelLimitUseCase.execute(merchantId);
        return ResponseEntity.ok(new CancelLimitResponse(
            result.merchantId(), result.dailyLimit(), result.merchantStatus()
        ));
    }
}
```

- [ ] **Step 3: AdminMerchantController 구현**

```java
// src/main/java/com/example/merchantlimit/presentation/controller/AdminMerchantController.java
package com.example.merchantlimit.presentation.controller;

import com.example.merchantlimit.application.interfaces.LimitHistoryRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.application.usecase.UpdateCancelLimitUseCase;
import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantStatus;
import com.example.merchantlimit.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/admin/merchants")
@RequiredArgsConstructor
public class AdminMerchantController {

    private final MerchantRepository merchantRepository;
    private final UpdateCancelLimitUseCase updateCancelLimitUseCase;
    private final LimitHistoryRepository limitHistoryRepository;

    @PostMapping
    public ResponseEntity<MerchantResponse> createMerchant(
        @RequestBody @Valid CreateMerchantRequest request
    ) {
        if (merchantRepository.existsByMerchantKey(request.merchantKey())) {
            throw new MerchantKeyDuplicatedException(request.merchantKey());
        }
        Merchant merchant = merchantRepository.save(
            Merchant.create(request.merchantKey(), request.name(), request.cancelPeriodDays())
        );
        return ResponseEntity
            .created(URI.create("/admin/merchants/" + merchant.getId()))
            .body(MerchantResponse.from(merchant));
    }

    @PatchMapping("/{merchantId}/status")
    public ResponseEntity<MerchantResponse> updateStatus(
        @PathVariable long merchantId,
        @RequestBody @Valid PatchMerchantStatusRequest request
    ) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new com.example.merchantlimit.domain.exception.MerchantNotFoundException(merchantId));

        MerchantStatus newStatus = MerchantStatus.valueOf(request.status());
        switch (newStatus) {
            case ACTIVE -> merchant.activate();
            case INACTIVE -> merchant.deactivate();
            case SUSPENDED -> merchant.suspend();
        }
        return ResponseEntity.ok(MerchantResponse.from(merchantRepository.save(merchant)));
    }

    @PutMapping("/{merchantId}/cancel-limit")
    public ResponseEntity<CancelLimitResponse> updateLimit(
        @PathVariable long merchantId,
        @RequestBody @Valid UpdateLimitRequest request
    ) {
        UpdateCancelLimitUseCase.Result result =
            updateCancelLimitUseCase.execute(merchantId, request.dailyLimit(), request.reason());
        return ResponseEntity.ok(new CancelLimitResponse(result.merchantId(), result.dailyLimit(), null));
    }

    @GetMapping("/{merchantId}/cancel-limit/history")
    public ResponseEntity<LimitHistoryPageResponse> getLimitHistory(
        @PathVariable long merchantId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var historyPage = limitHistoryRepository.findByMerchantId(
            merchantId, PageRequest.of(page, size));
        return ResponseEntity.ok(LimitHistoryPageResponse.from(historyPage));
    }

    @GetMapping
    public ResponseEntity<?> listMerchants(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        // 모든 가맹점 조회 (Spring Data Pageable 사용)
        var result = merchantRepository.findAll(PageRequest.of(page, size));
        return ResponseEntity.ok(result.map(MerchantResponse::from));
    }

    // merchantKey 중복 예외 (controller 내부 전용)
    static class MerchantKeyDuplicatedException extends BusinessException {
        MerchantKeyDuplicatedException(String key) {
            super(ErrorCode.MERCHANT_KEY_DUPLICATED, "이미 사용 중인 가맹점 키입니다. key=" + key);
        }
    }
}
```

- [ ] **Step 4: MerchantRepository에 findAll(Pageable) 추가**

```java
// application/interfaces/MerchantRepository.java 에 추가
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

Page<Merchant> findAll(Pageable pageable);
```

`MerchantRepositoryImpl.java`에도 구현 추가:
```java
@Override
public Page<Merchant> findAll(Pageable pageable) {
    return jpaRepository.findAll(pageable).map(MerchantJpaEntity::toDomain);
}
```

- [ ] **Step 5: GlobalExceptionHandler 구현**

```java
// src/main/java/com/example/merchantlimit/presentation/GlobalExceptionHandler.java
package com.example.merchantlimit.presentation;

import com.example.merchantlimit.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        log.warn("BusinessException: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(Map.of("code", e.getErrorCode().getCode(), "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().stream()
            .map(err -> err.getDefaultMessage())
            .findFirst().orElse("요청 형식이 올바르지 않습니다.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("code", "INVALID_REQUEST", "message", msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("code", "INTERNAL_ERROR", "message", "서버 오류가 발생했습니다."));
    }
}
```

- [ ] **Step 6: 전체 빌드 확인**

```bash
./gradlew :merchant-limit-service:build -x test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 단위 테스트 전체 확인**

```bash
./gradlew :merchant-limit-service:test --tests "com.example.merchantlimit.domain.*" --tests "com.example.merchantlimit.application.*"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add merchant-limit-service/src/
git commit -m "feat(merchant-limit): Presentation 레이어 — Internal + Admin API + ExceptionHandler"
```

---

### Task 11: PR 생성

- [ ] **Step 1: 브랜치 push**

```bash
git push -u origin feat/merchant-limit-service
```

- [ ] **Step 2: PR 생성**

```bash
gh pr create \
  --title "feat: merchant-limit-service 구현 (한도 관리 + Kafka Outbox)" \
  --body "..." \
  --base main
```
