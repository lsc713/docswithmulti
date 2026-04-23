# 이커머스 결제 취소 시스템

**기간:** 2026.04.01 ~ 2026.04.15  
**역할:** 백엔드 시스템 설계  
**규모:** 5개 마이크로서비스, Kafka 클러스터(3 broker), MySQL 5개 독립 DB

---

## 프로젝트 개요

분산 환경에서 결제 취소 시 발생하는 **멱등성·동시성·부분취소** 문제를 해결한 이커머스 결제 취소 시스템을 설계했습니다.

결제 취소는 단순해 보이지만 실제 운영 환경에서는 네트워크 재시도로 인한 중복 환불, 가맹점 한도 동시 차감, 서버 재시작 시 데이터 불일치 등 복합적인 문제가 동시에 발생합니다. 이를 각각 적합한 패턴으로 해결했습니다.

---

## 기술 스택

Java 21 / Spring Boot 3.x / Spring Data JPA / MySQL 8.0 / Kafka 3.x / Flyway / Gradle 멀티 모듈

---

## 핵심 문제와 해결

### 1. 멱등성 — 중복 취소 요청 방어

**문제:** 네트워크 타임아웃 후 클라이언트 재시도 시 동일 취소가 2번 실행되어 환불이 2회 발생

**핵심 설계 결정 — Idempotency-Key 생성 주체:**

클라이언트가 UUID를 직접 생성해 헤더로 전달하는 방식을 채택했습니다.

| 방식 | 장점 | 단점 |
|------|------|------|
| 클라이언트 생성 UUID (채택) | 재시도 여부를 클라이언트가 결정, 부분취소 횟수 제한 없음 | 클라이언트 구현 필요 |
| 서버가 생성해서 내려줌 | 클라이언트 구현 단순 | 재시도 시 키 유실 가능, 부분취소 횟수 사전에 알 수 없음 |
| paymentHistory 기반 복합키 | 별도 키 관리 불필요 | 부분취소 추가 시도 시 동일 키 생성 → 정상 요청 차단 |

paymentHistory 기반 복합키(orderId+paymentItemId)를 검토했으나, 같은 PaymentItem에 대해 50만원 부분취소 후 추가 30만원 취소 시도 시 동일 키가 생성되어 정상 요청이 차단되는 문제로 미채택했습니다.

**4개 레이어 멱등성 적용:**

| 레이어 | 장치 |
|--------|------|
| API 진입 | Idempotency-Key UUID + DB Unique Key (24시간 TTL) |
| Kafka Consumer | processed_cancel_event Unique Key |
| 보상 트랜잭션 | cancel_usage_compensation Unique Key |
| 보상 재시도 | compensation_retry Unique Key |

**기술적 판단:**
- Redis 대신 MySQL UK 선택 → Redis 장애 시 멱등성이 깨지면 환불 2회 발생 (금융 사고) → DB 영속성 활용
- paymentHistory 테이블 대신 별도 idempotency_key 테이블 분리 → TPS 10,000 시 하루 864,000만 건 누적 방지, 단일 책임
- 중복 요청 시 에러(409) 대신 기존 응답(200) 반환 → 멱등성 = 동일 요청 N번 = 동일 결과

---

### 2. 동시성 — 3가지 케이스 각각 해결

**케이스 1 — 동일 요청 중복 (네트워크 재시도)**
- 해결: Idempotency-Key DB Unique Key

**케이스 2 — 가맹점 한도 동시 차감**

문제: 한도 100만원에 A, B가 70만원씩 동시 요청 시 140만원 취소 가능

해결: `SELECT ... FOR UPDATE` (Pessimistic Lock)
- 조회와 차감을 단일 트랜잭션에서 원자적으로 처리
- Optimistic Lock 미채택 이유: 한도 초과 시 재시도해도 동일하게 실패 → 재시도만 증가, 비관적 락과 결과 동일
- Redis 분산락 미채택 이유: Redis 장애 시 취소 전체 불가, 추가 인프라 불필요

**케이스 3 — 동일 PaymentItem 동시 수정**

문제: 고객·가맹점이 서로 다른 UUID로 동시에 같은 항목 취소 시도 → Idempotency-Key로 방어 불가

해결: PaymentItem `@Version` Optimistic Lock
- version 불일치 시 OptimisticLockException → 재조회 후 재시도 또는 에러

---

### 3. 분산 트랜잭션 — SAGA 패턴 (Choreography)

**문제:** HTTP 경계를 넘으면 원자성이 깨짐. risk-management-service 커밋 후 payment-service 실패 시 한도는 차감됐는데 취소는 안 된 상태

**해결:** 트랜잭션 3단계 분리 + 보상 트랜잭션

```
TX 1: CancelRequest PENDING INSERT (risk 호출 전 별도 커밋)
      → 이후 서버 다운 시 스케줄러 추적 가능

HTTP: risk-management-service (used_amount 선차감)

TX 2: CancelRequest → PROCESSING (별도 커밋)
      → 이 시점부터 "한도 차감 완료"를 DB에 기록

HTTP: PG사 취소 API

TX 3: PaymentItem + Payment + COMPLETED + Outbox + idempotency_key (단일 커밋)
      → 하나라도 실패 시 전부 롤백
```

**보상 트랜잭션:** risk 커밋 후 payment 실패 시 즉시 보상 API 호출 → 실패 시 compensation_retry → 지수 백오프 재시도 (30초/1분/2분/4분/EXHAUSTED)

**TX 3 멱등성 보장:** 복구 스케줄러가 TX 3을 재시도해도 안전하도록 각 단계에 UK 제약 적용

**2PC 미채택 이유:** 성능 오버헤드, DB-Kafka 간 2PC 표준 미지원

---

### 4. Outbox Pattern — DB-Kafka 원자성 보장

**문제:** 취소 완료 DB 커밋 후 Kafka 발행 전 서버 다운 시 이벤트 영구 유실

**해결:** cancel_event_outbox 테이블을 TX 3에 포함해 원자적으로 INSERT → 별도 스케줄러가 PENDING 행 조회 후 Kafka 발행

```
Case 1: DB 커밋 후 서버 다운 → 재시작 후 스케줄러가 PENDING 행 발견 → 재발행
Case 2: Kafka 발행 성공, PUBLISHED 업데이트 전 다운 → 재발행 → Consumer UK로 중복 처리 방어
Case 3: DB 커밋 실패 → Outbox도 롤백 → 이벤트 미발행 (정상)
```

**CDC(Debezium) 미채택 이유:** 추가 인프라(binlog 파이프라인) 대비 현재 규모에서 과잉

---

### 5. 서버 재시작 내구성 — CancelRequest 상태 머신

**문제:** 취소 처리 중 서버 재시작 시 어디까지 처리됐는지 알 수 없어 데이터 불일치

**해결:** CancelRequest 상태 머신으로 재시작 후 복구 위치 결정

```
PENDING    → risk 호출 전  → 처음부터 재처리
PROCESSING → risk 완료 후  → TX 3만 재처리 (used_amount 재차감 금지)
COMPLETED  → 최종 완료     → 처리 없음
FAILED     → 보상 필요     → compensation_retry 확인
```

복구 스케줄러가 5분 초과 PENDING/PROCESSING 건 감지 → PG사 결과 조회 후 적절한 경로로 재처리

---

### 6. 가맹점 취소한도 모듈 분리 설계

**설계 결정:** merchant-limit-service(한도 원본)와 risk-management-service(소진 추적) 분리

**분리 이유:**
- 변경 주기 다름: 한도는 계약 변경 시, 소진은 매 취소 요청마다
- 부하 특성 다름: 소진은 고빈도 쓰기 + FOR UPDATE, 한도는 저빈도 읽기
- 한도 정책 배포가 소진 추적에 영향 주면 안 됨

**daily_limit 조회 전략:** DB 스냅샷 (당일 첫 요청만 HTTP 호출, 이후 자체 DB)
- Redis + DB 폴백 대안 검토: TPS 1,000 이상 시 전환 검토 대상
- 매 요청 HTTP 미채택: TPS 10,000 시 10,000 req/s HTTP 트래픽, merchant-limit 장애 = 전체 장애

---

### 7. 분산 스케줄러 — ShedLock

**문제:** 복구/Outbox/보상 스케줄러가 여러 인스턴스에서 동시 실행 시 중복 처리

**해결:** ShedLock (DB 행 기반 분산락)
- `@SchedulerLock(name = "cancel-recovery", lockAtMostFor = "55s")`
- 전체 클러스터에서 동시에 하나의 인스턴스만 실행 보장
- 인스턴스 다운 시 55초 후 자동 해제

**Redis 분산락 미채택 이유:** MySQL 이미 사용 중이라 추가 인프라 없이 동일 효과. Redis 장애 시 스케줄러 전체 중단 위험

---

### 8. Kafka 설계

**토픽 구조:**
```
payment.cancelled       파티션 10개 / 7일 보존
payment.cancelled.retry 파티션 10개 / 7일 보존
payment.cancelled.DLQ   파티션 3개  / 30일 보존
```

**설계 결정:**
- 파티션 키: payment_key → 동일 결제건 이벤트의 파티션 내 순서 보장
- At-least-once + Consumer 멱등성 채택 → Exactly-once 대비 Kafka 트랜잭션 오버헤드 없음
- 수동 offset 커밋 → 처리 완료(DB 커밋) 후에만 커밋 → 처리 중 장애 시 재처리 보장

**DLQ 전략:** 일시적 오류 → retry 토픽 (지수 백오프: 1분/5분/10분), 데이터 오류 → 즉시 DLQ, 3회 초과 → DLQ → 운영팀 알림

---

## 아키텍처 패턴 요약

| 패턴 | 적용 위치 | 해결한 문제 |
|------|---------|----------|
| Idempotency Key | API 레이어 | 네트워크 재시도 중복 요청 |
| Pessimistic Lock | 가맹점 한도 차감 | 동시 한도 초과 |
| Optimistic Lock | PaymentItem | 동일 항목 동시 수정 |
| SAGA (Choreography) | 전체 취소 플로우 | HTTP 경계 분산 트랜잭션 |
| Outbox Pattern | Kafka 이벤트 발행 | DB-Kafka 원자성 |
| Compensation Transaction | used_amount 원복 | 부분 실패 복구 |
| State Machine | CancelRequest | 서버 재시작 내구성 |
| Circuit Breaker | 외부 서비스 호출 | 장애 격리 (Fail-closed) |
| ShedLock | 스케줄러 | 분산 환경 중복 실행 방지 |
| Snapshot | 결제 시점 데이터 | 모듈 간 데이터 독립 |
| DLQ + Retry Topic | Kafka Consumer | 처리 실패 격리 |

---

## 경력기술서 한 줄 표현 예시

> 분산 환경에서 멱등성·동시성·분산 트랜잭션 문제를 해결하는 이커머스 결제 취소 시스템을 설계했습니다. Idempotency Key, Pessimistic/Optimistic Lock, SAGA 패턴, Outbox Pattern을 각 문제에 적합하게 적용하고, CancelRequest 상태 머신으로 서버 재시작 내구성을 보장했습니다.


---

## 다음 세션에서 다룰 것

### 코드 흐름 (이어서)
- 스케줄러 코드 (복구/Outbox/보상재시도)
- Kafka Consumer 흐름

### 추가 질문 목록

1. TX별 CancelRequest 변경 실패 케이스
   - TX 1 실패: 롤백, 클라이언트 재시도
   - TX 2 실패: PENDING 상태로 남음 → 스케줄러 재처리 시 이중 차감 위험 (설계 취약점)
   - TX 3 실패: PROCESSING 상태로 남음 → PG사 조회 후 TX 3 재시도

2. PG사 성공 후 Outbox INSERT 실패 시 처리

3. 분산락 심도있는 이야기 + 구현 방법

4. PG사 성공을 트랜잭션에 묶는 건 안 되는지

5. 일일한도 당일 업데이트 시 어떻게 처리할지

6. 보상 트랜잭션 로직이 실패하는 경우

7. DB RDS 사용 시 달라지는 것
   + @Transactional(readOnly) 왜 있는지

8. 분산락 구현 방법

9. TX 2 취약점 해결 방안
   (PENDING 재처리 시 이중 차감 방어 로직 필요)

### 설계 결정 대안 비교 설명 연습

10. 멱등키 테이블 설계 고민
    - cancel_request 등 다른 테이블과 통합 가능한지
    - 분리되어야 하는 이유
    - 설계상 트레이드오프
