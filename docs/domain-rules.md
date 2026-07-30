# Domain rules

비즈니스 규칙의 단일 원본이다.
코드보다 이 문서가 먼저 작성되고, 코드는 이 문서를 따른다.
규칙 변경 시 이 문서를 먼저 수정한 후 구현을 변경한다.

---

## 1. 취소 가능 조건

### 1-1. Payment 상태 조건

| 허용 상태 | 설명 |
|----------|------|
| COMPLETED | 결제 완료, 아직 취소 없음 |
| PARTIAL_CANCELLED | 일부 취소 완료, 추가 취소 가능 |

| 거부 상태 | 거부 사유 |
|----------|---------|
| PENDING | 결제 진행 중 |
| CANCELLED | 전액 취소 완료 |
| CANCEL_FAILED | 취소 실패 상태 |

### 1-2. PaymentItem 상태 조건

| 허용 상태 | 설명 |
|----------|------|
| ACTIVE | 취소되지 않음 |

CANCELLED 상태인 PaymentItem을 포함한 요청은 전액 거부한다.

> **아이템 단위 전액 취소만 가능**: 항목별 부분취소는 지원하지 않는다.
> PARTIAL_CANCELLED 상태는 존재하지 않는다.

### 1-3. 취소 가능 기간
Payment.created_at 기준으로 payment.cancel_period_days 이내에만 취소 가능하다.
cancel_period_days는 결제 시점 가맹점 정책을 스냅샷한 값이다.
기간 초과 시 전액 거부한다.

### 1-4. Order 상태 조건 (취소 가능 범위)

| 취소 가능 상태 |
|--------------|
| PENDING |
| PAYMENT_VERIFYING |
| PAID |
| DELIVERY_WAITING |

DELIVERING 이후 상태에서는 취소 불가. 반품으로만 처리 가능.
CONFIRMED 이후는 취소/반품 모두 불가.

---

## 2. 취소 금액 검증

### 2-1. 검증 순서

```
1. cancelItems 비어있으면 400 거부

2. cancelItems에 동일한 paymentItemId 중복이면 400 거부

3. 대상 PaymentItem 상태 검증
   CANCELLED 상태인 PaymentItem 포함 시 422 거부

4. 가맹점 취소한도 검증
   잔여 한도 >= 요청 취소 총액
   → 초과 시 422
```

> **부분취소 미지원**: 항목별 cancelAmount를 검증하지 않는다.
> 취소 금액은 대상 PaymentItem의 item_amount 전액이다.

### 2-2. 잔여 취소 가능액 계산

```
잔여 취소 가능액
  = Payment.total_amount
  - sum(COMPLETED 상태인 CANCEL_REQUEST.cancel_amount)

PENDING, PROCESSING, FAILED 상태 취소 건은 계산에서 제외한다.
FAILED 건을 제외하는 이유:
  취소가 실패한 금액은 실제로 취소되지 않았으므로
  잔여 가능액에서 차감하면 안 된다.
```

### 2-3. 취소 항목 조건
- cancelItems가 비어있으면 400 거부한다.
- cancelItems에 동일한 paymentItemId가 중복되면 400 거부한다.

---

## 3. 가맹점 취소한도 정책

### 3-1. 한도 기준
- 기준 단위: KST 일(日) 단위로 리셋된다.
- 매일 새 레코드를 INSERT한다 (이전 날 레코드는 감사 목적으로 보존).
- 서버 코드에서 KST 오늘 날짜를 계산한다.

```java
LocalDate kstToday = LocalDate.now(ZoneId.of("Asia/Seoul"));
```

### 3-2. 한도 계산 방식

```
당일 잔여 한도
  = merchant_cancel_usage.daily_limit
  - merchant_cancel_usage.used_amount

used_amount는 CANCEL_REQUEST가 COMPLETED될 때만 증가한다.
FAILED된 건은 used_amount에 반영하지 않는다.
```

한도 차감은 선차감 방식이다.
- 가맹점한도 검증 통과 → used_amount 선차감 (PROCESSING 진입)
- 취소 COMPLETED → 선차감 유지
- 취소 FAILED → 보상 트랜잭션으로 used_amount 원복

### 3-3. 한도 초과 처리
잔여 한도보다 요청 금액이 크면 전액 거부한다.
부분 허용하지 않는다.

### 3-4. 한도 필수 규칙
merchant_cancel_usage 레코드가 없는 가맹점의 취소 요청은 거부한다.
null 한도(무제한)는 허용하지 않는다.

### 3-5. 일일 한도 스냅샷
당일 첫 취소 요청 시 merchant-limit-service에서 daily_limit을 조회해
merchant_cancel_usage에 저장한다.
이후 요청은 스냅샷을 사용한다.
한도 변경은 다음날 첫 요청 시 반영된다.

### 3-6. 가맹점 상태별 취소 허용 정책

| 상태 | 신규 결제 | 취소 |
|------|---------|------|
| ACTIVE | 허용 | 허용 |
| INACTIVE | 불가 | 허용 |
| SUSPENDED | 불가 | 불가 |

### 3-7. 한도 변경 이력
daily_limit 변경 시 merchant_cancel_limit_history에 기록한다.
이력은 삭제하지 않는다.

---

## 4. 상태 전이 규칙

### 4-1. Payment 상태 전이

```
COMPLETED
  ├─ 부분취소 성공 → PARTIAL_CANCELLED
  └─ 전액취소 성공 → CANCELLED

PARTIAL_CANCELLED
  ├─ 추가 부분취소 → PARTIAL_CANCELLED (유지)
  └─ 잔액 전체 취소 → CANCELLED
```

전이 조건:
취소 후 CANCELLED 상태인 PaymentItem.item_amount 합계
= Payment.total_amount 이면 → CANCELLED
미만이면 → PARTIAL_CANCELLED

### 4-2. PaymentItem 상태 전이

```
ACTIVE
  └─ 취소 → CANCELLED
```

> 아이템 단위 전액 취소만 가능하므로 PARTIAL_CANCELLED 상태는 존재하지 않는다.

### 4-3. CancelRequest 상태 전이

```
PENDING
  └─ used_amount 선차감 완료 → PROCESSING

PROCESSING
  ├─ PaymentItem 변경 + Outbox INSERT 완료 → COMPLETED
  └─ 처리 실패 → FAILED

COMPLETED: 최종 상태 (변경 불가)
FAILED: 최종 상태 (변경 불가, 보상 트랜잭션 대상)
```

PROCESSING 상태의 특수 규칙:
서버 재시작 후 5분 초과 PROCESSING 건은
복구 스케줄러가 COMPLETED 방향으로 재처리한다.
이때 used_amount 선차감은 이미 완료된 것으로 간주하고 skip한다.

### 4-4. Order 상태 전이

```
PENDING → PAYMENT_VERIFYING → PAID
                ↓
         DELIVERY_WAITING → DELIVERING → DELIVERED → CONFIRMED
                                                    → CONFIRM_PENDING
         
취소 발생 시:
PAID / DELIVERY_WAITING → PARTIAL_CANCELLED or CANCELLED
```

payment_type별 상태 흐름:
- CREDIT_CARD: PENDING → PAID
- VIRTUAL_ACCOUNT / TRANSFER: PENDING → PAYMENT_VERIFYING → PAID

---

## 5. 멱등성 규칙

### 5-1. API 레이어
서버가 `paymentKey + cancelItemIds 오름차순 정렬`을 SHA-256 해시하여 `request_hash`(content-hash)를 생성한다.
클라이언트가 `Idempotency-Key` 헤더를 보내면 그 값을 우선 사용하고, 없으면 `request_hash`로 폴백한다.
`cancel_request.dedup_key`는 `Idempotency-Key`가 있으면 `ik:{key}`, 없으면 `ch:{request_hash}` 접두로 생성되며,
`cancel_request(payment_id, dedup_key)` UNIQUE KEY로 중복 요청을 방어한다.

기존 cancel_request 상태별 처리:
- `COMPLETED` → 200 기존 응답 반환
- `PENDING` / `PROCESSING` → 200 처리 중 응답 반환
- `FAILED` → PENDING으로 UPDATE 후 재처리 진행

같은 `Idempotency-Key`로 이전과 다른 요청 내용(`request_hash` 불일치)이 재사용되면
`IDEMPOTENCY_KEY_CONFLICT` 409로 거부한다 (기존 cancel_request는 변경하지 않음).

### 5-2. Kafka Consumer 레이어
cancelRequestId 기준으로 processed_cancel_event 테이블에서 중복 방어한다.

### 5-3. 보상 트랜잭션 레이어
cancelRequestId 기준으로 cancel_usage_compensation 테이블에서 중복 방어한다.

---

## 6. EXHAUSTED 운영 정책

보상 트랜잭션이 5회 재시도 후 모두 실패한 상태를 EXHAUSTED라 한다.

처리 절차:
1. 운영팀에 알림 발송
2. 운영팀이 merchant_cancel_usage.used_amount 수동 보정
3. compensation_retry.status = EXHAUSTED 건에 처리 완료 기록

자동 해소하지 않는다.

---

## 7. 금지 규칙 요약

```
- 취소 완료된 금액을 재취소 불가
- 취소 요청액이 PaymentItem 잔액 초과 불가
- 취소 요청액이 가맹점 잔여 한도 초과 시 부분 허용 불가 (전액 거부)
- 한도 없는 가맹점의 취소 요청 불가
- null 한도 불가
- FAILED 취소 건을 잔여 취소 가능액 계산에 포함 불가
- PROCESSING 재처리 시 used_amount 재차감 불가
- DELIVERING 이후 상태에서 취소 불가
- CONFIRMED 이후 취소/반품 불가
- domain 로직에서 외부 시스템 직접 호출 불가
```

---

## 8. 취소 인가 (AUTHZ-01)

취소 요청은 취소 코어 진입 이전에 presentation pre-check 에서 인가한다.
판정은 게이트웨이(Phase 2)가 검증 후 재주입한 신뢰 헤더 role 만으로 수행하며, payment 는 이를 무검증 신뢰한다.

### 8-1. 판정 매트릭스 (D-P3-2)

| role (X-User-Role) | 조건 | 결과 |
|--------------------|------|------|
| `ADMIN` | 무조건 | 전체 허용 (대상 payment 로드 생략) |
| `MERCHANT` | `X-Merchant-Id` == `payment.merchant_id` | 허용 |
| `MERCHANT` | 불일치 / `X-Merchant-Id` 누락·비정상 | 403 |
| `USER` | 소유 여부 무관 | 403 (self-cancel 미허용) |
| 누락 / 기타 | — | 403 |

- 실패는 신규 에러코드 없이 기존 `FORBIDDEN_PAYMENT`(403)를 재사용한다 (D-P3-4).
- `X-Merchant-Id` 는 문자열 헤더 → `Long` 파싱하여 `payment.merchant_id`(Long)와 비교한다.
  파싱 실패(비숫자)·null 은 500 이 아니라 403 으로 흡수한다 (T-03-04).
- ADMIN 만 payment 로드를 생략하고, MERCHANT 경로에서만 `findByPaymentKey` 로 대상 payment 를
  read-only 1회 로드한다 (D-P3-5).

### 8-2. 신뢰 헤더 정책 (D-P3-3, D-P3-7)

- payment 는 role 을 JWT 재검증 없이 신뢰한다 — spring-security 의존을 도입하지 않는다.
- `X-User-Id` 는 인가에 사용하지 않고 감사 로깅 전용으로만 보관한다.

### 8-3. 신뢰 경계 — NetworkPolicy 배포 게이트 (D-P3-6, 필수)

payment(8080)로 게이트웨이를 우회해 직접 도달하면 `X-User-Role` 헤더를 위조해 전량 취소가 가능하다.
이 스푸핑 방어는 코드가 아니라 **k3s NetworkPolicy 로 배포 시점에 이관**한다 — payment ingress 를
게이트웨이 파드로만 제한해야 한다. NetworkPolicy 부재 시 인가 자체가 무력화되므로 **배포 전 필수 게이트**다.
코드는 게이트웨이 경유(신뢰 헤더 진위)를 가정한다.