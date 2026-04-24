# risk-management-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 가맹점별 일일 취소한도 소진 관리 서비스 구현 — validate-and-reserve / compensate / check Internal API 3개, `merchant.limit.updated` Kafka Consumer, Redis 분산락 동시성 제어, Resilience4j CircuitBreaker

**Architecture:** payment-service/merchant-limit-service와 동일한 Hexagonal 구조(domain → application → infrastructure → presentation). 도메인 레이어는 순수 Java. `ValidateAndReserveService`는 `TransactionTemplate`으로 프로그래매틱 TX 관리 — Redis 락 해제가 TX 커밋 이후에 일어나도록 보장. 스케줄러 없음.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Data JPA, MySQL 8.0, Flyway, Kafka 3.x(Consumer), Redis(분산락 + 캐시), Resilience4j(CircuitBreaker), JUnit 5 + Mockito + Testcontainers

---

## 파일 구조 (전체 생성 목록)

```
risk-management-service/
├── build.gradle
└── src/
    ├── main/
    │   ├── java/com/example/riskmanagement/
    │   │   ├── RiskManagementApplication.java
    │   │   ├── common/exception/
    │   │   │   ├── ErrorCode.java
    │   │   │   └── BusinessException.java
    │   │   ├── domain/
    │   │   │   ├── entity/
    │   │   │   │   ├── MerchantCancelUsage.java
    │   │   │   │   ├── CancelUsageHistory.java
    │   │   │   │   └── CancelUsageCompensation.java
    │   │   │   ├── service/
    │   │   │   │   └── CancelLimitDomainService.java
    │   │   │   └── exception/
    │   │   │       └── CancelLimitExceededException.java
    │   │   ├── application/
    │   │   │   ├── interfaces/
    │   │   │   │   ├── MerchantCancelUsageRepository.java
    │   │   │   │   ├── CancelUsageHistoryRepository.java
    │   │   │   │   ├── CancelUsageCompensationRepository.java
    │   │   │   │   ├── MerchantLimitClient.java
    │   │   │   │   └── DailyLimitCache.java
    │   │   │   ├── usecase/
    │   │   │   │   ├── ValidateAndReserveUseCase.java
    │   │   │   │   ├── CompensateUseCase.java
    │   │   │   │   └── CheckChargeUseCase.java
    │   │   │   ├── service/
    │   │   │   │   ├── ValidateAndReserveService.java
    │   │   │   │   ├── CompensateService.java
    │   │   │   │   └── CheckChargeService.java
    │   │   │   └── exception/
    │   │   │       └── ServiceUnavailableException.java
    │   │   ├── infrastructure/
    │   │   │   ├── persistence/
    │   │   │   │   ├── MerchantCancelUsageJpaEntity.java
    │   │   │   │   ├── MerchantCancelUsageJpaRepository.java
    │   │   │   │   ├── MerchantCancelUsageRepositoryImpl.java
    │   │   │   │   ├── CancelUsageHistoryJpaEntity.java
    │   │   │   │   ├── CancelUsageHistoryJpaRepository.java
    │   │   │   │   ├── CancelUsageHistoryRepositoryImpl.java
    │   │   │   │   ├── CancelUsageCompensationJpaEntity.java
    │   │   │   │   ├── CancelUsageCompensationJpaRepository.java
    │   │   │   │   └── CancelUsageCompensationRepositoryImpl.java
    │   │   │   ├── cache/
    │   │   │   │   └── RedisDailyLimitCache.java
    │   │   │   ├── http/
    │   │   │   │   ├── MerchantLimitRestClient.java
    │   │   │   │   ├── MerchantLimitResponse.java
    │   │   │   │   └── MerchantNotFoundException.java
    │   │   │   ├── messaging/
    │   │   │   │   ├── MerchantLimitUpdatedConsumer.java
    │   │   │   │   └── MerchantLimitUpdatedPayload.java
    │   │   │   └── config/
    │   │   │       ├── PersistenceConfig.java
    │   │   │       ├── KafkaConsumerConfig.java
    │   │   │       ├── RedisConfig.java
    │   │   │       └── ResilienceConfig.java
    │   │   └── presentation/
    │   │       ├── controller/
    │   │       │   └── InternalCancelLimitController.java
    │   │       ├── dto/
    │   │       │   ├── ValidateAndReserveRequest.java
    │   │       │   ├── ValidateAndReserveResponse.java
    │   │       │   ├── CompensateRequest.java
    │   │       │   ├── CompensateResponse.java
    │   │       │   └── CheckChargeResponse.java
    │   │       └── GlobalExceptionHandler.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           └── V1__create_risk_core.sql
    └── test/
        └── java/com/example/riskmanagement/
            ├── domain/entity/MerchantCancelUsageTest.java
            ├── domain/service/CancelLimitDomainServiceTest.java
            ├── application/service/
            │   ├── ValidateAndReserveServiceTest.java
            │   ├── CompensateServiceTest.java
            │   └── CheckChargeServiceTest.java
            ├── infrastructure/persistence/
            │   ├── AbstractRepositoryTest.java
            │   └── MerchantCancelUsageRepositoryImplTest.java
            └── presentation/InternalCancelLimitControllerTest.java
```

---

### Task 1: 프로젝트 셋업

**Files:**
- Create: `risk-management-service/build.gradle`
- Create: `risk-management-service/src/main/resources/application.yml`
- Create: `risk-management-service/src/main/resources/db/migration/V1__create_risk_core.sql`
- Create: `risk-management-service/src/main/java/com/example/riskmanagement/RiskManagementApplication.java`

- [ ] **Step 1: build.gradle 작성**

`risk-management-service/build.gradle`:
```groovy
// risk-management-service build configuration
// 부모 build.gradle의 subprojects 설정을 상속받음

dependencies {
    // Kafka Consumer
    implementation 'org.springframework.kafka:spring-kafka'

    // Redis (분산락 + 캐시)
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'

    // Resilience4j CircuitBreaker
    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
    implementation 'org.springframework.boot:spring-boot-starter-aop'
}
```

- [ ] **Step 2: application.yml 작성**

`risk-management-service/src/main/resources/application.yml`:
```yaml
server:
  port: 8083

spring:
  application:
    name: risk-management-service

  datasource:
    url: jdbc:mysql://localhost:3306/risk_management_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
    username: risk
    password: risk
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

  flyway:
    locations: classpath:db/migration
    baseline-on-migrate: false

  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: risk-management-service
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

  data:
    redis:
      host: localhost
      port: 6379

external:
  merchant-limit:
    base-url: http://localhost:8082

kafka:
  topic:
    merchant-limit-updated: merchant.limit.updated

risk:
  lock:
    ttl-seconds: 5

resilience4j:
  circuitbreaker:
    instances:
      merchant-limit:
        failureRateThreshold: 50
        slidingWindowSize: 10
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 5
        ignore-exceptions:
          - com.example.riskmanagement.infrastructure.http.MerchantNotFoundException

logging:
  level:
    com.example.riskmanagement: INFO
```

- [ ] **Step 3: DDL 작성**

`risk-management-service/src/main/resources/db/migration/V1__create_risk_core.sql`:
```sql
-- 가맹점 일일 소진 내역 (가맹점+날짜당 1행)
CREATE TABLE merchant_cancel_usage (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT        NOT NULL,
    kst_date    DATE          NOT NULL,
    daily_limit DECIMAL(19,2) NOT NULL,
    used_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_cancel_usage_merchant_id_kst_date (merchant_id, kst_date)
);

-- 차감 이력 (이중 차감 방어 — cancelRequestId UK)
CREATE TABLE cancel_usage_history (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    cancel_request_id VARCHAR(64)   NOT NULL,
    merchant_id       BIGINT        NOT NULL,
    kst_date          DATE          NOT NULL,
    cancel_amount     DECIMAL(19,2) NOT NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cancel_usage_history_cancel_request_id (cancel_request_id)
);

-- 보상 멱등성 (cancelRequestId UK)
CREATE TABLE cancel_usage_compensation (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    cancel_request_id VARCHAR(64)   NOT NULL,
    merchant_id       BIGINT        NOT NULL,
    restore_amount    DECIMAL(19,2) NOT NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cancel_usage_compensation_cancel_request_id (cancel_request_id)
);
```

- [ ] **Step 4: Application 클래스 작성**

`risk-management-service/src/main/java/com/example/riskmanagement/RiskManagementApplication.java`:
```java
package com.example.riskmanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RiskManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskManagementApplication.class, args);
    }
}
```

- [ ] **Step 5: 빌드 확인**

```bash
./gradlew :risk-management-service:build -x test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add risk-management-service/
git commit -m "feat(risk): 프로젝트 셋업 — build.gradle, application.yml, DDL, Application"
```

---

### Task 2: Common + Domain 레이어

**Files:**
- Create: `risk-management-service/src/main/java/com/example/riskmanagement/common/exception/ErrorCode.java`
- Create: `risk-management-service/src/main/java/com/example/riskmanagement/common/exception/BusinessException.java`
- Create: `risk-management-service/src/main/java/com/example/riskmanagement/domain/entity/MerchantCancelUsage.java`
- Create: `risk-management-service/src/main/java/com/example/riskmanagement/domain/entity/CancelUsageHistory.java`
- Create: `risk-management-service/src/main/java/com/example/riskmanagement/domain/entity/CancelUsageCompensation.java`
- Create: `risk-management-service/src/main/java/com/example/riskmanagement/domain/exception/CancelLimitExceededException.java`
- Create: `risk-management-service/src/main/java/com/example/riskmanagement/domain/service/CancelLimitDomainService.java`
- Test: `risk-management-service/src/test/java/com/example/riskmanagement/domain/entity/MerchantCancelUsageTest.java`
- Test: `risk-management-service/src/test/java/com/example/riskmanagement/domain/service/CancelLimitDomainServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/riskmanagement/domain/entity/MerchantCancelUsageTest.java`:
```java
package com.example.riskmanagement.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MerchantCancelUsage")
class MerchantCancelUsageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);

    @Test
    @DisplayName("deduct — used_amount 증가")
    void deduct_increases_usedAmount() {
        MerchantCancelUsage usage = MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000));
        usage.deduct(BigDecimal.valueOf(300_000));
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.valueOf(300_000));
        assertThat(usage.remaining()).isEqualByComparingTo(BigDecimal.valueOf(4_700_000));
    }

    @Test
    @DisplayName("restore — used_amount 감소")
    void restore_decreases_usedAmount() {
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(300_000));
        usage.restore(BigDecimal.valueOf(300_000));
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("restore — 음수 방지 (0 이하로 내려가지 않음)")
    void restore_does_not_go_below_zero() {
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(100_000));
        usage.restore(BigDecimal.valueOf(500_000)); // 복원액 > 소진액
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("updateDailyLimit — daily_limit 변경")
    void updateDailyLimit_changes_limit() {
        MerchantCancelUsage usage = MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000));
        usage.updateDailyLimit(BigDecimal.valueOf(8_000_000));
        assertThat(usage.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(8_000_000));
    }
}
```

`src/test/java/com/example/riskmanagement/domain/service/CancelLimitDomainServiceTest.java`:
```java
package com.example.riskmanagement.domain.service;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.exception.CancelLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CancelLimitDomainService")
class CancelLimitDomainServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private final CancelLimitDomainService sut = new CancelLimitDomainService();

    @Test
    @DisplayName("validateAndDeduct — 한도 내이면 차감 성공")
    void validateAndDeduct_within_limit_succeeds() {
        MerchantCancelUsage usage = MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000));
        sut.validateAndDeduct(usage, BigDecimal.valueOf(300_000));
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.valueOf(300_000));
    }

    @Test
    @DisplayName("validateAndDeduct — 한도 초과 시 CancelLimitExceededException")
    void validateAndDeduct_over_limit_throws() {
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(4_800_000));
        assertThatThrownBy(() -> sut.validateAndDeduct(usage, BigDecimal.valueOf(300_000)))
            .isInstanceOf(CancelLimitExceededException.class);
    }

    @Test
    @DisplayName("applyCompensation — used_amount 복원")
    void applyCompensation_restores_usedAmount() {
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(300_000));
        sut.applyCompensation(usage, BigDecimal.valueOf(300_000));
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
```

- [ ] **Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew :risk-management-service:test \
  --tests "com.example.riskmanagement.domain.*" 2>&1 | tail -20
```
Expected: FAIL (클래스 없음)

- [ ] **Step 3: ErrorCode + BusinessException 작성**

`src/main/java/com/example/riskmanagement/common/exception/ErrorCode.java`:
```java
package com.example.riskmanagement.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    CANCEL_LIMIT_EXCEEDED("CANCEL_LIMIT_EXCEEDED", 422, "가맹점 일일 취소한도를 초과했습니다."),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", 503, "일시적 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
```

`src/main/java/com/example/riskmanagement/common/exception/BusinessException.java`:
```java
package com.example.riskmanagement.common.exception;

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

- [ ] **Step 4: 도메인 엔티티 작성**

`src/main/java/com/example/riskmanagement/domain/entity/MerchantCancelUsage.java`:
```java
package com.example.riskmanagement.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public class MerchantCancelUsage {

    private Long id;
    private Long merchantId;
    private LocalDate kstDate;
    private BigDecimal dailyLimit;
    private BigDecimal usedAmount;

    private MerchantCancelUsage() {}

    public static MerchantCancelUsage create(long merchantId, LocalDate kstDate, BigDecimal dailyLimit) {
        MerchantCancelUsage u = new MerchantCancelUsage();
        u.merchantId = merchantId;
        u.kstDate = kstDate;
        u.dailyLimit = dailyLimit;
        u.usedAmount = BigDecimal.ZERO;
        return u;
    }

    public static MerchantCancelUsage reconstruct(
        Long id, long merchantId, LocalDate kstDate, BigDecimal dailyLimit, BigDecimal usedAmount) {
        MerchantCancelUsage u = new MerchantCancelUsage();
        u.id = id;
        u.merchantId = merchantId;
        u.kstDate = kstDate;
        u.dailyLimit = dailyLimit;
        u.usedAmount = usedAmount;
        return u;
    }

    public void deduct(BigDecimal amount) {
        this.usedAmount = this.usedAmount.add(amount);
    }

    public void restore(BigDecimal amount) {
        this.usedAmount = this.usedAmount.subtract(amount).max(BigDecimal.ZERO);
    }

    public void updateDailyLimit(BigDecimal newLimit) {
        this.dailyLimit = newLimit;
    }

    public BigDecimal remaining() {
        return dailyLimit.subtract(usedAmount);
    }

    public Long getId()               { return id; }
    public Long getMerchantId()       { return merchantId; }
    public LocalDate getKstDate()     { return kstDate; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public BigDecimal getUsedAmount() { return usedAmount; }
}
```

`src/main/java/com/example/riskmanagement/domain/entity/CancelUsageHistory.java`:
```java
package com.example.riskmanagement.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CancelUsageHistory {

    private Long id;
    private String cancelRequestId;
    private Long merchantId;
    private LocalDate kstDate;
    private BigDecimal cancelAmount;

    private CancelUsageHistory() {}

    public static CancelUsageHistory record(
        String cancelRequestId, long merchantId, LocalDate kstDate, BigDecimal cancelAmount) {
        CancelUsageHistory h = new CancelUsageHistory();
        h.cancelRequestId = cancelRequestId;
        h.merchantId = merchantId;
        h.kstDate = kstDate;
        h.cancelAmount = cancelAmount;
        return h;
    }

    public static CancelUsageHistory reconstruct(
        Long id, String cancelRequestId, Long merchantId, LocalDate kstDate, BigDecimal cancelAmount) {
        CancelUsageHistory h = new CancelUsageHistory();
        h.id = id;
        h.cancelRequestId = cancelRequestId;
        h.merchantId = merchantId;
        h.kstDate = kstDate;
        h.cancelAmount = cancelAmount;
        return h;
    }

    public Long getId()                   { return id; }
    public String getCancelRequestId()    { return cancelRequestId; }
    public Long getMerchantId()           { return merchantId; }
    public LocalDate getKstDate()         { return kstDate; }
    public BigDecimal getCancelAmount()   { return cancelAmount; }
}
```

`src/main/java/com/example/riskmanagement/domain/entity/CancelUsageCompensation.java`:
```java
package com.example.riskmanagement.domain.entity;

import java.math.BigDecimal;

public class CancelUsageCompensation {

    private Long id;
    private String cancelRequestId;
    private Long merchantId;
    private BigDecimal restoreAmount;

    private CancelUsageCompensation() {}

    public static CancelUsageCompensation record(
        String cancelRequestId, long merchantId, BigDecimal restoreAmount) {
        CancelUsageCompensation c = new CancelUsageCompensation();
        c.cancelRequestId = cancelRequestId;
        c.merchantId = merchantId;
        c.restoreAmount = restoreAmount;
        return c;
    }

    public Long getId()                   { return id; }
    public String getCancelRequestId()    { return cancelRequestId; }
    public Long getMerchantId()           { return merchantId; }
    public BigDecimal getRestoreAmount()  { return restoreAmount; }
}
```

- [ ] **Step 5: 예외 + 도메인 서비스 작성**

`src/main/java/com/example/riskmanagement/domain/exception/CancelLimitExceededException.java`:
```java
package com.example.riskmanagement.domain.exception;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class CancelLimitExceededException extends BusinessException {

    private final BigDecimal dailyLimit;
    private final BigDecimal usedAmount;
    private final BigDecimal requestAmount;

    public CancelLimitExceededException(
        BigDecimal dailyLimit, BigDecimal usedAmount, BigDecimal requestAmount) {
        super(ErrorCode.CANCEL_LIMIT_EXCEEDED);
        this.dailyLimit = dailyLimit;
        this.usedAmount = usedAmount;
        this.requestAmount = requestAmount;
    }
}
```

`src/main/java/com/example/riskmanagement/domain/service/CancelLimitDomainService.java`:
```java
package com.example.riskmanagement.domain.service;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.exception.CancelLimitExceededException;

import java.math.BigDecimal;

public class CancelLimitDomainService {

    public void validateAndDeduct(MerchantCancelUsage usage, BigDecimal cancelAmount) {
        if (usage.remaining().compareTo(cancelAmount) < 0)
            throw new CancelLimitExceededException(
                usage.getDailyLimit(), usage.getUsedAmount(), cancelAmount);
        usage.deduct(cancelAmount);
    }

    public void applyCompensation(MerchantCancelUsage usage, BigDecimal restoreAmount) {
        usage.restore(restoreAmount);
    }
}
```

- [ ] **Step 6: 테스트 실행 — PASS 확인**

```bash
./gradlew :risk-management-service:test \
  --tests "com.example.riskmanagement.domain.*"
```
Expected: 7 tests PASS

- [ ] **Step 7: Commit**

```bash
git add risk-management-service/src/
git commit -m "feat(risk): Common + Domain 레이어 — 엔티티, 예외, 도메인 서비스"
```

---

### Task 3: Application 인터페이스 + UseCase 인터페이스

**Files:**
- Create: `application/interfaces/MerchantCancelUsageRepository.java`
- Create: `application/interfaces/CancelUsageHistoryRepository.java`
- Create: `application/interfaces/CancelUsageCompensationRepository.java`
- Create: `application/interfaces/MerchantLimitClient.java`
- Create: `application/interfaces/DailyLimitCache.java`
- Create: `application/usecase/ValidateAndReserveUseCase.java`
- Create: `application/usecase/CompensateUseCase.java`
- Create: `application/usecase/CheckChargeUseCase.java`
- Create: `application/exception/ServiceUnavailableException.java`

- [ ] **Step 1: Repository 인터페이스 작성**

`src/main/java/com/example/riskmanagement/application/interfaces/MerchantCancelUsageRepository.java`:
```java
package com.example.riskmanagement.application.interfaces;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;

import java.time.LocalDate;
import java.util.Optional;

public interface MerchantCancelUsageRepository {
    MerchantCancelUsage save(MerchantCancelUsage usage);
    Optional<MerchantCancelUsage> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate);
}
```

`src/main/java/com/example/riskmanagement/application/interfaces/CancelUsageHistoryRepository.java`:
```java
package com.example.riskmanagement.application.interfaces;

import com.example.riskmanagement.domain.entity.CancelUsageHistory;

import java.util.Optional;

public interface CancelUsageHistoryRepository {
    CancelUsageHistory save(CancelUsageHistory history);
    Optional<CancelUsageHistory> findByCancelRequestId(String cancelRequestId);
}
```

`src/main/java/com/example/riskmanagement/application/interfaces/CancelUsageCompensationRepository.java`:
```java
package com.example.riskmanagement.application.interfaces;

import com.example.riskmanagement.domain.entity.CancelUsageCompensation;

public interface CancelUsageCompensationRepository {
    CancelUsageCompensation save(CancelUsageCompensation compensation);
    boolean existsByCancelRequestId(String cancelRequestId);
}
```

`src/main/java/com/example/riskmanagement/application/interfaces/MerchantLimitClient.java`:
```java
package com.example.riskmanagement.application.interfaces;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface MerchantLimitClient {
    /** merchant-limit-service HTTP 호출. 실패 시 ServiceUnavailableException. */
    BigDecimal fetchDailyLimit(long merchantId, LocalDate kstDate);
}
```

`src/main/java/com/example/riskmanagement/application/interfaces/DailyLimitCache.java`:
```java
package com.example.riskmanagement.application.interfaces;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface DailyLimitCache {
    Optional<BigDecimal> get(long merchantId, LocalDate kstDate);
    void set(long merchantId, LocalDate kstDate, BigDecimal limit);
}
```

- [ ] **Step 2: UseCase 인터페이스 작성**

`src/main/java/com/example/riskmanagement/application/usecase/ValidateAndReserveUseCase.java`:
```java
package com.example.riskmanagement.application.usecase;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface ValidateAndReserveUseCase {
    record Command(long merchantId, String cancelRequestId, BigDecimal cancelAmount, LocalDate kstDate) {}
    record Result(long merchantId, BigDecimal dailyLimit, BigDecimal usedAmount, BigDecimal remainingLimit) {}
    Result execute(Command command);
}
```

`src/main/java/com/example/riskmanagement/application/usecase/CompensateUseCase.java`:
```java
package com.example.riskmanagement.application.usecase;

import java.math.BigDecimal;

public interface CompensateUseCase {
    record Command(String cancelRequestId, long merchantId, BigDecimal restoreAmount) {}
    record Result(String cancelRequestId, boolean restored, String reason) {}
    Result execute(Command command);
}
```

`src/main/java/com/example/riskmanagement/application/usecase/CheckChargeUseCase.java`:
```java
package com.example.riskmanagement.application.usecase;

import java.math.BigDecimal;

public interface CheckChargeUseCase {
    record Result(String cancelRequestId, boolean charged, Long merchantId, BigDecimal cancelAmount) {}
    Result execute(String cancelRequestId);
}
```

- [ ] **Step 3: ServiceUnavailableException 작성**

`src/main/java/com/example/riskmanagement/application/exception/ServiceUnavailableException.java`:
```java
package com.example.riskmanagement.application.exception;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;

public class ServiceUnavailableException extends BusinessException {
    public ServiceUnavailableException() {
        super(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
```

- [ ] **Step 4: 빌드 확인 (컴파일 오류 없음)**

```bash
./gradlew :risk-management-service:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add risk-management-service/src/
git commit -m "feat(risk): Application 인터페이스 + UseCase 인터페이스"
```

---

### Task 4: Application Services (단위 테스트)

**Files:**
- Create: `application/service/ValidateAndReserveService.java`
- Create: `application/service/CompensateService.java`
- Create: `application/service/CheckChargeService.java`
- Test: `application/service/ValidateAndReserveServiceTest.java`
- Test: `application/service/CompensateServiceTest.java`
- Test: `application/service/CheckChargeServiceTest.java`

> **핵심 설계:** `ValidateAndReserveService`는 Redis 락과 TX 순서를 보장하기 위해 `TransactionTemplate`을 사용한다. `transactionTemplate.execute()` 완료(TX 커밋) 후 finally 블록에서 Redis 락을 해제하므로 락 해제 전에 커밋이 완료된다.
>
> **daily_limit 3단계 조회 순서:**
> 1. Redis: `daily_limit:{merchantId}:{kstDate}`
> 2. DB 스냅샷: `merchant_cancel_usage.daily_limit` (이미 조회한 usageOpt 재사용)
> 3. HTTP: `merchantLimitClient.fetchDailyLimit()` (Resilience4j CB 적용)

- [ ] **Step 1: ValidateAndReserveService 테스트 작성**

`src/test/java/com/example/riskmanagement/application/service/ValidateAndReserveServiceTest.java`:
```java
package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.exception.ServiceUnavailableException;
import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.exception.CancelLimitExceededException;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ValidateAndReserveService")
class ValidateAndReserveServiceTest {

    @Mock MerchantCancelUsageRepository usageRepository;
    @Mock CancelUsageHistoryRepository historyRepository;
    @Mock MerchantLimitClient merchantLimitClient;
    @Mock DailyLimitCache dailyLimitCache;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    ValidateAndReserveService sut;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private static final BigDecimal DAILY_LIMIT = BigDecimal.valueOf(5_000_000);
    private static final BigDecimal CANCEL_AMOUNT = BigDecimal.valueOf(300_000);

    @BeforeEach
    void setUp() {
        // TransactionTemplate은 실제 Spring TX 없이 콜백을 그냥 실행하는 stub으로 대체
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        sut = new ValidateAndReserveService(
            usageRepository, historyRepository,
            merchantLimitClient, dailyLimitCache,
            new CancelLimitDomainService(), redisTemplate, txTemplate);
    }

    @Test
    @DisplayName("Redis hit — DB/HTTP 미호출, 차감 성공")
    void execute_redis_hit_skips_db_and_http() {
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.of(DAILY_LIMIT));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.empty());
        when(usageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());

        ValidateAndReserveUseCase.Result result = sut.execute(
            new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY));

        assertThat(result.remainingLimit()).isEqualByComparingTo(BigDecimal.valueOf(4_700_000));
        verify(merchantLimitClient, never()).fetchDailyLimit(anyLong(), any());
        verify(usageRepository).save(any());
        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("Redis miss, DB 스냅샷 hit — HTTP 미호출")
    void execute_db_snapshot_hit_skips_http() {
        MerchantCancelUsage existing = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, DAILY_LIMIT, BigDecimal.ZERO);
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.empty());
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(existing));
        when(usageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());

        sut.execute(new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY));

        verify(merchantLimitClient, never()).fetchDailyLimit(anyLong(), any());
    }

    @Test
    @DisplayName("Redis miss, DB miss → HTTP 호출")
    void execute_calls_http_when_no_cache_and_no_snapshot() {
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.empty());
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.empty());
        when(merchantLimitClient.fetchDailyLimit(1L, TODAY)).thenReturn(DAILY_LIMIT);
        when(usageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());

        sut.execute(new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY));

        verify(merchantLimitClient).fetchDailyLimit(1L, TODAY);
    }

    @Test
    @DisplayName("이중 차감 방어 — cancelRequestId 이미 있으면 no-op")
    void execute_returns_noop_when_already_charged() {
        CancelUsageHistory existing = CancelUsageHistory.record("cr_001", 1L, TODAY, CANCEL_AMOUNT);
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, DAILY_LIMIT, CANCEL_AMOUNT);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(existing));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(usage));

        ValidateAndReserveUseCase.Result result = sut.execute(
            new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY));

        assertThat(result.usedAmount()).isEqualByComparingTo(CANCEL_AMOUNT);
        verify(usageRepository, never()).save(any()); // 재차감 없음
    }

    @Test
    @DisplayName("한도 초과 — CancelLimitExceededException")
    void execute_throws_when_limit_exceeded() {
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.of(DAILY_LIMIT));
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());
        MerchantCancelUsage full = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, DAILY_LIMIT, BigDecimal.valueOf(4_800_000));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(full));

        assertThatThrownBy(() -> sut.execute(
            new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY)))
            .isInstanceOf(CancelLimitExceededException.class);
    }
}
```

- [ ] **Step 2: CompensateService + CheckChargeService 테스트 작성**

`src/test/java/com/example/riskmanagement/application/service/CompensateServiceTest.java`:
```java
package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompensateService")
class CompensateServiceTest {

    @Mock CancelUsageCompensationRepository compensationRepository;
    @Mock CancelUsageHistoryRepository historyRepository;
    @Mock MerchantCancelUsageRepository usageRepository;

    CompensateService sut;
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);

    @BeforeEach
    void setUp() {
        sut = new CompensateService(compensationRepository, historyRepository, usageRepository,
            new CancelLimitDomainService());
    }

    @Test
    @DisplayName("이미 보상된 경우 ALREADY_COMPENSATED 반환")
    void execute_returns_already_compensated_when_duplicate() {
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(true);

        CompensateUseCase.Result result = sut.execute(
            new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000)));

        assertThat(result.restored()).isFalse();
        assertThat(result.reason()).isEqualTo("ALREADY_COMPENSATED");
        verify(usageRepository, never()).save(any());
    }

    @Test
    @DisplayName("차감 이력 없는 경우 NOT_CHARGED 반환")
    void execute_returns_not_charged_when_no_history() {
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(false);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.empty());

        CompensateUseCase.Result result = sut.execute(
            new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000)));

        assertThat(result.restored()).isFalse();
        assertThat(result.reason()).isEqualTo("NOT_CHARGED");
    }

    @Test
    @DisplayName("보상 성공 — used_amount 복원 + compensation INSERT")
    void execute_restores_and_inserts_compensation() {
        CancelUsageHistory history = CancelUsageHistory.record("cr_001", 1L, TODAY, BigDecimal.valueOf(300_000));
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(300_000));
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(false);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(history));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(usage));
        when(usageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompensateUseCase.Result result = sut.execute(
            new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000)));

        assertThat(result.restored()).isTrue();
        assertThat(result.reason()).isNull();
        verify(compensationRepository).save(any());
    }
}
```

`src/test/java/com/example/riskmanagement/application/service/CheckChargeServiceTest.java`:
```java
package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.CancelUsageHistoryRepository;
import com.example.riskmanagement.application.usecase.CheckChargeUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CheckChargeService")
class CheckChargeServiceTest {

    @Mock CancelUsageHistoryRepository historyRepository;
    @InjectMocks CheckChargeService sut;

    @Test
    @DisplayName("차감된 경우 charged=true 반환")
    void execute_returns_charged_true_when_history_exists() {
        CancelUsageHistory history = CancelUsageHistory.record(
            "cr_001", 1L, LocalDate.of(2026, 4, 23), BigDecimal.valueOf(300_000));
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(history));

        CheckChargeUseCase.Result result = sut.execute("cr_001");

        assertThat(result.charged()).isTrue();
        assertThat(result.cancelAmount()).isEqualByComparingTo(BigDecimal.valueOf(300_000));
    }

    @Test
    @DisplayName("차감 없는 경우 charged=false 반환")
    void execute_returns_charged_false_when_no_history() {
        when(historyRepository.findByCancelRequestId("cr_999")).thenReturn(Optional.empty());

        CheckChargeUseCase.Result result = sut.execute("cr_999");

        assertThat(result.charged()).isFalse();
        assertThat(result.merchantId()).isNull();
    }
}
```

- [ ] **Step 3: 테스트 실행 — FAIL 확인**

```bash
./gradlew :risk-management-service:test \
  --tests "com.example.riskmanagement.application.*" 2>&1 | tail -20
```
Expected: FAIL (서비스 클래스 없음)

- [ ] **Step 4: ValidateAndReserveService 구현**

`src/main/java/com/example/riskmanagement/application/service/ValidateAndReserveService.java`:
```java
package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.exception.ServiceUnavailableException;
import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ValidateAndReserveService implements ValidateAndReserveUseCase {

    private final MerchantCancelUsageRepository usageRepository;
    private final CancelUsageHistoryRepository historyRepository;
    private final MerchantLimitClient merchantLimitClient;
    private final DailyLimitCache dailyLimitCache;
    private final CancelLimitDomainService domainService;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${risk.lock.ttl-seconds:5}")
    private long lockTtlSeconds;

    @Override
    public Result execute(Command cmd) {
        String lockKey = "lock:risk:merchant:" + cmd.merchantId();
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "locked", Duration.ofSeconds(lockTtlSeconds));
        if (!Boolean.TRUE.equals(acquired)) throw new ServiceUnavailableException();

        try {
            // transactionTemplate.execute() 완료(TX 커밋) 후 finally에서 락 해제
            return transactionTemplate.execute(status -> {
                // 이중 차감 방어 — cancelRequestId UK
                Optional<CancelUsageHistory> existing =
                    historyRepository.findByCancelRequestId(cmd.cancelRequestId());
                if (existing.isPresent()) {
                    MerchantCancelUsage usage = usageRepository
                        .findByMerchantIdAndKstDate(cmd.merchantId(), cmd.kstDate())
                        .orElseThrow();
                    return toResult(usage);
                }

                // 1회 조회로 DB 스냅샷과 upsert를 함께 처리
                Optional<MerchantCancelUsage> usageOpt =
                    usageRepository.findByMerchantIdAndKstDate(cmd.merchantId(), cmd.kstDate());

                BigDecimal dailyLimit = resolveDailyLimit(cmd.merchantId(), cmd.kstDate(), usageOpt);

                MerchantCancelUsage usage = usageOpt
                    .orElseGet(() -> MerchantCancelUsage.create(
                        cmd.merchantId(), cmd.kstDate(), dailyLimit));

                domainService.validateAndDeduct(usage, cmd.cancelAmount());
                usageRepository.save(usage);
                historyRepository.save(CancelUsageHistory.record(
                    cmd.cancelRequestId(), cmd.merchantId(), cmd.kstDate(), cmd.cancelAmount()));

                return toResult(usage);
            });
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * daily_limit 3단계 조회:
     * 1. Redis: daily_limit:{merchantId}:{kstDate}
     * 2. DB 스냅샷: usageOpt.dailyLimit (이미 조회한 결과 재사용 — 추가 DB 호출 없음)
     * 3. HTTP: merchantLimitClient (Resilience4j CB 적용)
     */
    private BigDecimal resolveDailyLimit(
        long merchantId, LocalDate kstDate, Optional<MerchantCancelUsage> usageOpt) {
        // 1순위: Redis
        Optional<BigDecimal> cached = dailyLimitCache.get(merchantId, kstDate);
        if (cached.isPresent()) return cached.get();

        // 2순위: DB 스냅샷 (이미 조회한 usageOpt 재사용)
        if (usageOpt.isPresent()) return usageOpt.get().getDailyLimit();

        // 3순위: merchant-limit HTTP (CB OPEN 시 ServiceUnavailableException)
        return merchantLimitClient.fetchDailyLimit(merchantId, kstDate);
    }

    private Result toResult(MerchantCancelUsage usage) {
        return new Result(
            usage.getMerchantId(), usage.getDailyLimit(),
            usage.getUsedAmount(), usage.remaining());
    }
}
```

- [ ] **Step 5: CompensateService + CheckChargeService 구현**

`src/main/java/com/example/riskmanagement/application/service/CompensateService.java`:
```java
package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageCompensation;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CompensateService implements CompensateUseCase {

    private final CancelUsageCompensationRepository compensationRepository;
    private final CancelUsageHistoryRepository historyRepository;
    private final MerchantCancelUsageRepository usageRepository;
    private final CancelLimitDomainService domainService;

    @Override
    @Transactional
    public Result execute(Command cmd) {
        // 1. 보상 중복 확인 (멱등)
        if (compensationRepository.existsByCancelRequestId(cmd.cancelRequestId()))
            return new Result(cmd.cancelRequestId(), false, "ALREADY_COMPENSATED");

        // 2. 차감 이력 확인
        Optional<CancelUsageHistory> historyOpt =
            historyRepository.findByCancelRequestId(cmd.cancelRequestId());
        if (historyOpt.isEmpty())
            return new Result(cmd.cancelRequestId(), false, "NOT_CHARGED");

        // 3. 보상 적용
        CancelUsageHistory history = historyOpt.get();
        MerchantCancelUsage usage = usageRepository
            .findByMerchantIdAndKstDate(history.getMerchantId(), history.getKstDate())
            .orElseThrow();

        domainService.applyCompensation(usage, cmd.restoreAmount());
        usageRepository.save(usage);
        compensationRepository.save(CancelUsageCompensation.record(
            cmd.cancelRequestId(), cmd.merchantId(), cmd.restoreAmount()));

        return new Result(cmd.cancelRequestId(), true, null);
    }
}
```

`src/main/java/com/example/riskmanagement/application/service/CheckChargeService.java`:
```java
package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.CancelUsageHistoryRepository;
import com.example.riskmanagement.application.usecase.CheckChargeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckChargeService implements CheckChargeUseCase {

    private final CancelUsageHistoryRepository historyRepository;

    @Override
    @Transactional(readOnly = true)
    public Result execute(String cancelRequestId) {
        return historyRepository.findByCancelRequestId(cancelRequestId)
            .map(h -> new Result(cancelRequestId, true, h.getMerchantId(), h.getCancelAmount()))
            .orElseGet(() -> new Result(cancelRequestId, false, null, null));
    }
}
```

- [ ] **Step 6: 테스트 실행 — PASS 확인**

```bash
./gradlew :risk-management-service:test \
  --tests "com.example.riskmanagement.application.*"
```
Expected: 10 tests PASS

- [ ] **Step 7: Commit**

```bash
git add risk-management-service/src/
git commit -m "feat(risk): Application 서비스 3개 구현 (ValidateAndReserve, Compensate, CheckCharge)"
```

---

### Task 5: Infrastructure 영속성 레이어 (Testcontainers)

**Files:**
- Create: 9개 JPA 파일 (JpaEntity × 3, JpaRepository × 3, RepositoryImpl × 3)
- Create: `infrastructure/config/PersistenceConfig.java`
- Test: `infrastructure/persistence/AbstractRepositoryTest.java`
- Test: `infrastructure/persistence/MerchantCancelUsageRepositoryImplTest.java`

- [ ] **Step 1: AbstractRepositoryTest 작성**

`src/test/java/com/example/riskmanagement/infrastructure/persistence/AbstractRepositoryTest.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.infrastructure.config.PersistenceConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
public abstract class AbstractRepositoryTest {

    @Container
    public static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("risk_management_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }
}
```

- [ ] **Step 2: MerchantCancelUsageRepositoryImplTest 작성**

`src/test/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageRepositoryImplTest.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class MerchantCancelUsageRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired MerchantCancelUsageJpaRepository jpaRepository;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);

    @Test
    @DisplayName("저장 후 merchantId+kstDate로 조회")
    void save_and_find_by_merchant_id_and_kst_date() {
        MerchantCancelUsageJpaEntity entity = MerchantCancelUsageJpaEntity.from(
            MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000)));
        jpaRepository.save(entity);

        var found = jpaRepository.findByMerchantIdAndKstDate(1L, TODAY);
        assertThat(found).isPresent();
        assertThat(found.get().toDomain().getDailyLimit())
            .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    @DisplayName("동일 merchantId+kstDate 중복 저장 시 DataIntegrityViolationException")
    void duplicate_merchant_id_kst_date_throws() {
        jpaRepository.save(MerchantCancelUsageJpaEntity.from(
            MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000))));

        assertThatThrownBy(() -> jpaRepository.saveAndFlush(
            MerchantCancelUsageJpaEntity.from(
                MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(3_000_000)))))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 3: 테스트 실행 — FAIL 확인 (JPA 클래스 없음)**

```bash
./gradlew :risk-management-service:test \
  --tests "com.example.riskmanagement.infrastructure.persistence.*" 2>&1 | tail -20
```
Expected: FAIL

- [ ] **Step 4: MerchantCancelUsage JPA 파일 작성**

`src/main/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageJpaEntity.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "merchant_cancel_usage",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_merchant_cancel_usage_merchant_id_kst_date",
        columnNames = {"merchant_id", "kst_date"}))
public class MerchantCancelUsageJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "kst_date", nullable = false)
    private LocalDate kstDate;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "used_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal usedAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantCancelUsageJpaEntity() {}

    public static MerchantCancelUsageJpaEntity from(MerchantCancelUsage usage) {
        MerchantCancelUsageJpaEntity e = new MerchantCancelUsageJpaEntity();
        e.id = usage.getId();
        if (usage.getId() == null) e.createdAt = Instant.now();
        e.merchantId = usage.getMerchantId();
        e.kstDate = usage.getKstDate();
        e.dailyLimit = usage.getDailyLimit();
        e.usedAmount = usage.getUsedAmount();
        e.updatedAt = Instant.now();
        return e;
    }

    public MerchantCancelUsage toDomain() {
        return MerchantCancelUsage.reconstruct(id, merchantId, kstDate, dailyLimit, usedAmount);
    }

    public Long getId() { return id; }
}
```

`src/main/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageJpaRepository.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface MerchantCancelUsageJpaRepository
    extends JpaRepository<MerchantCancelUsageJpaEntity, Long> {

    Optional<MerchantCancelUsageJpaEntity> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate);
}
```

`src/main/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageRepositoryImpl.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.Optional;

@RequiredArgsConstructor
public class MerchantCancelUsageRepositoryImpl implements MerchantCancelUsageRepository {

    private final MerchantCancelUsageJpaRepository jpa;

    @Override
    public MerchantCancelUsage save(MerchantCancelUsage usage) {
        return jpa.save(MerchantCancelUsageJpaEntity.from(usage)).toDomain();
    }

    @Override
    public Optional<MerchantCancelUsage> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate) {
        return jpa.findByMerchantIdAndKstDate(merchantId, kstDate)
            .map(MerchantCancelUsageJpaEntity::toDomain);
    }
}
```

- [ ] **Step 5: CancelUsageHistory JPA 파일 작성**

`src/main/java/com/example/riskmanagement/infrastructure/persistence/CancelUsageHistoryJpaEntity.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "cancel_usage_history",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cancel_usage_history_cancel_request_id",
        columnNames = "cancel_request_id"))
public class CancelUsageHistoryJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, length = 64)
    private String cancelRequestId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "kst_date", nullable = false)
    private LocalDate kstDate;

    @Column(name = "cancel_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cancelAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CancelUsageHistoryJpaEntity() {}

    public static CancelUsageHistoryJpaEntity from(CancelUsageHistory history) {
        CancelUsageHistoryJpaEntity e = new CancelUsageHistoryJpaEntity();
        e.cancelRequestId = history.getCancelRequestId();
        e.merchantId = history.getMerchantId();
        e.kstDate = history.getKstDate();
        e.cancelAmount = history.getCancelAmount();
        e.createdAt = Instant.now();
        return e;
    }

    public CancelUsageHistory toDomain() {
        return CancelUsageHistory.reconstruct(id, cancelRequestId, merchantId, kstDate, cancelAmount);
    }
}
```

`src/main/java/com/example/riskmanagement/infrastructure/persistence/CancelUsageHistoryJpaRepository.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CancelUsageHistoryJpaRepository
    extends JpaRepository<CancelUsageHistoryJpaEntity, Long> {

    Optional<CancelUsageHistoryJpaEntity> findByCancelRequestId(String cancelRequestId);
}
```

`src/main/java/com/example/riskmanagement/infrastructure/persistence/CancelUsageHistoryRepositoryImpl.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.application.interfaces.CancelUsageHistoryRepository;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class CancelUsageHistoryRepositoryImpl implements CancelUsageHistoryRepository {

    private final CancelUsageHistoryJpaRepository jpa;

    @Override
    public CancelUsageHistory save(CancelUsageHistory history) {
        return jpa.save(CancelUsageHistoryJpaEntity.from(history)).toDomain();
    }

    @Override
    public Optional<CancelUsageHistory> findByCancelRequestId(String cancelRequestId) {
        return jpa.findByCancelRequestId(cancelRequestId)
            .map(CancelUsageHistoryJpaEntity::toDomain);
    }
}
```

- [ ] **Step 6: CancelUsageCompensation JPA 파일 작성**

`src/main/java/com/example/riskmanagement/infrastructure/persistence/CancelUsageCompensationJpaEntity.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.domain.entity.CancelUsageCompensation;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cancel_usage_compensation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cancel_usage_compensation_cancel_request_id",
        columnNames = "cancel_request_id"))
public class CancelUsageCompensationJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, length = 64)
    private String cancelRequestId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "restore_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal restoreAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CancelUsageCompensationJpaEntity() {}

    public static CancelUsageCompensationJpaEntity from(CancelUsageCompensation compensation) {
        CancelUsageCompensationJpaEntity e = new CancelUsageCompensationJpaEntity();
        e.cancelRequestId = compensation.getCancelRequestId();
        e.merchantId = compensation.getMerchantId();
        e.restoreAmount = compensation.getRestoreAmount();
        e.createdAt = Instant.now();
        return e;
    }
}
```

`src/main/java/com/example/riskmanagement/infrastructure/persistence/CancelUsageCompensationJpaRepository.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CancelUsageCompensationJpaRepository
    extends JpaRepository<CancelUsageCompensationJpaEntity, Long> {

    boolean existsByCancelRequestId(String cancelRequestId);
}
```

`src/main/java/com/example/riskmanagement/infrastructure/persistence/CancelUsageCompensationRepositoryImpl.java`:
```java
package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.application.interfaces.CancelUsageCompensationRepository;
import com.example.riskmanagement.domain.entity.CancelUsageCompensation;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CancelUsageCompensationRepositoryImpl implements CancelUsageCompensationRepository {

    private final CancelUsageCompensationJpaRepository jpa;

    @Override
    public CancelUsageCompensation save(CancelUsageCompensation compensation) {
        jpa.save(CancelUsageCompensationJpaEntity.from(compensation));
        return compensation;
    }

    @Override
    public boolean existsByCancelRequestId(String cancelRequestId) {
        return jpa.existsByCancelRequestId(cancelRequestId);
    }
}
```

- [ ] **Step 7: PersistenceConfig 작성**

`src/main/java/com/example/riskmanagement/infrastructure/config/PersistenceConfig.java`:
```java
package com.example.riskmanagement.infrastructure.config;

import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.infrastructure.persistence.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.riskmanagement.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public MerchantCancelUsageRepository merchantCancelUsageRepository(
        MerchantCancelUsageJpaRepository jpa) {
        return new MerchantCancelUsageRepositoryImpl(jpa);
    }

    @Bean
    public CancelUsageHistoryRepository cancelUsageHistoryRepository(
        CancelUsageHistoryJpaRepository jpa) {
        return new CancelUsageHistoryRepositoryImpl(jpa);
    }

    @Bean
    public CancelUsageCompensationRepository cancelUsageCompensationRepository(
        CancelUsageCompensationJpaRepository jpa) {
        return new CancelUsageCompensationRepositoryImpl(jpa);
    }
}
```

- [ ] **Step 8: 테스트 실행 — PASS 확인**

```bash
./gradlew :risk-management-service:test \
  --tests "com.example.riskmanagement.infrastructure.persistence.*"
```
Expected: 3 tests PASS (MySQL Testcontainer 기동 포함, 30~60초 소요)

- [ ] **Step 9: Commit**

```bash
git add risk-management-service/src/
git commit -m "feat(risk): Infrastructure 영속성 레이어 — JPA 엔티티 3개 + PersistenceConfig"
```

---

### Task 6: Redis 캐시

**Files:**
- Create: `infrastructure/cache/RedisDailyLimitCache.java`
- Create: `infrastructure/config/RedisConfig.java`

- [ ] **Step 1: RedisConfig 작성**

`src/main/java/com/example/riskmanagement/infrastructure/config/RedisConfig.java`:
```java
package com.example.riskmanagement.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
```

- [ ] **Step 2: RedisDailyLimitCache 작성**

`src/main/java/com/example/riskmanagement/infrastructure/cache/RedisDailyLimitCache.java`:
```java
package com.example.riskmanagement.infrastructure.cache;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisDailyLimitCache implements DailyLimitCache {

    private final StringRedisTemplate redisTemplate;
    private static final Duration TTL = Duration.ofHours(25);

    @Override
    public Optional<BigDecimal> get(long merchantId, LocalDate kstDate) {
        String value = redisTemplate.opsForValue().get(key(merchantId, kstDate));
        return Optional.ofNullable(value).map(BigDecimal::new);
    }

    @Override
    public void set(long merchantId, LocalDate kstDate, BigDecimal limit) {
        redisTemplate.opsForValue().set(key(merchantId, kstDate), limit.toPlainString(), TTL);
    }

    private String key(long merchantId, LocalDate kstDate) {
        return "daily_limit:" + merchantId + ":" + kstDate;
    }
}
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :risk-management-service:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add risk-management-service/src/
git commit -m "feat(risk): Redis 캐시 구현 — RedisDailyLimitCache (TTL 25h)"
```

---

### Task 7: HTTP 클라이언트 + Resilience4j CircuitBreaker

**Files:**
- Create: `infrastructure/http/MerchantNotFoundException.java`
- Create: `infrastructure/http/MerchantLimitResponse.java`
- Create: `infrastructure/http/MerchantLimitRestClient.java`
- Create: `infrastructure/config/ResilienceConfig.java`

> **Resilience4j 동작:**
> - `ignoreExceptions`에 등록된 `MerchantNotFoundException` (404)은 CB 실패 카운트 안 됨 — 예외가 그대로 전파됨 (fallback 미호출)
> - 5xx / 타임아웃은 CB 실패 카운트 → OPEN 시 fallback 호출 → `ServiceUnavailableException` throw
> - `ValidateAndReserveService`에서 3순위 HTTP를 호출할 때만 도달. DB 스냅샷 없음 → HTTP 실패 → 503

- [ ] **Step 1: 인프라 예외 + 응답 모델 작성**

`src/main/java/com/example/riskmanagement/infrastructure/http/MerchantNotFoundException.java`:
```java
package com.example.riskmanagement.infrastructure.http;

public class MerchantNotFoundException extends RuntimeException {
    public MerchantNotFoundException(long merchantId) {
        super("가맹점을 찾을 수 없습니다: " + merchantId);
    }
}
```

`src/main/java/com/example/riskmanagement/infrastructure/http/MerchantLimitResponse.java`:
```java
package com.example.riskmanagement.infrastructure.http;

import java.math.BigDecimal;

public record MerchantLimitResponse(long merchantId, BigDecimal dailyLimit, String merchantStatus) {}
```

- [ ] **Step 2: ResilienceConfig (RestClient 빈) 작성**

`src/main/java/com/example/riskmanagement/infrastructure/config/ResilienceConfig.java`:
```java
package com.example.riskmanagement.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ResilienceConfig {

    @Bean
    public RestClient merchantLimitRestClient(
        @Value("${external.merchant-limit.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
```

- [ ] **Step 3: MerchantLimitRestClient 작성**

`src/main/java/com/example/riskmanagement/infrastructure/http/MerchantLimitRestClient.java`:
```java
package com.example.riskmanagement.infrastructure.http;

import com.example.riskmanagement.application.exception.ServiceUnavailableException;
import com.example.riskmanagement.application.interfaces.MerchantLimitClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantLimitRestClient implements MerchantLimitClient {

    private final RestClient merchantLimitRestClient;

    @CircuitBreaker(name = "merchant-limit", fallbackMethod = "fetchDailyLimitFallback")
    @Override
    public BigDecimal fetchDailyLimit(long merchantId, LocalDate kstDate) {
        MerchantLimitResponse response = merchantLimitRestClient.get()
            .uri("/internal/merchants/{merchantId}/cancel-limit", merchantId)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                if (res.getStatusCode().value() == 404)
                    throw new MerchantNotFoundException(merchantId);
                throw new RuntimeException("merchant-limit 4xx: " + res.getStatusCode());
            })
            .body(MerchantLimitResponse.class);
        return response.dailyLimit();
    }

    // CB OPEN 또는 5xx / 타임아웃 시 호출
    // ignoreExceptions의 MerchantNotFoundException은 이 fallback을 거치지 않고 직접 전파됨
    private BigDecimal fetchDailyLimitFallback(long merchantId, LocalDate kstDate, Exception e) {
        log.warn("merchant-limit CircuitBreaker fallback. merchantId={}, cause={}", merchantId, e.getMessage());
        throw new ServiceUnavailableException();
    }
}
```

- [ ] **Step 4: 빌드 확인**

```bash
./gradlew :risk-management-service:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add risk-management-service/src/
git commit -m "feat(risk): MerchantLimitRestClient + Resilience4j CircuitBreaker"
```

---

### Task 8: Kafka Consumer

**Files:**
- Create: `infrastructure/messaging/MerchantLimitUpdatedPayload.java`
- Create: `infrastructure/messaging/MerchantLimitUpdatedConsumer.java`
- Create: `infrastructure/config/KafkaConsumerConfig.java`

- [ ] **Step 1: Payload 모델 작성**

`src/main/java/com/example/riskmanagement/infrastructure/messaging/MerchantLimitUpdatedPayload.java`:
```java
package com.example.riskmanagement.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MerchantLimitUpdatedPayload(long merchantId, BigDecimal newLimit, LocalDate kstDate) {}
```

- [ ] **Step 2: KafkaConsumerConfig 작성**

`src/main/java/com/example/riskmanagement/infrastructure/config/KafkaConsumerConfig.java`:
```java
package com.example.riskmanagement.infrastructure.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
```

- [ ] **Step 3: MerchantLimitUpdatedConsumer 작성**

`src/main/java/com/example/riskmanagement/infrastructure/messaging/MerchantLimitUpdatedConsumer.java`:
```java
package com.example.riskmanagement.infrastructure.messaging;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantLimitUpdatedConsumer {

    private final DailyLimitCache dailyLimitCache;
    private final MerchantCancelUsageRepository usageRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${kafka.topic.merchant-limit-updated}",
        groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            MerchantLimitUpdatedPayload payload =
                objectMapper.readValue(record.value(), MerchantLimitUpdatedPayload.class);

            // 1. Redis 갱신 (TTL 25h) — 자연 멱등
            dailyLimitCache.set(payload.merchantId(), payload.kstDate(), payload.newLimit());

            // 2. DB 스냅샷 갱신 (행 있을 때만) — 자연 멱등
            usageRepository.findByMerchantIdAndKstDate(payload.merchantId(), payload.kstDate())
                .ifPresent(usage -> {
                    usage.updateDailyLimit(payload.newLimit());
                    usageRepository.save(usage);
                });

            ack.acknowledge();
            log.debug("merchant.limit.updated 처리 완료. merchantId={}, kstDate={}",
                payload.merchantId(), payload.kstDate());

        } catch (Exception e) {
            log.error("merchant.limit.updated 처리 실패. offset={}, value={}",
                record.offset(), record.value(), e);
            ack.acknowledge(); // idempotent — ack 후 넘어감 (3순위 HTTP fallback 보장)
        }
    }
}
```

- [ ] **Step 4: 빌드 확인**

```bash
./gradlew :risk-management-service:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add risk-management-service/src/
git commit -m "feat(risk): Kafka Consumer — merchant.limit.updated (Redis+DB 갱신, MANUAL_IMMEDIATE)"
```

---

### Task 9: Presentation 레이어

**Files:**
- Create: `presentation/dto/ValidateAndReserveRequest.java`
- Create: `presentation/dto/ValidateAndReserveResponse.java`
- Create: `presentation/dto/CompensateRequest.java`
- Create: `presentation/dto/CompensateResponse.java`
- Create: `presentation/dto/CheckChargeResponse.java`
- Create: `presentation/controller/InternalCancelLimitController.java`
- Create: `presentation/GlobalExceptionHandler.java`
- Test: `presentation/InternalCancelLimitControllerTest.java`

- [ ] **Step 1: 실패하는 컨트롤러 테스트 작성**

`src/test/java/com/example/riskmanagement/presentation/InternalCancelLimitControllerTest.java`:
```java
package com.example.riskmanagement.presentation;

import com.example.riskmanagement.application.exception.ServiceUnavailableException;
import com.example.riskmanagement.application.usecase.CheckChargeUseCase;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.domain.exception.CancelLimitExceededException;
import com.example.riskmanagement.presentation.controller.InternalCancelLimitController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({InternalCancelLimitController.class, GlobalExceptionHandler.class})
@DisplayName("InternalCancelLimitController")
class InternalCancelLimitControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ValidateAndReserveUseCase validateAndReserveUseCase;
    @MockBean CompensateUseCase compensateUseCase;
    @MockBean CheckChargeUseCase checkChargeUseCase;

    @Test
    @DisplayName("validate-and-reserve 성공 — 200")
    void validate_and_reserve_returns_200() throws Exception {
        when(validateAndReserveUseCase.execute(any())).thenReturn(
            new ValidateAndReserveUseCase.Result(1L, BigDecimal.valueOf(5_000_000),
                BigDecimal.valueOf(300_000), BigDecimal.valueOf(4_700_000)));

        mockMvc.perform(post("/internal/cancel-limit/validate-and-reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "merchantId": 1,
                      "cancelRequestId": "cr_001",
                      "cancelAmount": 300000,
                      "kstDate": "2026-04-23"
                    }"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.remainingLimit").value(4700000));
    }

    @Test
    @DisplayName("한도 초과 — 422 + 추가 필드 포함")
    void validate_and_reserve_returns_422_when_limit_exceeded() throws Exception {
        when(validateAndReserveUseCase.execute(any())).thenThrow(
            new CancelLimitExceededException(
                BigDecimal.valueOf(5_000_000),
                BigDecimal.valueOf(4_800_000),
                BigDecimal.valueOf(300_000)));

        mockMvc.perform(post("/internal/cancel-limit/validate-and-reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "merchantId": 1,
                      "cancelRequestId": "cr_001",
                      "cancelAmount": 300000,
                      "kstDate": "2026-04-23"
                    }"""))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CANCEL_LIMIT_EXCEEDED"))
            .andExpect(jsonPath("$.dailyLimit").value(5000000))
            .andExpect(jsonPath("$.requestAmount").value(300000));
    }

    @Test
    @DisplayName("merchantId 누락 — 400")
    void validate_and_reserve_returns_400_when_merchant_id_missing() throws Exception {
        mockMvc.perform(post("/internal/cancel-limit/validate-and-reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelRequestId": "cr_001",
                      "cancelAmount": 300000,
                      "kstDate": "2026-04-23"
                    }"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("check — charged=true 반환")
    void check_returns_charged_true() throws Exception {
        when(checkChargeUseCase.execute("cr_001")).thenReturn(
            new CheckChargeUseCase.Result("cr_001", true, 1L, BigDecimal.valueOf(300_000)));

        mockMvc.perform(get("/internal/cancel-limit/check")
                .param("cancelRequestId", "cr_001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.charged").value(true))
            .andExpect(jsonPath("$.cancelAmount").value(300000));
    }

    @Test
    @DisplayName("SERVICE_UNAVAILABLE — 503")
    void validate_and_reserve_returns_503_when_service_unavailable() throws Exception {
        when(validateAndReserveUseCase.execute(any())).thenThrow(new ServiceUnavailableException());

        mockMvc.perform(post("/internal/cancel-limit/validate-and-reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "merchantId": 1,
                      "cancelRequestId": "cr_001",
                      "cancelAmount": 300000,
                      "kstDate": "2026-04-23"
                    }"""))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
```

- [ ] **Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew :risk-management-service:test \
  --tests "com.example.riskmanagement.presentation.*" 2>&1 | tail -20
```
Expected: FAIL (컨트롤러/DTO 없음)

- [ ] **Step 3: DTO 작성**

`src/main/java/com/example/riskmanagement/presentation/dto/ValidateAndReserveRequest.java`:
```java
package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ValidateAndReserveRequest(
    @NotNull Long merchantId,
    @NotBlank String cancelRequestId,
    @NotNull @DecimalMin("0.01") BigDecimal cancelAmount,
    @NotNull LocalDate kstDate
) {
    public ValidateAndReserveUseCase.Command toCommand() {
        return new ValidateAndReserveUseCase.Command(merchantId, cancelRequestId, cancelAmount, kstDate);
    }
}
```

`src/main/java/com/example/riskmanagement/presentation/dto/ValidateAndReserveResponse.java`:
```java
package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;

import java.math.BigDecimal;

public record ValidateAndReserveResponse(
    long merchantId,
    BigDecimal dailyLimit,
    BigDecimal usedAmount,
    BigDecimal remainingLimit
) {
    public static ValidateAndReserveResponse from(ValidateAndReserveUseCase.Result result) {
        return new ValidateAndReserveResponse(
            result.merchantId(), result.dailyLimit(), result.usedAmount(), result.remainingLimit());
    }
}
```

`src/main/java/com/example/riskmanagement/presentation/dto/CompensateRequest.java`:
```java
package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.CompensateUseCase;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CompensateRequest(
    @NotBlank String cancelRequestId,
    @NotNull Long merchantId,
    @NotNull @DecimalMin("0.01") BigDecimal restoreAmount
) {
    public CompensateUseCase.Command toCommand() {
        return new CompensateUseCase.Command(cancelRequestId, merchantId, restoreAmount);
    }
}
```

`src/main/java/com/example/riskmanagement/presentation/dto/CompensateResponse.java`:
```java
package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.CompensateUseCase;

public record CompensateResponse(String cancelRequestId, boolean restored, String reason) {
    public static CompensateResponse from(CompensateUseCase.Result result) {
        return new CompensateResponse(result.cancelRequestId(), result.restored(), result.reason());
    }
}
```

`src/main/java/com/example/riskmanagement/presentation/dto/CheckChargeResponse.java`:
```java
package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.CheckChargeUseCase;

import java.math.BigDecimal;

public record CheckChargeResponse(
    String cancelRequestId, boolean charged, Long merchantId, BigDecimal cancelAmount
) {
    public static CheckChargeResponse from(CheckChargeUseCase.Result result) {
        return new CheckChargeResponse(
            result.cancelRequestId(), result.charged(), result.merchantId(), result.cancelAmount());
    }
}
```

- [ ] **Step 4: 컨트롤러 작성**

`src/main/java/com/example/riskmanagement/presentation/controller/InternalCancelLimitController.java`:
```java
package com.example.riskmanagement.presentation.controller;

import com.example.riskmanagement.application.usecase.CheckChargeUseCase;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/cancel-limit")
@RequiredArgsConstructor
public class InternalCancelLimitController {

    private final ValidateAndReserveUseCase validateAndReserveUseCase;
    private final CompensateUseCase compensateUseCase;
    private final CheckChargeUseCase checkChargeUseCase;

    @PostMapping("/validate-and-reserve")
    public ResponseEntity<ValidateAndReserveResponse> validateAndReserve(
        @RequestBody @Valid ValidateAndReserveRequest request) {
        return ResponseEntity.ok(
            ValidateAndReserveResponse.from(validateAndReserveUseCase.execute(request.toCommand())));
    }

    @PostMapping("/compensate")
    public ResponseEntity<CompensateResponse> compensate(
        @RequestBody @Valid CompensateRequest request) {
        return ResponseEntity.ok(
            CompensateResponse.from(compensateUseCase.execute(request.toCommand())));
    }

    @GetMapping("/check")
    public ResponseEntity<CheckChargeResponse> check(@RequestParam String cancelRequestId) {
        return ResponseEntity.ok(
            CheckChargeResponse.from(checkChargeUseCase.execute(cancelRequestId)));
    }
}
```

- [ ] **Step 5: GlobalExceptionHandler 작성**

`src/main/java/com/example/riskmanagement/presentation/GlobalExceptionHandler.java`:
```java
package com.example.riskmanagement.presentation;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.domain.exception.CancelLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 한도 초과는 추가 필드(dailyLimit, usedAmount, remainingLimit, requestAmount) 포함
    @ExceptionHandler(CancelLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleLimitExceeded(CancelLimitExceededException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getErrorCode().getCode());
        body.put("dailyLimit", e.getDailyLimit());
        body.put("usedAmount", e.getUsedAmount());
        body.put("remainingLimit", e.getDailyLimit().subtract(e.getUsedAmount()));
        body.put("requestAmount", e.getRequestAmount());
        return ResponseEntity.status(422).body(body);
    }

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("code", "INTERNAL_ERROR", "message", "서버 오류가 발생했습니다."));
    }
}
```

- [ ] **Step 6: 테스트 실행 — PASS 확인**

```bash
./gradlew :risk-management-service:test \
  --tests "com.example.riskmanagement.presentation.*"
```
Expected: 5 tests PASS

- [ ] **Step 7: 전체 테스트 실행**

```bash
./gradlew :risk-management-service:test
```
Expected: 모든 테스트 PASS

- [ ] **Step 8: Commit**

```bash
git add risk-management-service/src/
git commit -m "feat(risk): Presentation 레이어 — 컨트롤러, DTO, GlobalExceptionHandler"
```

---

## Self-Review

**스펙 커버리지:**
- ✅ POST /internal/cancel-limit/validate-and-reserve
- ✅ POST /internal/cancel-limit/compensate
- ✅ GET /internal/cancel-limit/check
- ✅ daily_limit 3단계 조회 (Redis → DB 스냅샷 → HTTP)
- ✅ Redis 분산락 per merchantId (TTL 5초, TX 커밋 후 해제)
- ✅ cancel_usage_history UK (이중 차감 방어)
- ✅ cancel_usage_compensation UK (보상 멱등성)
- ✅ Kafka Consumer (merchant.limit.updated → Redis+DB 갱신)
- ✅ Resilience4j CircuitBreaker (MerchantNotFoundException ignoreExceptions)
- ✅ 스케줄러 없음

**타입 일관성:**
- `CancelUsageHistory.record(cancelRequestId, merchantId, kstDate, cancelAmount)` — Task 2 정의, Task 4/5에서 동일 시그니처 사용
- `MerchantCancelUsage.reconstruct(id, merchantId, kstDate, dailyLimit, usedAmount)` — Task 2 정의, Task 5 JPA에서 동일 사용
- `ValidateAndReserveUseCase.Command(merchantId, cancelRequestId, cancelAmount, kstDate)` — Task 3 정의, Task 4 테스트/컨트롤러에서 동일 사용
