# Architecture guide

이 문서는 시스템 전체 설계의 단일 원본이다.
코드보다 이 문서가 먼저 작성되고, 코드는 이 문서를 따른다.

---

## 읽는 순서

서비스 하나를 이해하려면 이 순서로 읽는다.

```
1. domain           비즈니스 규칙, 상태 전이, 불변 조건
2. application      유스케이스, 인터페이스 선언, 트랜잭션 경계
3. infrastructure   영속성, Kafka, 외부 HTTP 클라이언트
4. presentation     컨트롤러, 요청/응답 DTO
```

의존 방향은 항상 안쪽을 향한다.

```
presentation → application → domain
infrastructure → domain
```

역방향 의존은 ArchUnit으로 빌드 시 감지한다.

---

## 왜 이 구조인가

취소 플로우는 여러 외부 시스템과 통신한다.

```
MySQL        모듈별 독립 DB
Kafka        3-broker 클러스터
PG사 HTTP    결제 취소 API
모듈 간 HTTP risk-management, merchant-limit 검증
```

이 의존들은 필요하지만 도메인 규칙을 결정해서는 안 된다.
domain과 application은 프레임워크 없이 테스트 가능하게 유지한다.

---

## 멀티 모듈 구조

각 모듈은 독립 인스턴스와 독립 DB를 가진다.

| 모듈 | 포트 | 소유 테이블 |
|------|------|-----------|
| `payment-service` | 8080 | payment, payment_item, payment_history, cancel_request, cancel_request_item, cancel_event_outbox, idempotency_key, compensation_retry, shedlock |
| `order-service` | 8081 | order, order_item, processed_cancel_event |
| `merchant-limit-service` | 8082 | merchant, merchant_cancel_limit, merchant_cancel_limit_history, shedlock |
| `risk-management-service` | 8083 | merchant_cancel_usage, cancel_usage_compensation, compensation_retry, shedlock |
| `product-service` | 8084 | product, product_version, product_sku, product_sku_attribute, product_attribute_type, product_attribute_value, product_stock, product_image, category |

모듈 간 DB 직접 접근은 금지한다.
데이터가 필요하면 HTTP 또는 Kafka를 경유한다.

---

## 모듈 간 통신 전략

### 동기 HTTP — 즉시 응답이 필요한 검증

```
결제 모듈 → risk-management-service
  취소 가능 여부 검증 + 가맹점 소진 한도 차감

risk-management-service → merchant-limit-service
  가맹점 daily_limit 조회 (당일 첫 요청 시 스냅샷)
```

동기 호출 실패는 즉시 취소 플로우를 중단한다.
Circuit Breaker (Resilience4j) 로 장애 전파를 막는다.

### 비동기 Kafka — 완료 후 상태 전파

```
payment-service → payment.cancelled → order-service
  취소 완료 후 OrderItem 상태 동기화
  파티션 키: payment_key (동일 결제건 순서 보장)
```

비동기 실패는 Retry 토픽 + DLQ로 처리한다.
상세 설계는 kafka-design.md를 참조한다.

---

## 취소 플로우

### 전체 흐름

```
클라이언트
  │
  ▼
payment-service
  │
  ├─ idempotency_key 중복 체크
  ├─ Payment 상태 검증
  ├─ PaymentItem 금액 검증
  │
  ▼
risk-management-service (동기 HTTP)
  │
  ├─ merchant-limit-service에서 daily_limit 조회
  ├─ merchant_cancel_usage FOR UPDATE
  ├─ 한도 초과 여부 판단
  └─ used_amount 선차감
  │
  ▼
payment-service
  │
  ├─ [단일 트랜잭션]
  │   cancel_request → COMPLETED
  │   PaymentItem 상태 변경
  │   cancel_event_outbox INSERT
  │
  ▼
Outbox 스케줄러
  │
  ▼
payment.cancelled (Kafka)
  │
  ▼
order-service Consumer
  │
  └─ OrderItem 상태 동기화
```

### 상태 머신

**CancelRequest**

```
PENDING
  └─ 한도 검증 통과 + used_amount 선차감 완료
      ↓
  PROCESSING
      ├─ PaymentItem 변경 + Outbox INSERT 완료 → COMPLETED
      └─ 처리 실패 → FAILED
```

PROCESSING 상태에서 서버 재시작 시:
복구 스케줄러가 5분 초과 건을 감지해 COMPLETED 방향으로 재처리한다.
이때 used_amount 선차감은 이미 완료된 것으로 간주하고 skip한다.

**Payment**

```
COMPLETED
  ├─ 부분취소 → PARTIAL_CANCELLED
  └─ 전액취소 → CANCELLED

PARTIAL_CANCELLED
  ├─ 추가 부분취소 → PARTIAL_CANCELLED (유지)
  └─ 잔액 전체취소 → CANCELLED
```

**PaymentItem**

```
ACTIVE
  ├─ 부분취소 → PARTIAL_CANCELLED
  └─ 전액취소 → CANCELLED

PARTIAL_CANCELLED
  └─ 잔액 전체취소 → CANCELLED
```

---

## 동시성 결정

### 가맹점 취소한도 — 비관적 락

```sql
SELECT * FROM merchant_cancel_usage
WHERE merchant_id = ? AND kst_date = ?
FOR UPDATE
```

낙관적 락을 선택하지 않은 이유:
- 한도 초과 시 재시도 자체가 무의미하다.
- 동시 요청 폭주 시 재시도 루프가 DB 부하를 증가시킨다.
- 취소한도 검증은 정확성이 처리량보다 중요하다.

### 멱등키 — UK 제약

```sql
UNIQUE KEY uk_idempotency_idem_key (idem_key)
```

동시에 동일 키로 요청이 와도 하나만 INSERT 성공한다.
실패 건은 DataIntegrityViolationException을 잡아
기존 결과를 반환한다.

---

## 멱등성 레이어

멱등성은 각 레이어에서 독립적으로 보장한다.

| 레이어 | 보장 수단 |
|--------|---------|
| API | Idempotency-Key 헤더 → idempotency_key 테이블 UK |
| used_amount 보상 | cancelRequestId → cancel_usage_compensation UK |
| Kafka Consumer | cancelRequestId → processed_cancel_event UK |
| 보상 재시도 | cancelRequestId → compensation_retry UK |

---

## 장애 복구 전략

| 장애 | 방어 수단 |
|------|---------|
| 서버 재시작 (PENDING/PROCESSING 잔존) | 복구 스케줄러 (5분 초과 건 재처리) |
| Kafka 발행 실패 | Outbox Pattern |
| Kafka Consumer 처리 실패 | Retry 토픽 (최대 3회) → DLQ |
| risk-management-service 장애 | Circuit Breaker → Fail-closed |
| merchant-limit-service 장애 | Circuit Breaker → Fail-closed |
| 보상 트랜잭션 실패 | compensation_retry → 지수 백오프 재시도 |
| 보상 5회 초과 (EXHAUSTED) | 운영팀 알림 → 수동 보정 |
| 스케줄러 중복 실행 | ShedLock (DB 기반 분산 락) |

### ShedLock 대상 스케줄러

| 스케줄러 | lock name | 실행 주기 |
|---------|-----------|---------|
| 취소 복구 | `cancel-recovery` | 60초 |
| Outbox 발행 | `outbox-publisher` | 10초 |
| 보상 재시도 | `compensation-retry` | 30초 |

---

## Outbox Pattern

취소 완료 후 Kafka에 직접 발행하지 않는다.

```
[단일 DB 트랜잭션]
  1. PaymentItem 상태 변경
  2. CancelRequest → COMPLETED
  3. cancel_event_outbox INSERT (status=PENDING)

[Outbox 스케줄러 - 별도 실행]
  PENDING 행 조회 → Kafka 발행 → PUBLISHED 업데이트
```

DB 커밋 성공 후 Kafka 발행 실패로 인한 이벤트 유실을 막는다.

---

## 시간 처리

서버와 DB는 UTC로 일관한다.
프론트엔드에서 KST → UTC 변환 후 전달한다.

가맹점 취소한도 기준일은 KST 날짜 기준이다.

```java
// 서버 코드에서 KST 오늘 날짜 계산
LocalDate kstToday = LocalDate.now(ZoneId.of("Asia/Seoul"));
```

DB에는 `kst_date DATE` 컬럼으로 저장한다.

---

## 패키지 구조

```
{module}-service/
├── src/
│   ├── main/java/com/example/{module}/
│   │   ├── domain/
│   │   │   ├── entity/
│   │   │   ├── service/
│   │   │   └── policy/
│   │   ├── application/
│   │   │   ├── usecase/
│   │   │   ├── service/
│   │   │   └── interfaces/
│   │   ├── infrastructure/
│   │   │   ├── persistence/
│   │   │   ├── messaging/
│   │   │   ├── http/
│   │   │   └── config/
│   │   └── presentation/
│   │       ├── controller/
│   │       └── dto/
│   └── resources/
│       └── application.yml
└── db/
    └── migration/
        └── V1__*.sql
```

---

## 테스트 전략

테스트는 레이어별로 분리한다.

| 테스트 종류 | 범위 | 도구 |
|-----------|------|------|
| domain 테스트 | 비즈니스 불변 조건 | 순수 JUnit |
| application 테스트 | 유스케이스 오케스트레이션 | Mockito |
| infrastructure 테스트 | DB 매핑, Kafka 직렬화 | Testcontainers |
| presentation 테스트 | 입력 검증, 응답 형식 | MockMvc |
| 아키텍처 테스트 | 의존 방향 규칙 | ArchUnit |
| 동시성 테스트 | 한도 차감, 멱등키 | Testcontainers + 멀티스레드 |

각 테스트는 해당 레이어만 로드한다.
전체 Spring 컨텍스트를 시작하는 테스트는 통합 테스트로 분리한다.

---

## 모니터링 메트릭

| 메트릭 | 수집 위치 | 알림 기준 |
|--------|---------|---------|
| `cancel_request_total{status}` | payment-service | - |
| `cancel_processing_stuck_count` | 복구 스케줄러 | 1건 이상 |
| `outbox_pending_lag` | Outbox 스케줄러 | 5분 초과 |
| `compensation_retry_exhausted` | 보상 스케줄러 | 1건 이상 즉시 |
| `kafka_consumer_lag` | order-service | 1,000 초과 |
| `dlq_message_count` | Kafka DLQ | 1건 이상 즉시 |
| `merchant_limit_cb_state` | Circuit Breaker | OPEN 진입 즉시 |