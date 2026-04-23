## 6. 상태 전이 규칙

### 6-1. Payment 상태 전이

```mermaid
stateDiagram-v2
  [*] --> COMPLETED : 결제 완료
  COMPLETED --> PARTIAL_CANCELLED : 일부 아이템 취소
  COMPLETED --> CANCELLED : 전체 아이템 취소
  PARTIAL_CANCELLED --> PARTIAL_CANCELLED : 추가 아이템 취소
  PARTIAL_CANCELLED --> CANCELLED : 나머지 아이템 전부 취소
  CANCELLED --> [*]
```

### 6-2. PaymentItem 상태 전이

```mermaid
stateDiagram-v2
  [*] --> ACTIVE
  ACTIVE --> CANCELLED : 아이템 단위 전액 취소
  CANCELLED --> [*]
```

```
PaymentItem 부분취소 불가:
  아이템 단위 전액 취소만 허용
  PARTIAL_CANCELLED 상태 없음
  cancelled_amount 컬럼 없음
  version (낙관적 락) 없음
  → cancel_request (payment_id, request_hash) UK로 동시성 제어
```

### 6-3. 취소 불가 상태

```
Payment 취소 불가: PENDING, CANCELLED, CANCEL_FAILED
PaymentItem 취소 불가: CANCELLED
Order 취소 불가: DELIVERING 이후
```

### 6-4. 검증 순서와 이유

```
1. 요청 형식 오류 (400)
2. 인가 오류 (403)
3. 리소스 없음 (404) — Payment 존재 확인
4. 멱등 중복 (409)
5. 비즈니스 규칙 (422):
     Payment 상태
     PaymentItem 상태
     취소 금액 검증
     취소 기간 검증
     가맹점 한도 검증 (risk-management-service)

순서가 중요한 이유:
  한도 검증(5번)을 리소스 확인(3번) 전에 하면
  존재하지 않는 Payment에 대해 한도가 차감될 수 있음
```

---

## 7. API 설계

### 7-1. 취소 요청 API

```
POST /v1/payments/{paymentKey}/cancel

헤더:
  Authorization: Bearer {token}
  Idempotency-Key: {UUID}

요청:
{
  "cancelAmount": 300000,
  "cancelReason": "고객 단순 변심",
  "cancelItems": [
    { "paymentItemId": 2, "cancelAmount": 300000 }
  ]
}

응답 200:
{
  "cancelRequestId": "cr_abc123",
  "paymentKey": "pay_xyz",
  "cancelAmount": 300000,
  "currency": "KRW",
  "status": "COMPLETED",
  "cancellerType": "USER",
  "cancelledItems": [
    { "paymentItemId": 2, "cancelAmount": 300000, "status": "CANCELLED" }
  ],
  "completedAt": "2026-04-13T10:00:00.000Z"
}
```

**paymentKey를 URL에 사용한 이유:**

```
paymentId(내부 PK): 순차적 숫자
  → 전체 결제 건수 유추 가능
  → 다른 결제 ID 추측 접근 가능 → 보안 취약

paymentKey(PG사 발급 키): 불투명 키
  → 추측 불가
  → 클라이언트가 결제 완료 후 응답으로 받은 값
  → URL에 쓰기 적합
```

### 7-2. 조회 API

```
GET /v1/payments/{paymentKey}/cancel/{cancelRequestId}
GET /v1/payments/{paymentKey}/cancels?page=0&size=20
```

---

## 8. 데이터 설계

### 8-1. 모듈별 테이블 구조

**payment-service**

```mermaid
erDiagram
  PAYMENT ||--o{ PAYMENT_ITEM : contains
  PAYMENT ||--o{ PAYMENT_HISTORY : has
  PAYMENT ||--o{ CANCEL_REQUEST : has
  CANCEL_REQUEST ||--o{ CANCEL_REQUEST_HISTORY : has
  CANCEL_REQUEST ||--o| CANCEL_EVENT_OUTBOX : triggers
  CANCEL_REQUEST ||--o{ COMPENSATION_RETRY : retries

  PAYMENT {
    bigint id PK
    varchar payment_key UK
    bigint merchant_id
    bigint user_id
    varchar pg_type
    decimal total_amount
    varchar currency
    int cancel_period_days
    varchar status
    datetime created_at
  }
  PAYMENT_ITEM {
    bigint id PK
    bigint payment_id FK
    bigint order_item_id
    bigint product_id
    bigint product_auto_id
    varchar item_name
    decimal item_amount
    varchar status
  }
  PAYMENT_HISTORY {
    bigint id PK
    bigint payment_id FK
    varchar from_status
    varchar to_status
    varchar cause
    bigint caused_by_id
    datetime created_at
  }
  CANCEL_REQUEST {
    bigint id PK
    bigint payment_id FK
    varchar request_hash
    decimal cancel_amount
    varchar canceller_type
    bigint cancelled_by
    varchar status
    datetime pg_pending_since
    datetime completed_at
    varchar failed_reason
  }
  CANCEL_REQUEST_HISTORY {
    bigint id PK
    bigint cancel_request_id FK
    varchar status
    varchar reason
    datetime created_at
  }
  CANCEL_EVENT_OUTBOX {
    bigint id PK
    bigint cancel_request_id UK
    json payload
    varchar status
    datetime created_at
    datetime published_at
  }
  COMPENSATION_RETRY {
    bigint id PK
    varchar cancel_request_id UK
    bigint merchant_id
    decimal restore_amount
    int attempt_count
    datetime next_retry_at
    varchar status
  }
```

**order-service**

```mermaid
erDiagram
  ORDER ||--o{ ORDER_ITEM : contains

  ORDER {
    bigint id PK
    varchar order_key UK
    varchar payment_key UK
    bigint user_id
    bigint merchant_id
    decimal total_amount
    varchar currency
    varchar payment_type
    varchar status
    datetime created_at
  }
  ORDER_ITEM {
    bigint id PK
    bigint order_id FK
    bigint merchant_id
    bigint product_id
    bigint product_auto_id
    varchar item_name
    decimal item_price
    int quantity
    varchar status
  }
  PROCESSED_CANCEL_EVENT {
    bigint id PK
    varchar cancel_request_id UK
    datetime processed_at
  }
```

**merchant-limit-service**

```mermaid
erDiagram
  MERCHANT ||--o{ MERCHANT_CANCEL_LIMIT : has
  MERCHANT ||--o{ MERCHANT_CANCEL_LIMIT_HISTORY : has

  MERCHANT {
    bigint id PK
    varchar merchant_key UK
    varchar name
    int cancel_period_days
    varchar status
  }
  MERCHANT_CANCEL_LIMIT {
    bigint id PK
    bigint merchant_id FK
    date kst_date
    decimal daily_limit
    datetime created_at
  }
  MERCHANT_CANCEL_LIMIT_HISTORY {
    bigint id PK
    bigint merchant_id FK
    decimal previous_limit
    decimal new_limit
    varchar change_reason
    bigint changed_by
    datetime created_at
  }
```

**risk-management-service**

```mermaid
erDiagram
  MERCHANT_CANCEL_USAGE ||--o{ CANCEL_USAGE_HISTORY : has
  MERCHANT_CANCEL_USAGE ||--o{ CANCEL_USAGE_COMPENSATION : compensated_by

  MERCHANT_CANCEL_USAGE {
    bigint id PK
    bigint merchant_id
    date kst_date
    decimal daily_limit
    decimal used_amount
    datetime created_at
    datetime updated_at
  }
  CANCEL_USAGE_HISTORY {
    bigint id PK
    varchar cancel_request_id UK
    bigint merchant_id
    decimal cancel_amount
    datetime created_at
  }
  CANCEL_USAGE_COMPENSATION {
    bigint id PK
    varchar cancel_request_id UK
    bigint merchant_id
    decimal restore_amount
    varchar status
    datetime created_at
  }
```

**product-service**

```mermaid
erDiagram
  CATEGORY ||--o{ PRODUCT : classifies
  PRODUCT ||--o{ PRODUCT_VERSION : versions
  PRODUCT ||--o{ PRODUCT_ATTRIBUTE_TYPE : has
  PRODUCT_ATTRIBUTE_TYPE ||--o{ PRODUCT_ATTRIBUTE_VALUE : has
  PRODUCT_VERSION ||--o{ PRODUCT_SKU : has
  PRODUCT_VERSION ||--o{ PRODUCT_IMAGE : has
  PRODUCT_SKU ||--o{ PRODUCT_SKU_ATTRIBUTE : composed_of
  PRODUCT_SKU ||--|| PRODUCT_STOCK : tracked_by
  PRODUCT_ATTRIBUTE_VALUE ||--o{ PRODUCT_SKU_ATTRIBUTE : used_in

  CATEGORY {
    bigint id PK
    bigint parent_id FK
    varchar name
    int sort_order
  }
  PRODUCT {
    bigint id PK
    bigint merchant_id
    bigint category_id FK
    datetime created_at
  }
  PRODUCT_VERSION {
    bigint id PK
    bigint product_id FK
    varchar name
    decimal price
    decimal discount_amount
    datetime discount_start_at
    datetime discount_end_at
    varchar status
  }
  PRODUCT_ATTRIBUTE_TYPE {
    bigint id PK
    bigint product_id FK
    varchar name
    int sort_order
  }
  PRODUCT_ATTRIBUTE_VALUE {
    bigint id PK
    bigint attribute_type_id FK
    varchar value
    int sort_order
  }
  PRODUCT_SKU {
    bigint id PK
    bigint product_version_id FK
    varchar sku_code
    decimal additional_price
    varchar status
  }
  PRODUCT_SKU_ATTRIBUTE {
    bigint id PK
    bigint sku_id FK
    bigint attribute_value_id FK
  }
  PRODUCT_STOCK {
    bigint id PK
    bigint sku_id FK
    int quantity
    datetime updated_at
  }
  PRODUCT_IMAGE {
    bigint id PK
    bigint product_version_id FK
    varchar image_url
    tinyint is_thumbnail
    int sort_order
  }
```

### 8-2. 핵심 테이블 관계

```
payment-service:
  payment (결제 원장)
    └── payment_item (결제 항목, 아이템 단위 취소)
    └── payment_history (상태 변경 이력)
    └── cancel_request (취소 요청, request_hash UK)
        └── cancel_request_history (상태 변경 이력)
    └── cancel_event_outbox (Kafka 발행 보장)
    └── compensation_retry (보상 재시도 — payment-service 관리)

risk-management-service:
  merchant_cancel_usage (가맹점 일일 소진 내역)
    └── cancel_usage_history (차감 이력, 이중 차감 방어)
    └── cancel_usage_compensation (보상 멱등성)
```

### 8-2. 금액 타입

```
FLOAT / DOUBLE 금지 이유:
  0.1 + 0.2 = 0.30000000004
  부동소수점 오차 → 금융에서 절대 금지

선택: DECIMAL(19,2) + currency VARCHAR(3)
  고정소수점으로 정확한 소수점 처리
  Java에서 BigDecimal로 매핑
  currency: ISO 4217 코드 (KRW, USD, EUR)
```

### 8-3. 상품 버저닝

```
product (원본, 불변)
product_version (버전별 상세)
product_sku (버전별 속성 조합 — 색상, 사이즈)

실제 가격 = product_version.price
           - product_version.discount_amount
           + product_sku.additional_price

payment_item 스냅샷:
  product_auto_id: 결제 시점 버전 고정
  item_name, item_price: 결제 시점 값
  → 나중에 상품 정보 변경돼도 결제 내역 불변
```

---

