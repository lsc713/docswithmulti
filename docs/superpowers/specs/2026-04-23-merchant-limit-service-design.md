# merchant-limit-service Implementation Design

**Goal:** 가맹점별 일일 취소한도 원본 관리 서비스 구현 — internal API(risk-management 호출), 관리자 CRUD API, `merchant.limit.updated` Kafka 이벤트 발행(Outbox 패턴)

**Architecture:** payment-service와 동일한 Hexagonal 구조(domain → application → infrastructure → presentation). 도메인 레이어는 Spring/JPA 의존 없이 순수 Java. 인증/인가는 구현 범위 외(API Gateway 위임).

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Data JPA, MySQL 8.0, Flyway, Kafka 3.x, Resilience4j(Redis 분산락), JUnit 5 + Mockito + Testcontainers

---

## 1. 패키지 구조

```
com.example.merchantlimit
├── MerchantLimitApplication.java
├── common/exception/
│   ├── BusinessException.java
│   └── ErrorCode.java
├── domain/
│   ├── entity/
│   │   ├── Merchant.java                 (id, merchantKey, name, status, cancelPeriodDays)
│   │   ├── MerchantStatus.java           (ACTIVE / INACTIVE / SUSPENDED)
│   │   ├── MerchantCancelLimit.java      (id, merchantId, dailyLimit, updatedAt)
│   │   └── LimitHistory.java             (id, merchantId, oldLimit, newLimit, reason, createdAt)
│   ├── service/
│   │   └── MerchantLimitDomainService.java  (한도 변경 가능 여부 검증)
│   └── exception/
│       ├── MerchantNotFoundException.java
│       ├── MerchantSuspendedException.java
│       └── InvalidLimitAmountException.java
├── application/
│   ├── usecase/
│   │   ├── GetCancelLimitUseCase.java
│   │   └── UpdateCancelLimitUseCase.java
│   ├── service/
│   │   ├── GetCancelLimitService.java
│   │   └── UpdateCancelLimitService.java
│   └── interfaces/
│       ├── MerchantRepository.java
│       ├── MerchantCancelLimitRepository.java
│       ├── LimitHistoryRepository.java
│       └── LimitEventOutboxRepository.java
├── infrastructure/
│   ├── persistence/
│   │   ├── MerchantJpaEntity.java
│   │   ├── MerchantJpaRepository.java
│   │   ├── MerchantRepositoryImpl.java
│   │   ├── MerchantCancelLimitJpaEntity.java
│   │   ├── MerchantCancelLimitJpaRepository.java
│   │   ├── MerchantCancelLimitRepositoryImpl.java
│   │   ├── LimitHistoryJpaEntity.java
│   │   ├── LimitHistoryJpaRepository.java
│   │   ├── LimitHistoryRepositoryImpl.java
│   │   ├── LimitEventOutboxJpaEntity.java
│   │   ├── LimitEventOutboxJpaRepository.java
│   │   └── LimitEventOutboxRepositoryImpl.java
│   ├── messaging/
│   │   ├── LimitEventKafkaProducer.java
│   │   └── OutboxPublisherScheduler.java   (10초 주기, Redis 분산락)
│   └── config/
│       ├── PersistenceConfig.java
│       ├── KafkaProducerConfig.java
│       └── RedisConfig.java
└── presentation/
    ├── controller/
    │   ├── InternalMerchantController.java   (risk-management 호출용)
    │   └── AdminMerchantController.java      (관리자 API)
    ├── dto/
    │   ├── CreateMerchantRequest.java
    │   ├── UpdateLimitRequest.java
    │   ├── PatchMerchantStatusRequest.java
    │   ├── MerchantResponse.java
    │   ├── CancelLimitResponse.java
    │   └── LimitHistoryResponse.java
    └── GlobalExceptionHandler.java
```

---

## 2. 도메인 모델

### Merchant

```java
public class Merchant {
    private Long id;
    private String merchantKey;   // UK, 외부 식별자
    private String name;
    private MerchantStatus status;
    private int cancelPeriodDays;

    // 상태 전이
    public void activate()   { /* INACTIVE → ACTIVE */ }
    public void deactivate() { /* ACTIVE → INACTIVE */ }
    public void suspend()    { /* ACTIVE/INACTIVE → SUSPENDED */ }

    // 한도 변경 가능 여부 (SUSPENDED 차단)
    public void validateLimitChangeable() {
        if (status == MerchantStatus.SUSPENDED)
            throw new MerchantSuspendedException(id);
    }
}
```

### MerchantCancelLimit

```java
public class MerchantCancelLimit {
    private Long id;
    private Long merchantId;
    private BigDecimal dailyLimit;

    // 한도 금액 최소 1원 검증
    public static MerchantCancelLimit create(long merchantId, BigDecimal dailyLimit) {
        if (dailyLimit.compareTo(BigDecimal.ONE) < 0)
            throw new InvalidLimitAmountException(dailyLimit);
        ...
    }

    public void update(BigDecimal newLimit) {
        if (newLimit.compareTo(BigDecimal.ONE) < 0)
            throw new InvalidLimitAmountException(newLimit);
        this.dailyLimit = newLimit;
    }
}
```

### MerchantLimitDomainService

```java
public class MerchantLimitDomainService {
    // Merchant 상태 검증 후 한도 변경 위임
    public void updateLimit(Merchant merchant, MerchantCancelLimit limit, BigDecimal newLimit) {
        merchant.validateLimitChangeable();
        limit.update(newLimit);
    }
}
```

---

## 3. DB / DDL (V1)

```sql
-- V1__create_merchant_limit_core.sql

CREATE TABLE merchant (
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

-- 현재 유효 취소 한도 (가맹점당 1행)
CREATE TABLE merchant_cancel_limit (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT        NOT NULL,
    daily_limit DECIMAL(19,2) NOT NULL,
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_cancel_limit_merchant_id (merchant_id)
);

-- 한도 변경 이력 (domain-rules.md 3-7)
CREATE TABLE merchant_cancel_limit_history (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT        NOT NULL,
    old_limit   DECIMAL(19,2) NULL,
    new_limit   DECIMAL(19,2) NOT NULL,
    reason      VARCHAR(500)  NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_limit_history_merchant_id (merchant_id)
);

-- Kafka Outbox
CREATE TABLE limit_event_outbox (
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

**설계 결정:**
- `merchant_cancel_limit`은 가맹점당 1행(현재 유효 한도). 날짜별 스냅샷은 risk-service의 `merchant_cancel_usage` 담당
- `limit_event_outbox`에 merchant_id UK 없음 — 하루에 여러 번 한도 변경 가능
- FK 제약 없음 — 앱 레벨 관리 (db-schema.md 원칙)

---

## 4. API 설계

### Internal API (risk-management 호출, port 8082)

```
GET /internal/merchants/{merchantId}/cancel-limit

응답 200:
{
  "merchantId": 1,
  "dailyLimit": 5000000,
  "merchantStatus": "ACTIVE"
}

응답 404: { "code": "MERCHANT_NOT_FOUND" }
응답 422: { "code": "MERCHANT_CANCEL_LIMIT_NOT_FOUND" }
```

### Admin API

```
POST /admin/merchants
요청: { "merchantKey": "mct_001", "name": "패션몰A", "cancelPeriodDays": 90 }
응답 201: { "merchantId": 1, "merchantKey": "mct_001", "name": "패션몰A", "status": "ACTIVE" }
응답 409: { "code": "MERCHANT_KEY_DUPLICATED" }

PATCH /admin/merchants/{merchantId}/status
요청: { "status": "SUSPENDED" }
응답 200: { "merchantId": 1, "status": "SUSPENDED" }

PUT /admin/merchants/{merchantId}/cancel-limit
요청: { "dailyLimit": 5000000, "reason": "프로모션 시즌 한도 증액" }
응답 200: { "merchantId": 1, "dailyLimit": 5000000, "updatedAt": "..." }
응답 422: { "code": "MERCHANT_SUSPENDED" }
응답 422: { "code": "INVALID_LIMIT_AMOUNT" }

GET /admin/merchants/{merchantId}/cancel-limit/history?page=0&size=20
응답 200:
{
  "content": [
    { "oldLimit": 3000000, "newLimit": 5000000, "reason": "프로모션", "changedAt": "..." }
  ],
  "totalElements": 5, "page": 0, "size": 20
}

GET /admin/merchants?page=0&size=20&status=ACTIVE
응답 200:
{
  "content": [{ "merchantId": 1, "merchantKey": "mct_001", "name": "패션몰A", "status": "ACTIVE" }],
  "totalElements": 10, "page": 0, "size": 20
}
```

### 에러 코드

| code | HTTP | 설명 |
|------|------|------|
| `MERCHANT_NOT_FOUND` | 404 | 가맹점 없음 |
| `MERCHANT_KEY_DUPLICATED` | 409 | merchantKey 중복 |
| `MERCHANT_CANCEL_LIMIT_NOT_FOUND` | 422 | 한도 미설정 |
| `INVALID_LIMIT_AMOUNT` | 422 | 한도 1원 미만 |
| `MERCHANT_SUSPENDED` | 422 | SUSPENDED 가맹점 한도 변경 |

---

## 5. Kafka / Outbox

### 이벤트 페이로드 — `merchant.limit.updated`

```json
{
  "merchantId": 1,
  "newLimit": 5000000,
  "kstDate": "2026-04-23"
}
```

| 항목 | 값 |
|------|-----|
| 토픽 | `merchant.limit.updated` |
| 파티션 키 | `merchantId` (가맹점별 순서 보장) |
| 파티션 수 | 3 |
| Consumer | risk-management-service (Redis 갱신 + merchant_cancel_usage UPDATE) |

### Outbox 플로우

```
PUT /admin/merchants/{id}/cancel-limit
  └── 단일 트랜잭션
        ├── merchant_cancel_limit UPDATE or INSERT
        ├── merchant_cancel_limit_history INSERT
        └── limit_event_outbox INSERT (status=PENDING, payload=JSON)

OutboxPublisherScheduler (10초 주기)
  └── PENDING 1000건 조회
        └── KafkaProducer.send(merchantId 파티션 키)
              └── 성공 → status=PUBLISHED, published_at=now
```

### 스케줄러 Redis 분산락

| 항목 | 값 |
|------|-----|
| 주기 | 10초 |
| 배치 크기 | 1000건 |
| Redis 락 키 | `lock:merchant-limit:outbox-publisher` |
| lockAtMostFor | 9초 |

---

## 6. 테스트 전략

| 레이어 | 종류 | 주요 시나리오 |
|--------|------|------------|
| Domain | 단위 (순수 Java) | `InvalidLimitAmountException` (0원), SUSPENDED → 한도 변경 거부, 상태 전이 |
| Application | 단위 (Mockito) | 한도 미설정 가맹점 조회 → 422, 한도 변경 → outbox INSERT 확인, 가맹점 미존재 → 404 |
| Infrastructure/JPA | 통합 (Testcontainers MySQL) | UK 충돌 (merchantKey 중복, merchant_cancel_limit UK), outbox 상태 전이 |
| Presentation | `@WebMvcTest` | 요청 유효성 검증, 에러 응답 포맷 (code/message) |

---

## 7. application.yml 주요 설정

```yaml
server:
  port: 8082

spring:
  application:
    name: merchant-limit-service
  datasource:
    url: jdbc:mysql://localhost:3306/merchant_limit_db
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      enable-idempotence: true
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

external:
  redis:
    host: localhost
    port: 6379
```
