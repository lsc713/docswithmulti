# API spec

취소 관련 API 명세.
모든 요청은 UTC 기준 시각을 사용한다.
에러 응답 포맷은 error-catalog.md를 따른다.

---

## 공통

### 요청 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| `Authorization` | 필수 | Bearer {token} |
| `Content-Type` | 필수 | application/json |
| `Idempotency-Key` | 선택 | 클라이언트가 지정하는 멱등키. 취소 요청(1번 API)에만 적용 |

> **멱등성 처리**: `Idempotency-Key` 헤더가 있으면 그 값을 사용하고, 없으면 서버가
> `paymentKey + cancelItemIds 오름차순 정렬`을 SHA-256 해시한 `request_hash`로 폴백한다.
> `cancel_request.dedup_key`(= `Idempotency-Key`가 있으면 `ik:{key}`, 없으면 `ch:{request_hash}`)를
> `(payment_id, dedup_key)` UK로 방어한다.
> 같은 `Idempotency-Key`로 이전과 다른 요청 내용(`request_hash` 불일치)이 재사용되면
> `IDEMPOTENCY_KEY_CONFLICT` 409로 거부한다.

### 공통 에러 응답

```json
{
  "code": "ERROR_CODE",
  "message": "사람이 읽을 수 있는 설명",
  "detail": {}
}
```

---

## 1. 취소 요청

### 기본 정보

```
POST /v1/payments/{paymentKey}/cancel
```

| 항목 | 값 |
|------|-----|
| 인가 | 가맹점(MERCHANT) / 관리자(ADMIN) / 사용자(USER) |
| 멱등성 | `Idempotency-Key` 헤더(선택) 기준, 없으면 `request_hash` 폴백 |

### Path parameter

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `paymentKey` | String | PG사 발급 결제 키 |

### Request body

```json
{
  "cancelReason": "고객 단순 변심",
  "cancelItems": [
    {
      "paymentItemId": 2
    }
  ]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `cancelReason` | String | 선택 | 취소 사유. 최대 255자 |
| `cancelItems` | Array | 필수 | 취소 항목 목록. 1개 이상 |
| `cancelItems[].paymentItemId` | Long | 필수 | 취소할 결제 항목 ID |

> **아이템 단위 전액 취소**: 항목별 `cancelAmount`를 전달하지 않는다.
> 취소 금액은 `payment_item.item_amount` 전액이며, 부분취소는 지원하지 않는다.
> 총 취소 금액(`cancelAmount`)은 서버가 cancelItems의 `item_amount` 합계로 계산한다.

### Response 200

```json
{
  "cancelRequestId": "cr_abc123",
  "paymentKey": "pay_xyz",
  "cancelAmount": 300000,
  "status": "COMPLETED",
  "cancelledItems": [
    {
      "paymentItemId": 2,
      "itemAmount": 300000,
      "status": "CANCELLED"
    }
  ],
  "completedAt": "2026-04-18T10:00:00.000Z"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `cancelRequestId` | String | 취소 요청 식별자 |
| `paymentKey` | String | PG사 결제 키 |
| `cancelAmount` | Decimal | 총 취소 금액 |
| `status` | String | 취소 상태 |
| `cancelledItems` | Array | 취소된 항목 목록 |
| `cancelledItems[].paymentItemId` | Long | 결제 항목 ID |
| `cancelledItems[].itemAmount` | Decimal | 항목 결제 금액 (= 취소 금액) |
| `cancelledItems[].status` | String | 항목 상태 (CANCELLED) |
| `completedAt` | DateTime | 취소 완료 시각 (UTC ISO-8601) |

### 멱등성 처리 응답

`dedup_key`(`Idempotency-Key`가 있으면 `ik:{key}`, 없으면 `ch:{request_hash}`) 기준으로
기존 cancel_request가 있을 때 상태별 응답:

| 상태 | 응답 |
|------|------|
| `COMPLETED` | 200 — 기존 취소 응답 그대로 반환 |
| `PENDING` / `PROCESSING` | 200 — 처리 중 응답 반환 (`status: PENDING` or `PROCESSING`) |
| `FAILED` | 재처리 진행 (FAILED → PENDING으로 UPDATE 후 플로우 재실행) |

같은 `Idempotency-Key`로 이전과 다른 요청 내용(`request_hash` 불일치)이 재사용되면
새 cancel_request를 만들지 않고 409로 거부한다.

### Response 403 (인가 실패)

무권한 취소 요청은 취소 코어 진입 이전에 차단된다 (AUTHZ-01, domain-rules.md §8).
신뢰 헤더 `X-User-Role` 이 `ADMIN` 이 아니고, `MERCHANT` 이면서 `X-Merchant-Id` 가
`payment.merchant_id` 와 일치하지도 않으면(불일치·누락·비정상, USER, role 누락 포함) 403.
에러코드는 기존 `FORBIDDEN_PAYMENT` 를 재사용한다(신규 코드 없음).

```json
{
  "code": "FORBIDDEN_PAYMENT",
  "message": "해당 결제에 대한 취소 권한이 없습니다.",
  "detail": {}
}
```

### Response 409 (Idempotency-Key 재사용 충돌)

```json
{
  "code": "IDEMPOTENCY_KEY_CONFLICT",
  "message": "이미 다른 요청에 사용된 Idempotency-Key입니다.",
  "detail": {
    "idempotencyKey": "..."
  }
}
```

### Response 422 (한도 초과)

```json
{
  "code": "MERCHANT_CANCEL_LIMIT_EXCEEDED",
  "message": "가맹점 일일 취소한도를 초과했습니다.",
  "detail": {
    "requestedAmount": 500000,
    "remainingLimit": 300000,
    "dailyLimit": 1000000
  }
}
```

### Response 422 (취소 기간 초과)

```json
{
  "code": "CANCEL_PERIOD_EXCEEDED",
  "message": "취소 가능 기간이 지났습니다.",
  "detail": {
    "paymentCreatedAt": "2025-12-01T00:00:00Z",
    "cancelPeriodDays": 90
  }
}
```

### Response 422 (이미 취소된 항목)

```json
{
  "code": "INVALID_PAYMENT_ITEM_STATUS",
  "message": "이미 취소된 항목입니다.",
  "detail": {
    "paymentItemId": 2,
    "currentStatus": "CANCELLED"
  }
}
```

---

## 2. 취소 단건 조회

### 기본 정보

```
GET /v1/payments/{paymentKey}/cancel/{cancelRequestId}
```

| 항목 | 값 |
|------|-----|
| 인가 | 가맹점(MERCHANT) / 관리자(ADMIN) / 사용자(USER) |

### Path parameter

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `paymentKey` | String | PG사 발급 결제 키 |
| `cancelRequestId` | String | 취소 요청 식별자 |

### Response 200

```json
{
  "cancelRequestId": "cr_abc123",
  "paymentKey": "pay_xyz",
  "cancelAmount": 300000,
  "status": "COMPLETED",
  "cancelledItems": [
    {
      "paymentItemId": 2,
      "itemAmount": 300000,
      "status": "CANCELLED"
    }
  ],
  "createdAt": "2026-04-13T10:00:00.000Z",
  "completedAt": "2026-04-13T10:00:01.000Z"
}
```

### Response 404

```json
{
  "code": "PAYMENT_NOT_FOUND",
  "message": "결제 정보를 찾을 수 없습니다.",
  "detail": {
    "paymentKey": "pay_xyz"
  }
}
```

---

## 3. 취소 목록 조회

### 기본 정보

```
GET /v1/payments/{paymentKey}/cancels
```

| 항목 | 값 |
|------|-----|
| 인가 | 가맹점(MERCHANT) / 관리자(ADMIN) / 사용자(USER) |

### Path parameter

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `paymentKey` | String | PG사 발급 결제 키 |

### Query parameter

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| `page` | Integer | 선택 | 0 | 페이지 번호 (0부터 시작) |
| `size` | Integer | 선택 | 20 | 페이지 크기. 최대 100 |

### Response 200

```json
{
  "content": [
    {
      "cancelRequestId": "cr_abc123",
      "cancelAmount": 300000,
      "status": "COMPLETED",
      "createdAt": "2026-04-13T10:00:00.000Z",
      "completedAt": "2026-04-13T10:00:01.000Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

## 상태값 정의

### cancel_request.status

| 값 | 설명 |
|----|------|
| `PENDING` | 취소 요청 수신, 한도 차감 전 |
| `PROCESSING` | 한도 차감 완료, 취소 처리 중 |
| `COMPLETED` | 취소 완료 |
| `FAILED` | 취소 실패 |

### payment_item.status (cancelledItems)

| 값 | 설명 |
|----|------|
| `ACTIVE` | 취소 없음 |
| `CANCELLED` | 취소됨 (아이템 단위 전액 취소) |

> 부분취소는 지원하지 않는다. 아이템은 ACTIVE → CANCELLED만 전이된다.

---

## 4. DEAD 취소 outbox 상태 검사 (내부 운영자 전용)

이 API는 원본 outbox와 주문·재고의 현재 상태를 읽기만 하며 Kafka 발행이나 DB 쓰기를
수행하지 않는다. public API Gateway에는 노출하지 않는다.

```http
GET /internal/cancel-outbox/{outboxId}
X-User-Role: ADMIN
X-User-Id: operator-1
```

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Role` | 필수 | 내부 인증 경계가 주입한 `ADMIN` 역할 |
| `X-User-Id` | 필수 | 비어 있지 않은 운영자 식별자 |

### Response 200

```json
{
  "outboxId": 6,
  "cancelRequestId": 27,
  "decision": "REDRIVE_REQUIRED",
  "reasonCode": null,
  "order": {
    "status": "APPLIED",
    "evidence": [
      {"targetId": 101, "currentStatus": "CANCELLED", "actualQuantity": null, "expectedQuantity": null}
    ]
  },
  "stock": {
    "status": "NOT_APPLIED",
    "evidence": [
      {"targetId": 8, "currentStatus": "RESERVED", "actualQuantity": 1, "expectedQuantity": 2}
    ]
  }
}
```

`decision`은 다음 네 값 중 하나다.

| 값 | 의미 |
|----|------|
| `REDRIVE_REQUIRED` | 주문 또는 재고에 아직 적용되지 않아 재발행 후보임 |
| `ALREADY_APPLIED` | 주문과 재고에 모두 적용돼 재발행이 필요 없음 |
| `NOT_ELIGIBLE` | 원본 상태나 payload가 안전 조건을 충족하지 않음 |
| `UNKNOWN` | downstream 장애로 안전하게 판단할 수 없음 |

각 레그의 `status`는 `APPLIED`, `NOT_APPLIED`, `INCONSISTENT`, `UNKNOWN` 중 하나다.
의존 서비스 timeout, 5xx 또는 CircuitBreaker open은 레그와 전체 판정을 `UNKNOWN`으로
fail-closed 처리하며 `ALREADY_APPLIED`로 추정하지 않는다. 안전 조건에서 단락된 경우
`order`와 `stock`은 `null`일 수 있다. 응답에는 원본 payload와 payment key를 포함하지 않는다.

### Error responses

| HTTP | code | 조건 |
|------|------|------|
| 401 | `INTERNAL_AUTHENTICATION_REQUIRED` | 내부 역할 헤더가 없거나 비어 있음 |
| 403 | `CANCEL_OUTBOX_REDRIVE_FORBIDDEN` | 운영자 식별자가 없거나 역할이 `ADMIN`이 아님 |
| 404 | `CANCEL_OUTBOX_NOT_FOUND` | 해당 outbox가 존재하지 않음 |
