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