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
| `Idempotency-Key` | 필수 | 클라이언트 생성 UUID. 중복 요청 방어 |
| `Content-Type` | 필수 | application/json |

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
| 멱등성 | Idempotency-Key 헤더 기준 |

### Path parameter

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `paymentKey` | String | PG사 발급 결제 키 |

### Request body

```json
{
  "cancelAmount": 300000,
  "cancelReason": "고객 단순 변심",
  "cancelItems": [
    {
      "paymentItemId": 2,
      "cancelAmount": 300000
    }
  ]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `cancelAmount` | Decimal | 필수 | 총 취소 금액. cancelItems 합계와 일치해야 함 |
| `cancelReason` | String | 선택 | 취소 사유. 최대 255자 |
| `cancelItems` | Array | 필수 | 취소 항목 목록. 1개 이상 |
| `cancelItems[].paymentItemId` | Long | 필수 | 취소할 결제 항목 ID |
| `cancelItems[].cancelAmount` | Decimal | 필수 | 항목별 취소 금액 |

### Response 200

```json
{
  "cancelRequestId": "cr_abc123",
  "paymentKey": "pay_xyz",
  "cancelAmount": 300000,
  "currency": "KRW",
  "status": "COMPLETED",
  "cancellerType": "USER",
  "cancelReason": "고객 단순 변심",
  "cancelledItems": [
    {
      "paymentItemId": 2,
      "cancelAmount": 300000,
      "status": "CANCELLED"
    }
  ],
  "completedAt": "2026-04-13T10:00:00.000Z"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `cancelRequestId` | String | 취소 요청 식별자 |
| `paymentKey` | String | PG사 결제 키 |
| `cancelAmount` | Decimal | 취소 금액 |
| `currency` | String | 통화 코드 (ISO 4217) |
| `status` | String | 취소 상태 |
| `cancellerType` | String | 취소 요청자 유형 (USER / MERCHANT / ADMIN) |
| `cancelReason` | String | 취소 사유 |
| `cancelledItems` | Array | 취소된 항목 목록 |
| `cancelledItems[].paymentItemId` | Long | 결제 항목 ID |
| `cancelledItems[].cancelAmount` | Decimal | 항목별 취소 금액 |
| `cancelledItems[].status` | String | 항목 상태 (PARTIAL_CANCELLED / CANCELLED) |
| `completedAt` | DateTime | 취소 완료 시각 (UTC ISO-8601) |

### Response 409 (멱등 중복)

```json
{
  "code": "IDEMPOTENT_DUPLICATION",
  "message": "이미 처리된 요청입니다.",
  "detail": {
    "originalStatus": "COMPLETED",
    "cancelRequestId": "cr_abc123"
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

### Response 422 (취소 금액 초과)

```json
{
  "code": "CANCEL_AMOUNT_EXCEEDED",
  "message": "취소 금액이 잔여 취소 가능액을 초과했습니다.",
  "detail": {
    "paymentItemId": 2,
    "requestedAmount": 500000,
    "availableAmount": 300000
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
  "currency": "KRW",
  "status": "COMPLETED",
  "cancellerType": "USER",
  "cancelReason": "고객 단순 변심",
  "cancelledItems": [
    {
      "paymentItemId": 2,
      "cancelAmount": 300000,
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
      "currency": "KRW",
      "status": "COMPLETED",
      "cancellerType": "USER",
      "cancelReason": "고객 단순 변심",
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
| `PARTIAL_CANCELLED` | 일부 취소됨 |
| `CANCELLED` | 전액 취소됨 |

### canceller_type

| 값 | 설명 |
|----|------|
| `USER` | 고객 본인 취소 |
| `MERCHANT` | 가맹점 취소 |
| `ADMIN` | 운영자 취소 |