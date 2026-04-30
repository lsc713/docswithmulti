# CLAUDE.md

Claude Code가 세션 시작 시 가장 먼저 읽는 파일이다.
이 파일을 읽은 후 아래 참조 문서를 순서대로 읽어라.

---

## 프로젝트 개요

패션 이커머스 결제 취소 시스템.
핵심은 결제 취소 플로우의 멱등성, 동시성, 부분취소 처리다.

### 모듈 구성

| 모듈 | 역할 | 포트 |
|------|------|------|
| `payment-service` | 결제 취소 핵심 로직 | 8080 |
| `order-service` | 주문/주문아이템 상태 동기화 | 8081 |
| `merchant-limit-service` | 가맹점별 일일 취소한도 원본 관리 | 8082 |
| `risk-management-service` | 취소 가능 여부 검증 + 소진 한도 관리 | 8083 |
| `product-service` | 상품/SKU/재고 관리 | 8084 |

### 기술 스택

| 항목 | 기술 |
|------|------|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 3.x |
| ORM | Spring Data JPA + QueryDSL |
| DB | MySQL 8.0 (모듈별 독립) |
| 마이그레이션 | Flyway |
| 메시징 | Kafka 3.x (3-broker 클러스터) |
| 빌드 | Gradle |
| 테스트 | JUnit 5 + Mockito + Testcontainers |

---

## 필수 참조 문서

작업 시작 전 반드시 아래 순서로 읽어라.
코드보다 문서가 먼저다.

```
@docs/domain-rules.md      비즈니스 규칙 원본
@docs/error-catalog.md     에러 코드 전체 목록
@docs/api-spec.md          API 요청/응답 스펙
@docs/db-schema.md         DB 규칙 및 인덱스 전략
@docs/kafka-design.md      Kafka 설계 및 운영
@docs/architecture.md      전체 시스템 설계
@docs/contributing.md      코드 작성 기준
@docs/agent.md             작업 행동 규칙
```

취소 플로우 전체 설계는 반드시 읽어라.

```
@docs/cancel-design.md     취소 플로우 상세 설계
                           (멱등성, TX 경계, 스케줄러, Kafka 페이로드 등)
```

DDL 확인 시 직접 파일을 읽어라.

```
payment-service:         db/migration/V1__create_payment_core.sql ~ V7
order-service:           db/migration/V1__create_order_core.sql
merchant-limit-service:  db/migration/V1__create_merchant_limit_core.sql
risk-management-service: db/migration/V1__create_risk_core.sql
product-service:         db/migration/V1__create_product_core.sql
```

---

## 핵심 설계 원칙

구현 전 반드시 숙지해라.
cancel-design.md를 읽지 않고 취소 관련 코드를 작성하는 것을 금지한다.

### 멱등성

```
request_hash = SHA-256(paymentKey + paymentItemIds 오름차순 정렬)
cancel_request (payment_id, request_hash) UNIQUE KEY
→ TX 1에서 따닥 요청 차단
→ Idempotency-Key 헤더 없음 (서버가 직접 생성)

FAILED 건 재시도:
  새 INSERT 금지 → 기존 FAILED 건을 PENDING으로 UPDATE
```

### TX 경계

```
TX 1: CancelRequest PENDING INSERT (risk 호출 전)
TX 2: CancelRequest PROCESSING UPDATE (risk 성공 후)
TX 3: PaymentItem + Payment + CancelRequest(COMPLETED) + Outbox
      → 하나의 트랜잭션으로 원자적 처리

이력(cancel_request_history):
  항상 TX 밖에서 별도 실행
  TX 1 커밋 후 별도 / TX 2 커밋 후 별도 / TX 3 커밋 후 별도
  실패해도 상태 변경은 유지 (이력은 보조 데이터)
```

### TX 3 주의사항

```
PaymentItem 재조회 필수:
  TX 1 이전 조회 데이터 사용 금지
  TX 3에서 findAllByPaymentIdForUpdate() 로 최신 상태 재조회
  → 동시 취소 시 Payment 상태 불일치 방지

Payment 상태 재계산:
  findByIdForUpdate() 후 전체 PaymentItem 기준으로 재계산
  isActive(): COMPLETED or PARTIAL_CANCELLED → 취소 가능
```

### daily_limit 조회 순서

```
1순위: Redis (daily_limit:{merchantId}:{kstDate})
2순위: merchant_cancel_usage.daily_limit (DB 스냅샷)
       → Redis 장애 시 merchant-limit 호출 없이 처리
       → 서버 간 의존성 최소화 (금융 도메인)
3순위: merchant-limit HTTP 조회
       → 최초 요청 또는 스냅샷 없는 경우에만

2순위를 건너뛰고 바로 3순위 호출 금지
```

### 스케줄러 (payment-service, Redis 분산락)

```
pending-recovery (60초):
  PENDING 5분 초과 → risk check → 차감됐으면 보상 → FAILED

processing-recovery (60초):
  PROCESSING 5분 초과 → PG사 조회 → TX 3 재실행 또는 보상
  TX 3 재실행 시 Kafka 발행도 함께 재시도

compensation-retry (30초):
  compensation_retry 테이블 → risk 보상 API 재시도
```

### Kafka 페이로드

```
payment.cancelled:
  { cancelRequestId, paymentKey, merchantId,
    cancelledItems: [{ paymentItemId, orderItemId, itemAmount }], cancelledAt }
  TX3 마지막에 kafkaTemplate.send() 직접 호출
  발행 실패 시 TX3 롤백 → processing-recovery 재처리

merchant.limit.updated:
  { merchantId }
  파티션 키: merchantId → 순서 보장
```

---

## 핵심 실행 명령어

### 빌드 및 실행

```bash
# 전체 빌드
./gradlew build

# 특정 모듈 빌드
./gradlew :payment-service:build

# 테스트 실행
./gradlew test

# 특정 모듈 테스트
./gradlew :payment-service:test

# 로컬 실행 (Docker Compose)
docker-compose up -d
```

### DB 마이그레이션

```bash
# 마이그레이션 실행 (애플리케이션 시작 시 자동 실행)
./gradlew :payment-service:flywayMigrate

# 마이그레이션 상태 확인
./gradlew :payment-service:flywayInfo
```

### Kafka

```bash
# Kafka UI 접근
http://localhost:8989

# 토픽 목록 확인
docker exec -it kafka1 kafka-topics.sh --list --bootstrap-server localhost:9092

# 메시지 실시간 확인
docker exec -it kafka1 kafka-console-consumer.sh \
  --topic payment.cancelled \
  --bootstrap-server localhost:9092 \
  --from-beginning
```

## 문서 검색 규칙

파일을 직접 읽기 전에 항상 qmd로 먼저 검색한다.

- query — 하이브리드 검색
- get — 문서 조회 (경로 또는 ID)
- multi_get — 배치 조회
- status — 인덱스 상태

qmd 결과가 충분하지 않을 때만 파일을 직접 읽는다.

---

## 패키지 구조

각 모듈은 아래 레이어 구조를 따른다.

```
{module}
└── src/main/java/com/example/{module}
    ├── common
    │   └── exception       BusinessException (모든 예외의 부모)
    ├── domain
    │   ├── entity          엔티티, 값객체
    │   ├── service         도메인 서비스
    │   ├── policy          정책 객체
    │   └── exception       비즈니스 규칙 위반 예외만
    ├── application
    │   ├── usecase         유스케이스 인터페이스
    │   ├── service         유스케이스 구현체
    │   ├── interfaces      외부 시스템 계약 (인터페이스)
    │   └── exception       리소스 없음, 멱등 중복 예외
    ├── infrastructure
    │   ├── persistence     JPA 구현체
    │   ├── messaging       Kafka Producer/Consumer
    │   ├── http            외부 HTTP 클라이언트
    │   ├── config          Spring 설정
    │   └── exception       외부 연동 실패 예외
    └── presentation
        ├── controller      REST 컨트롤러
        └── dto             요청/응답 DTO
```

### 예외 계층 원칙

```
common/exception
  BusinessException          모든 커스텀 예외의 부모
    errorCode: String        error-catalog.md 코드와 1:1 매핑
    httpStatus: int

domain/exception             비즈니스 규칙 위반만
  InvalidCancelAmountException
  InvalidPaymentStatusException
  CancelPeriodExpiredException
  InvalidCancelStateTransitionException

application/exception        리소스 없음, 멱등 중복
  PaymentNotFoundException
  CancelRequestNotFoundException
  IdempotentDuplicationException

infrastructure/exception     외부 연동 실패
  MerchantLimitServiceException
  RiskServiceException
```

presentation에서는 BusinessException을 잡아
errorCode 기반으로 에러 응답을 통일한다.

의존 방향: presentation → application → domain
infrastructure → domain (단방향, 역방향 금지)

---

## 현재 작업 상태

### 완료

- [x] 전체 시스템 설계
- [x] 도메인 규칙 확정
- [x] 에러 카탈로그 확정
- [x] API 스펙 확정
- [x] Kafka 설계 확정
- [x] 전체 모듈 DDL 작성 (Flyway V1~V7)
- [x] 취소 플로우 상세 설계 (cancel-design.md)
- [x] Circuit Breaker 설계
- [x] 스케줄러 4개 설계 (pending-recovery, processing-recovery, outbox-publisher, compensation-retry)

### 진행 중

- [ ] payment-service 구현
- [ ] order-service 구현
- [ ] merchant-limit-service 구현
- [ ] risk-management-service 구현
- [ ] product-service 구현

### 구현 우선순위

```
1. payment-service          핵심 취소 플로우
2. risk-management-service  취소 검증 + 한도 소진
3. merchant-limit-service   한도 원본 관리
4. order-service            Kafka Consumer + 상태 동기화
5. product-service          상품/SKU/재고
```

---

## 절대 하지 말아야 할 것

```
- 운영 DB에 직접 DDL 실행 금지 (Flyway를 통해서만)
- Flyway로 적용된 파일 수정 금지 (새 버전 파일로 추가)
- 모듈 간 DB 직접 접근 금지 (HTTP 또는 Kafka 경유)
- domain 레이어에 Spring/JPA 어노테이션 추가 금지
- 시크릿/비밀번호 코드에 하드코딩 금지
- 테스트 없이 구현 완료 처리 금지
- domain-rules.md를 확인하지 않고 비즈니스 로직 작성 금지

[취소 플로우 전용]
- cancel-design.md를 읽지 않고 취소 관련 코드 작성 금지
- request_hash 없이 CancelRequest INSERT 금지
- 이력(cancel_request_history)을 TX 1/2/3 안에 포함 금지
  (이력 실패로 비즈니스 로직 롤백 방지)
- TX 3에서 조회 시점 데이터로 Payment 상태 재계산 금지
  (반드시 findAllByPaymentIdForUpdate() 로 재조회)
- daily_limit 조회 시 Redis Miss → 바로 merchant-limit HTTP 호출 금지
  (DB 스냅샷 먼저 확인)
- FAILED 건 재시도 시 새 INSERT 금지
  (기존 FAILED 건을 PENDING으로 UPDATE)
```

---

## 모호한 요구사항 처리

요구사항이 불명확할 때:
1. domain-rules.md와 cancel-design.md에서 관련 규칙 먼저 확인
2. 문서에 없으면 가정을 1문장으로 명시 후 진행
3. 가정 내용을 응답 첫 줄에 표시

예시:
```
가정: 취소 기간 초과 여부는 Payment.created_at 기준으로 판단한다.
```