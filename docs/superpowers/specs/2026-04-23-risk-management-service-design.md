# risk-management-service Implementation Design

**Goal:** 가맹점별 일일 취소한도 소진 관리 서비스 구현 — payment-service 호출용 Internal API 3개(validate-and-reserve, compensate, check), `merchant.limit.updated` Kafka Consumer, Redis 분산락 기반 동시성 제어, Resilience4j CircuitBreaker

**Architecture:** payment-service/merchant-limit-service와 동일한 Hexagonal 구조(domain → application → infrastructure → presentation). 도메인 레이어는 Spring/JPA 의존 없이 순수 Java. 인증/인가는 구현 범위 외(API Gateway 위임). 스케줄러 없음.

**Tech Stack:** Java 21, Spring Boot 3.x(4.0.5), Spring Data JPA, MySQL 8.0, Flyway, Kafka 3.x, Redis(분산락 + daily_limit 캐시), Resilience4j(CircuitBreaker), JUnit 5 + Mockito + Testcontainers

---

## 1. 패키지 구조

```
com.example.riskmanagement
├── RiskManagementApplication.java
├── common/exception/
│   ├── BusinessException.java
│   └── ErrorCode.java
├── domain/
│   ├── entity/
│   │   ├── MerchantCancelUsage.java       (id, merchantId, kstDate, dailyLimit, usedAmount)
│   │   ├── CancelUsageHistory.java         (id, cancelRequestId, merchantId, cancelAmount)
│   │   └── CancelUsageCompensation.java    (id, cancelRequestId, merchantId, restoreAmount)
│   ├── service/
│   │   └── CancelLimitDomainService.java   (validateAmount, applyDeduction, applyCompensation)
│   └── exception/
│       └── CancelLimitExceededException.java
├── application/
│   ├── usecase/
│   │   ├── ValidateAndReserveUseCase.java
│   │   ├── CompensateUseCase.java
│   │   └── CheckChargeUseCase.java
│   ├── service/
│   │   ├── ValidateAndReserveService.java
│   │   ├── CompensateService.java
│   │   └── CheckChargeService.java
│   └── interfaces/
│       ├── MerchantCancelUsageRepository.java
│       ├── CancelUsageHistoryRepository.java
│       ├── CancelUsageCompensationRepository.java
│       ├── MerchantLimitClient.java          (외부 HTTP 계약)
│       └── DailyLimitCache.java              (Redis 계약)
├── infrastructure/
│   ├── persistence/
│   │   ├── MerchantCancelUsageJpaEntity.java
│   │   ├── MerchantCancelUsageJpaRepository.java
│   │   ├── MerchantCancelUsageRepositoryImpl.java
│   │   ├── CancelUsageHistoryJpaEntity.java
│   │   ├── CancelUsageHistoryJpaRepository.java
│   │   ├── CancelUsageHistoryRepositoryImpl.java
│   │   ├── CancelUsageCompensationJpaEntity.java
│   │   ├── CancelUsageCompensationJpaRepository.java
│   │   └── CancelUsageCompensationRepositoryImpl.java
│   ├── http/
│   │   └── MerchantLimitRestClient.java      (Resilience4j CB 적용)
│   ├── cache/
│   │   └── RedisDailyLimitCache.java
│   ├── messaging/
│   │   └── MerchantLimitUpdatedConsumer.java
│   └── config/
│       ├── PersistenceConfig.java
│       ├── KafkaConsumerConfig.java
│       ├── RedisConfig.java
│       └── ResilienceConfig.java
└── presentation/
    ├── controller/
    │   └── InternalCancelLimitController.java
    ├── dto/
    │   ├── ValidateAndReserveRequest.java
    │   ├── ValidateAndReserveResponse.java
    │   ├── CompensateRequest.java
    │   ├── CompensateResponse.java
    │   └── CheckChargeResponse.java
    └── GlobalExceptionHandler.java
```

---

## 2. 도메인 모델

### MerchantCancelUsage

```java
public class MerchantCancelUsage {
    private Long id;
    private Long merchantId;
    private LocalDate kstDate;
    private BigDecimal dailyLimit;
    private BigDecimal usedAmount;

    public static MerchantCancelUsage create(long merchantId, LocalDate kstDate, BigDecimal dailyLimit) { ... }
    public static MerchantCancelUsage reconstruct(...) { ... }

    // 차감 적용 (도메인 서비스에서 호출)
    public void deduct(BigDecimal amount) {
        this.usedAmount = this.usedAmount.add(amount);
    }

    // 보상 적용 (음수 방지)
    public void restore(BigDecimal amount) {
        this.usedAmount = this.usedAmount.subtract(amount).max(BigDecimal.ZERO);
    }

    public BigDecimal remaining() {
        return dailyLimit.subtract(usedAmount);
    }
}
```

### CancelUsageHistory

```java
public class CancelUsageHistory {
    private Long id;
    private String cancelRequestId;   // UK — 이중 차감 방어
    private Long merchantId;
    private LocalDate kstDate;        // 차감 시점 KST 날짜 (명시적 저장)
    private BigDecimal cancelAmount;

    public static CancelUsageHistory record(String cancelRequestId, long merchantId, LocalDate kstDate, BigDecimal cancelAmount) { ... }
}
```

### CancelUsageCompensation

```java
public class CancelUsageCompensation {
    private Long id;
    private String cancelRequestId;   // UK — 보상 멱등성
    private Long merchantId;
    private BigDecimal restoreAmount;

    public static CancelUsageCompensation record(String cancelRequestId, long merchantId, BigDecimal restoreAmount) { ... }
}
```

### CancelLimitDomainService

```java
public class CancelLimitDomainService {

    // 한도 검증 후 차감 적용
    public void validateAndDeduct(MerchantCancelUsage usage, BigDecimal cancelAmount) {
        if (usage.remaining().compareTo(cancelAmount) < 0)
            throw new CancelLimitExceededException(usage.getDailyLimit(), usage.getUsedAmount(), cancelAmount);
        usage.deduct(cancelAmount);
    }

    // 보상 적용
    public void applyCompensation(MerchantCancelUsage usage, BigDecimal restoreAmount) {
        usage.restore(restoreAmount);
    }
}
```

---

## 3. DB / DDL (V1)

```sql
-- V1__create_risk_core.sql

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

-- 차감 이력 (이중 차감 방어)
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

-- 보상 멱등성
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

**설계 결정:**
- `merchant_cancel_usage`는 가맹점+날짜당 1행. 당일 used_amount 누적
- `cancel_usage_history` UK: 분산락 해제 후 서버 다운 → 다른 인스턴스 락 획득 시 이중 차감 방어
- `cancel_usage_compensation` UK: compensate API 중복 호출 시 no-op 반환
- FK 제약 없음 — 앱 레벨 관리 (db-schema.md 원칙)

---

## 4. API 설계 (Internal — payment-service 호출)

### POST /internal/cancel-limit/validate-and-reserve

```
요청:
{
  "merchantId": 1,
  "cancelRequestId": "cr_abc123",
  "cancelAmount": 300000,
  "kstDate": "2026-04-23"
}

응답 200:
{
  "merchantId": 1,
  "dailyLimit": 5000000,
  "usedAmount": 300000,
  "remainingLimit": 4700000
}

응답 422 (한도 초과):
{
  "code": "CANCEL_LIMIT_EXCEEDED",
  "dailyLimit": 5000000,
  "usedAmount": 4800000,
  "remainingLimit": 200000,
  "requestAmount": 300000
}

응답 503 (merchant-limit 장애 + DB 스냅샷 없음):
{ "code": "SERVICE_UNAVAILABLE", "message": "일시적 오류가 발생했습니다" }
```

### POST /internal/cancel-limit/compensate

```
요청:
{
  "cancelRequestId": "cr_abc123",
  "merchantId": 1,
  "restoreAmount": 300000
}

응답 200 (보상 성공):
{ "cancelRequestId": "cr_abc123", "restored": true }

응답 200 (이미 보상됨 — 멱등):
{ "cancelRequestId": "cr_abc123", "restored": false, "reason": "ALREADY_COMPENSATED" }

응답 200 (차감 이력 없음):
{ "cancelRequestId": "cr_abc123", "restored": false, "reason": "NOT_CHARGED" }
```

### GET /internal/cancel-limit/check?cancelRequestId=cr_abc123

```
응답 200 (차감된 경우):
{
  "cancelRequestId": "cr_abc123",
  "charged": true,
  "merchantId": 1,
  "cancelAmount": 300000
}

응답 200 (차감 안 된 경우):
{ "cancelRequestId": "cr_abc123", "charged": false }
```

### 에러 코드

| code | HTTP | 설명 |
|------|------|------|
| `CANCEL_LIMIT_EXCEEDED` | 422 | 가맹점 일일 취소한도 초과 |
| `SERVICE_UNAVAILABLE` | 503 | merchant-limit 장애 + 스냅샷 없음 |

---

## 5. 핵심 서비스 로직

### ValidateAndReserveService

```
execute(merchantId, cancelRequestId, cancelAmount, kstDate):

1. Redis 분산락 획득: lock:risk:merchant:{merchantId} (TTL 5초)
   → 획득 실패 시 ServiceUnavailableException (재시도 권장)
2. cancel_usage_history에서 cancelRequestId 조회
   → 이미 있으면 기존 merchant_cancel_usage 조회 후 결과 반환 (no-op, 이중 차감 방어)
3. daily_limit 3단계 조회:
   a. Redis: daily_limit:{merchantId}:{kstDate}
   b. DB: merchant_cancel_usage.daily_limit (스냅샷)
   c. merchant-limit HTTP: GET /internal/merchants/{merchantId}/cancel-limit
      (Resilience4j CB — OPEN 시 b에서 스냅샷 없으면 ServiceUnavailableException)
4. merchant_cancel_usage upsert:
   - 행 없으면: MerchantCancelUsage.create(merchantId, kstDate, dailyLimit)
   - 행 있으면: 기존 행 사용 (daily_limit은 이미 스냅샷됨)
5. domainService.validateAndDeduct(usage, cancelAmount)
   → 한도 초과 시 CancelLimitExceededException
6. merchantCancelUsageRepository.save(usage)
7. cancelUsageHistoryRepository.save(CancelUsageHistory.record(cancelRequestId, merchantId, kstDate, cancelAmount))
8. Redis 분산락 해제 (finally)
9. ValidateAndReserveResult 반환
```

### CompensateService

```
execute(cancelRequestId, merchantId, restoreAmount):

1. cancel_usage_compensation에서 cancelRequestId 조회
   → 있으면: CompensateResult(restored=false, reason=ALREADY_COMPENSATED) 반환
2. cancel_usage_history에서 cancelRequestId 조회
   → 없으면: CompensateResult(restored=false, reason=NOT_CHARGED) 반환
3. history에서 merchantId, kstDate 직접 조회.
   merchant_cancel_usage에서 merchantId+kstDate로 조회
4. domainService.applyCompensation(usage, restoreAmount)
5. merchantCancelUsageRepository.save(usage)
6. cancelUsageCompensationRepository.save(CancelUsageCompensation.record(...))
7. CompensateResult(restored=true) 반환
```

### CheckChargeService

```
execute(cancelRequestId):

cancel_usage_history에서 cancelRequestId 조회
→ 있으면: CheckChargeResult(charged=true, merchantId, cancelAmount)
→ 없으면: CheckChargeResult(charged=false)
```

---

## 6. 동시성 — Redis 분산락

| 항목 | 값 |
|------|-----|
| 락 키 | `lock:risk:merchant:{merchantId}` |
| TTL | 5초 |
| 구현 | `StringRedisTemplate.opsForValue().setIfAbsent(key, "1", 5, SECONDS)` |
| 이중 차감 방어 | `cancel_usage_history` cancelRequestId UK |

```
분산락 해제 후 서버 다운 케이스:
  다른 인스턴스가 락 획득 → validate-and-reserve 재진입
  cancel_usage_history UK로 이중 차감 감지 → no-op 반환
  → 결과적으로 exactly-once 차감 보장
```

---

## 7. Kafka Consumer

### merchant.limit.updated

```
토픽: merchant.limit.updated
Consumer Group: risk-management-service
파티션: 3 (merchantId 파티션 키 → 가맹점별 순서 보장)
오프셋 커밋: 수동 (MANUAL_IMMEDIATE)

처리:
1. 메시지 수신: { merchantId, newLimit, kstDate }
2. Redis SET daily_limit:{merchantId}:{kstDate} = newLimit (TTL 25h)
3. merchant_cancel_usage UPDATE SET daily_limit=newLimit
   WHERE merchant_id=? AND kst_date=? (행 없으면 no-op)
4. offset commit

멱등성: UPDATE daily_limit=N 은 반복 실행해도 동일 결과 → 별도 UK 불필요
```

---

## 8. Resilience4j CircuitBreaker

```yaml
resilience4j:
  circuitbreaker:
    instances:
      merchant-limit:
        failureRateThreshold: 50
        slidingWindowSize: 10
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 5
        ignoreExceptions:
          - com.example.riskmanagement.infrastructure.http.MerchantNotFoundException
```

```
적용 위치: MerchantLimitRestClient.fetchDailyLimit()
OPEN 시 동작:
  3단계 조회에서 CB OPEN → CallNotPermittedException
  → DB 스냅샷 있으면 스냅샷으로 처리
  → DB 스냅샷 없으면 ServiceUnavailableException (503)

ignoreExceptions: MerchantNotFoundException (404) — 가맹점 없음은 장애 아님
```

---

## 9. 테스트 전략

| 레이어 | 종류 | 주요 시나리오 |
|--------|------|-------------|
| Domain | 단위 (순수 Java) | 한도 초과 → CancelLimitExceededException, restore 음수 방지 (max 0) |
| Application | 단위 (Mockito) | Redis hit → DB/HTTP 미호출 확인, DB 스냅샷 hit → HTTP 미호출 확인, CB OPEN + 스냅샷 없음 → 503, cancel_usage_history 존재 → no-op (이중 차감 방어), cancel_usage_compensation 존재 → ALREADY_COMPENSATED |
| Infrastructure/JPA | 통합 (Testcontainers MySQL) | cancel_usage_history UK 충돌, cancel_usage_compensation UK 충돌, merchant_cancel_usage upsert |
| Presentation | @WebMvcTest | 요청 유효성 검증, 에러 응답 포맷 (code/message) |

---

## 10. application.yml 주요 설정

```yaml
server:
  port: 8083

spring:
  application:
    name: risk-management-service
  datasource:
    url: jdbc:mysql://localhost:3306/risk_management_db
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: risk-management-service
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

external:
  redis:
    host: localhost
    port: 6379
  merchant-limit:
    base-url: http://localhost:8082

resilience4j:
  circuitbreaker:
    instances:
      merchant-limit:
        failureRateThreshold: 50
        slidingWindowSize: 10
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 5
```
