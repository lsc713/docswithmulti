# 이커머스 결제 취소 시스템 — 시스템 디자인

> 기간: 2026.04.01 ~ 2026.04.15
> 역할: 시스템 설계 전담
> 규모: 5개 독립 서비스, Kafka 클러스터(3 broker), MySQL 5개 독립 DB

---

## 관련 문서

| 문서 | 내용 |
|------|------|
| [detail/cancel-flow.md](detail/cancel-flow.md) | 취소 플로우 코드 상세, TX 경계, PG사, 보상트랜잭션 |
| [detail/concurrency.md](detail/concurrency.md) | 동시성 3가지 케이스, 락 전략 심화, 분산락 |
| [detail/daily-limit.md](detail/daily-limit.md) | 일일한도 전략, 당일 즉시 반영 설계 |
| [detail/data-design.md](detail/data-design.md) | ERD, 상태전이, API 설계, DDL |
| [design-decisions.md](design-decisions.md) | 설계 결정 대안 분석, 트레이드오프 |
| [interview-qa.md](interview-qa.md) | 면접 질문 + 답변 |

---

## 1. 프로젝트 개요

### 1-1. 한 줄 요약

```
분산 환경에서 멱등성·동시성·부분취소를 보장하는
이커머스 결제 취소 시스템 설계
```

### 1-2. 핵심 문제 (13가지)

#### 중복/멱등 관련
- **문제 1** — 네트워크 재시도로 동일 요청 중복 도달 → 환불 2회
- **문제 2** — Kafka 메시지 중복 수신 → OrderItem 중복 변경
- **문제 3** — 보상 트랜잭션 중복 실행 → used_amount 2번 원복

#### 동시성 관련
- **문제 4** — 가맹점 한도 동시 차감 → 한도 초과 취소
- **문제 5** — 동일 PaymentItem 동시 수정 → cancelled_amount 중복 차감

#### 분산 트랜잭션 관련
- **문제 6** — HTTP 경계를 넘은 원자성 보장 불가
- **문제 7** — DB 커밋과 Kafka 발행 사이 서버 다운

#### 장애/복구 관련
- **문제 8** — 서버 재시작 시 처리 중이던 요청 복구
- **문제 9** — 외부 서비스 장애 전파
- **문제 10** — 스케줄러 중복 실행

#### 데이터 정합성 관련
- **문제 11** — 모듈 간 DB 직접 접근 금지로 조인 불가
- **문제 12** — 결제 시점 상품 정보 변경
- **문제 13** — 가맹점 취소한도 기준일 KST vs UTC

---

## 2. 시스템 아키텍처

```
클라이언트
    │
    ▼
payment-service (8080)
    │
    ├── HTTP 동기 ──▶ risk-management-service (8083)
    │                    └── HTTP 동기 ──▶ merchant-limit-service (8082)
    │
    └── Kafka 비동기 ──▶ order-service (8081)

product-service (8084)   독립
```

### 레이어 아키텍처

```
presentation  → Controller, DTO
application   → UseCase, 인터페이스 선언, TX 경계
domain        → Entity, 값객체, 도메인 서비스 (Spring 의존 없음)
infrastructure → JPA 구현체, Kafka, HTTP 클라이언트
```

---

## 3. 취소 플로우 요약

```
Step 1. Payment/PaymentItem 조회 (ORDER BY id ASC)

Step 2. request_hash 생성 + 멱등성 체크
         hash = SHA-256(paymentKey + paymentItemIds 정렬)
         cancel_request 조회:
           COMPLETED → 기존 응답 반환
           PENDING/PROCESSING → 처리 중 응답 반환
           FAILED → PENDING으로 UPDATE (재처리)
                    cancel_request_history INSERT (이력 기록)
           없음 → 신규 처리

Step 3. Payment/PaymentItem 상태 검증

Step 4. TX 1 — CancelRequest PENDING INSERT
         (payment_id, request_hash) UK → 따닥 요청 차단
         cancel_request_history INSERT (이력 기록)

Step 5. risk-management-service HTTP 호출
         → Redis 우선 조회 → Miss 시 merchant-limit HTTP 폴백
         → merchant_cancel_usage FOR UPDATE
         → cancelRequestId 중복 체크 (이중 차감 방어)
         → used_amount 선차감 커밋

Step 6. TX 2 — CancelRequest PROCESSING
         cancel_request_history INSERT (이력 기록)

Step 7. PG사 취소 API 호출

Step 8. TX 3 — PaymentItem(CANCELLED) + Payment + CancelRequest(COMPLETED) + Outbox
         cancel_request_history INSERT (이력 기록)

Step 9. Outbox 스케줄러 → Kafka 발행 → order-service consume
```

**실패 시 복구:**

| CancelRequest 상태 | 의미 | 처리 |
|-------------------|------|------|
| PENDING | risk 호출 전 실패 | pending-recovery 스케줄러 → risk check → 차감됐으면 보상 → FAILED |
| PROCESSING | risk 완료, PG사 결과 불명확 | processing-recovery 스케줄러 → PG사 조회 후 TX 3 재처리 또는 FAILED |
| FAILED | 실패 확정 | 재시도 시 PENDING으로 UPDATE + 이력 기록 |

---

## 4. Kafka 페이로드

**payment.cancelled:**
```json
{
  "cancelRequestId": "cr_abc123",
  "paymentKey": "pay_xyz",
  "merchantId": 1,
  "cancelledItems": [
    { "paymentItemId": 1, "orderItemId": 10, "itemAmount": 300000 }
  ],
  "cancelledAt": "2026-04-21T10:00:00.000Z"
}
```

**merchant.limit.updated:**
```json
{
  "merchantId": 1,
  "newLimit": 3000000,
  "kstDate": "2026-04-21"
}
```

---

## 5. 아키텍처 패턴 정리

| 패턴 | 적용 위치 | 해결한 문제 |
|------|---------|----------|
| request_hash 멱등키 | cancel_request UK | 네트워크 재시도 중복 |
| Pessimistic Lock | 가맹점 한도 차감 | 동시 한도 초과 |
| SAGA (Choreography) | 전체 취소 플로우 | HTTP 경계 분산 트랜잭션 |
| Outbox Pattern | Kafka 이벤트 발행 | DB-Kafka 원자성 |
| Compensation Transaction | used_amount 원복 | 부분 실패 복구 |
| State Machine | CancelRequest | 서버 재시작 내구성 |
| ShedLock → Redis 분산락 | 스케줄러 | 분산 환경 중복 실행 방지 |
| Snapshot | 결제 시점 데이터 | 모듈 간 데이터 독립 |
| DLQ + Retry Topic | Kafka Consumer | 처리 실패 격리 |

---

## 5. 경력기술서 한 줄 표현

> 분산 환경에서 멱등성·동시성·분산 트랜잭션 문제를 해결하는 이커머스 결제 취소 시스템을 설계했습니다. Idempotency Key, Pessimistic/Optimistic Lock, SAGA 패턴, Outbox Pattern을 각 문제에 적합하게 적용하고, CancelRequest 상태 머신으로 서버 재시작 내구성을 보장했습니다.
