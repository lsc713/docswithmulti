# Error catalog

모든 에러 응답은 아래 포맷을 따른다.

## 응답 포맷

```json
{
  "code": "ERROR_CODE",
  "message": "사람이 읽을 수 있는 설명",
  "detail": {}
}
```

`detail`은 에러 유형에 따라 다른 필드를 포함한다.
정보가 없는 에러는 `detail`을 생략한다.

---

## HTTP 상태코드 기준

| 상태코드 | 사용 기준 |
|---------|---------|
| 400 | 요청 형식/구조 오류 |
| 401 | 인증 실패 (토큰 누락/무효/만료) — api-gateway |
| 403 | 인가 실패 (권한 없음) |
| 404 | 리소스 없음 |
| 409 | 멱등 중복 요청 |
| 422 | 비즈니스 규칙 위반 |
| 500 | 서버 내부 오류 |
| 503 | 외부 모듈 장애 |

---

## 에러 코드 목록

### 인증 오류 (401) — api-gateway

게이트웨이 JWT 게이트(`JwtTrustHeaderFilter`)가 downstream 도달 **전에** 단락하는 코드.
envelope는 `{code, message}` (user-service `GlobalExceptionHandler`와 동일 형태 — D-P2-7).
취소 코어 서비스의 `ErrorCode` enum과는 별개(게이트웨이는 무상태 독립 모듈).

| code | message | 발생 지점 · 의미 |
|------|---------|-----------------|
| `TOKEN_MISSING` | 인증 실패 | Authorization 헤더 누락 또는 `Bearer ` 형식 아님 |
| `TOKEN_INVALID` | 인증 실패 | 서명 불일치 / JWT 형식 오류 / alg 혼동(none·비대칭 위장) |
| `TOKEN_EXPIRED` | 인증 실패 | 만료(`exp` 경과) 토큰 |

### 취소 outbox 복구 검사 오류 — payment-service 내부 API

| HTTP | code | message | 발생 조건 |
|------|------|---------|----------|
| 401 | `INTERNAL_AUTHENTICATION_REQUIRED` | 내부 인증 정보가 필요합니다. | `X-User-Role` 누락 또는 공백 |
| 403 | `CANCEL_OUTBOX_REDRIVE_FORBIDDEN` | 취소 아웃박스 복구 권한이 없습니다. | 비-ADMIN 역할 또는 운영자 식별자 누락·공백 |
| 404 | `CANCEL_OUTBOX_NOT_FOUND` | 취소 아웃박스를 찾을 수 없습니다. | 요청한 outbox ID가 존재하지 않음 |

내부 검사 응답과 오류 메시지는 원본 payload, payment key, 내부 예외 문자열을 노출하지 않는다.

### 주문 검증 오류 (order-service) — `POST /v1/orders/items:verify` (내부 전용, OVER-01)

order-service `ErrorCode` enum(모듈 별도 원본, payment의 `ErrorCode`와 무관)에서 관리. envelope는 `{code, message}`.

| code | 상태코드 | message | 발생 지점 · 의미 |
|------|---------|---------|-----------------|
| `ORDER_ITEM_NOT_FOUND` | 404 | 주문 항목을 찾을 수 없습니다. | 요청된 orderItemId 중 존재하지 않는 항목이 있음 |
| `ORDER_ITEMS_MULTIPLE_ORDERS` | 409 | 요청된 항목이 여러 주문에 걸쳐 있습니다. | 요청된 orderItemId들이 2개 이상의 order에 분산 |
| `ORDER_OWNERSHIP_MISMATCH` | 403 | 해당 주문에 대한 권한이 없습니다. | order.user_id != 요청자(X-User-Id) |

### 결제 생성 — order 검증 거부/장애 (payment-service) — `POST /v1/payments` (PLINK-01/03)

payment `ErrorCode` enum(payment 모듈 원본)에서 관리. order-service의 404/409/403 응답을 그대로
동일 코드·상태로 재매핑해 결제를 거부한다(`OrderVerifyRejectedException`). order-service
장애/타임아웃/비2xx/CircuitBreaker OPEN은 `ORDER_VERIFY_UNAVAILABLE`(`OrderVerifyUnavailableException`,
fail-closed)로 결제를 거부한다.

| code | 상태코드 | message | 발생 지점 · 의미 |
|------|---------|---------|-----------------|
| `ORDER_ITEM_NOT_FOUND` | 404 | 주문 항목을 찾을 수 없습니다. | order-service verify 404 재매핑 |
| `ORDER_ITEMS_MULTIPLE_ORDERS` | 409 | 요청된 항목이 여러 주문에 걸쳐 있습니다. | order-service verify 409 재매핑 |
| `ORDER_OWNERSHIP_MISMATCH` | 403 | 해당 주문에 대한 권한이 없습니다. | order-service verify 403 재매핑 |
| `ORDER_VERIFY_UNAVAILABLE` | 503 | 주문 검증 서비스가 일시적으로 이용 불가합니다. | order-service 장애/타임아웃/비2xx/CB OPEN (fail-closed) |

### 지급(payout) 오류 (settlement-service) — 계좌 설정·조회/승인/콜백 (ACCT-01/ACCT-02/PAY-01/PAY-03/CONFIRM-01)

settlement-service `ErrorCode` enum(모듈 별도 원본, payment/order 의 `ErrorCode`와 무관)에서 관리.
envelope는 `{code, message}`(`GlobalExceptionHandler` → `BusinessException`). 정산 헤더 없음은
기존 `SETTLEMENT_NOT_FOUND`(404) 재사용.

| code | 상태코드 | message | 발생 지점 · 의미 |
|------|---------|---------|-----------------|
| `INVALID_PAYOUT_ACCOUNT` | 400 | 지급 계좌 정보가 올바르지 않습니다. | 계좌 upsert 시 bankCode/accountNumber/holderName 공백 |
| `INVALID_RESERVE_CONFIG` | 400 | 유보 정책 값이 올바르지 않습니다. | 유보 정책 upsert 시 rate<0 / rate≥1 / rate scale>4 / cap<0 / holdDays<0 (RCFG-01) |
| `PAYOUT_NOT_PAYABLE` | 400 | 지급 승인할 수 없는 정산입니다. | 승인 가드: FINALIZED 아님 / net ≤ 0 / 이미 지급 존재 |
| `PAYOUT_ACCOUNT_INACTIVE` | 400 | 활성 지급 계좌가 없습니다. | 승인 시 가맹점 활성 지급 계좌 미설정 |
| `PAYOUT_SIGNATURE_INVALID` | 401 | 지급 콜백 서명이 유효하지 않습니다. | webhook X-Bank-Signature 불일치 (상태 무변경) |
| `PAYOUT_ALREADY_EXISTS` | 409 | 이미 지급 건이 존재합니다. | 승인 시 정산에 이미 payout 존재(uk_payout_settlement) — 순차 재승인 또는 경합 패자. 바디는 기존 payout(id/status/amount, PayoutResponse) — 전용 핸들러가 {code,message} 대신 반환 |
| `PAYOUT_ACCOUNT_NOT_FOUND` | 404 | 지급 계좌를 찾을 수 없습니다. | GET 계좌 조회 시 활성 계좌 없음 (ACCT-02) |
| `PAYOUT_NOT_FOUND` | 404 | 지급 건을 찾을 수 없습니다. | GET 지급 조회 시 정산 헤더의 지급 건 없음 (PAY-03) |
| `RESERVE_CONFIG_NOT_FOUND` | 404 | 유보 정책을 찾을 수 없습니다. | GET 유보 정책 조회 시 미설정 (RCFG-02) |
| `RESERVE_NOT_FOUND` | 404 | 유보금을 찾을 수 없습니다. | GET 유보 상태 조회 시 정산의 유보금 없음 (HOLD-04) |

### 요청 형식 오류 (400)

| code | message | detail |
|------|---------|--------|
| `INVALID_REQUEST` | 요청 형식이 올바르지 않습니다. | `{ "fields": ["cancelAmount"] }` |
| `CANCEL_AMOUNT_MISMATCH` | 취소 항목 합계가 총 취소 금액과 일치하지 않습니다. | `{ "requestedTotal": 500000, "itemsTotal": 400000 }` |
| `DUPLICATE_PAYMENT_ITEM` | 동일한 항목이 중복 포함되어 있습니다. | `{ "duplicatedItemId": 12 }` |
| `EMPTY_CANCEL_ITEMS` | 취소 항목이 비어있습니다. | 생략 |
| `INVALID_CANCEL_AMOUNT` | 취소 금액은 1원 이상이어야 합니다. | `{ "cancelAmount": 0 }` |
| `COMPENSATION_MERCHANT_MISMATCH` | 보상 요청의 가맹점이 차감 이력과 일치하지 않습니다. | `{ "requestMerchantId": 1, "historyMerchantId": 2 }` |

### 인가 오류 (403)

| code | message | detail |
|------|---------|--------|
| `FORBIDDEN_PAYMENT` | 해당 결제에 대한 취소 권한이 없습니다. | 생략 |

### 리소스 없음 (404)

| code | message | detail |
|------|---------|--------|
| `PAYMENT_NOT_FOUND` | 결제 정보를 찾을 수 없습니다. | `{ "paymentKey": "pay_xyz" }` |
| `PAYMENT_ITEM_NOT_FOUND` | 취소 항목을 찾을 수 없습니다. | `{ "paymentItemId": 99 }` |
| `CANCEL_APPROVAL_NOT_FOUND` | 취소 승인 요청을 찾을 수 없습니다. | `{ "approvalId": 12 }` |

### 멱등 중복 (409)

| code | message | detail |
|------|---------|--------|
| `IDEMPOTENT_DUPLICATION` | 이미 처리된 요청입니다. | `{ "originalStatus": "COMPLETED", "cancelRequestId": "cr_abc" }` |
| `IDEMPOTENCY_KEY_CONFLICT` | 이미 다른 요청에 사용된 Idempotency-Key입니다. | `{ "idempotencyKey": "..." }` |
| `CANCEL_APPROVAL_CONFLICT` | 이미 진행 중이거나 결정된 취소 승인 요청입니다. | `{ "approvalId": 12 }` |

### 비즈니스 규칙 위반 (422)

| code | message | detail |
|------|---------|--------|
| `INVALID_PAYMENT_STATUS` | 현재 결제 상태에서는 취소할 수 없습니다. | `{ "currentStatus": "CANCELLED" }` |
| `INVALID_PAYMENT_ITEM_STATUS` | 이미 취소된 항목입니다. | `{ "paymentItemId": 12, "currentStatus": "CANCELLED" }` |
| `CANCEL_AMOUNT_EXCEEDED` | 취소 금액이 잔여 취소 가능액을 초과했습니다. | `{ "paymentItemId": 12, "requestedAmount": 500000, "availableAmount": 300000 }` |
| `MERCHANT_CANCEL_LIMIT_EXCEEDED` | 가맹점 일일 취소한도를 초과했습니다. | `{ "requestedAmount": 500000, "remainingLimit": 300000, "dailyLimit": 1000000 }` |
| `MERCHANT_CANCEL_LIMIT_NOT_FOUND` | 가맹점 취소한도가 설정되지 않았습니다. | `{ "merchantId": 123 }` |
| `CANCEL_PERIOD_EXCEEDED` | 취소 가능 기간이 지났습니다. | `{ "paymentCreatedAt": "2025-12-01T00:00:00Z", "cancelPeriodDays": 90 }` |
| `INVALID_ORDER_STATUS` | 현재 주문 상태에서는 취소할 수 없습니다. | `{ "currentStatus": "DELIVERING" }` |
| `MERCHANT_SUSPENDED` | 정지된 가맹점의 취소 요청은 처리할 수 없습니다. | 생략 |
| `RISK_REJECTED` | 위험관리 정책에 의해 취소가 거부되었습니다. | 생략 (보안상 상세 노출 금지) |

### 서버 오류 (500)

| code | message | detail |
|------|---------|--------|
| `INTERNAL_ERROR` | 서버 오류가 발생했습니다. | 생략 (내부 정보 노출 금지) |

### 외부 모듈 장애 (503)

| code | message | detail |
|------|---------|--------|
| `MERCHANT_LIMIT_SERVICE_UNAVAILABLE` | 취소한도 서비스가 일시적으로 이용 불가합니다. | `{ "retryAfterSeconds": 30 }` |
| `RISK_SERVICE_UNAVAILABLE` | 위험관리 서비스가 일시적으로 이용 불가합니다. | `{ "retryAfterSeconds": 30 }` |

---

## 규칙

- `code`는 대문자 SNAKE_CASE로 통일한다.
- `message`는 한국어로 작성한다.
- `detail`에 스택트레이스, SQL, 내부 경로를 절대 포함하지 않는다.
- `RISK_REJECTED`는 거부 사유를 detail에 포함하지 않는다.
- 500 에러는 서버 로그에만 상세 내용을 기록한다.
- 503 에러는 `retryAfterSeconds`를 포함해 클라이언트가 재시도 시점을 알 수 있게 한다.

---

## 에러 우선순위

```
1. 요청 형식 오류 (400)
2. 인가 오류 (403)
3. 리소스 없음 (404)
4. 멱등 중복 (409)
5. 비즈니스 규칙 (422) — 순서대로
     Payment 상태 확인
     PaymentItem 상태 확인
     취소 금액 검증
     가맹점 한도 검증
     위험관리 검증
```

---

## ErrorCode enum

에러 코드는 문자열이 아닌 `ErrorCode` enum으로 관리한다.
이 목록이 코드 레벨 원본이다. 새 에러가 필요하면 여기에 먼저 추가한다.

```java
// common/exception/ErrorCode.java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400 - 요청 형식 오류
    INVALID_REQUEST("INVALID_REQUEST", 400, "요청 형식이 올바르지 않습니다."),
    CANCEL_AMOUNT_MISMATCH("CANCEL_AMOUNT_MISMATCH", 400, "취소 항목 합계가 총 취소 금액과 일치하지 않습니다."),
    DUPLICATE_PAYMENT_ITEM("DUPLICATE_PAYMENT_ITEM", 400, "동일한 항목이 중복 포함되어 있습니다."),
    EMPTY_CANCEL_ITEMS("EMPTY_CANCEL_ITEMS", 400, "취소 항목이 비어있습니다."),
    INVALID_CANCEL_AMOUNT("INVALID_CANCEL_AMOUNT", 400, "취소 금액은 1원 이상이어야 합니다."),
    COMPENSATION_MERCHANT_MISMATCH("COMPENSATION_MERCHANT_MISMATCH", 400, "보상 요청의 가맹점이 차감 이력과 일치하지 않습니다."),

    // 401 - 내부 인증 오류
    INTERNAL_AUTHENTICATION_REQUIRED("INTERNAL_AUTHENTICATION_REQUIRED", 401, "내부 인증 정보가 필요합니다."),

    // 403 - 인가 오류
    FORBIDDEN_PAYMENT("FORBIDDEN_PAYMENT", 403, "해당 결제에 대한 취소 권한이 없습니다."),
    CANCEL_OUTBOX_REDRIVE_FORBIDDEN("CANCEL_OUTBOX_REDRIVE_FORBIDDEN", 403, "취소 아웃박스 복구 권한이 없습니다."),

    // 404 - 리소스 없음
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", 404, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_ITEM_NOT_FOUND("PAYMENT_ITEM_NOT_FOUND", 404, "취소 항목을 찾을 수 없습니다."),
    CANCEL_OUTBOX_NOT_FOUND("CANCEL_OUTBOX_NOT_FOUND", 404, "취소 아웃박스를 찾을 수 없습니다."),

    // 409 - 멱등 중복
    IDEMPOTENT_DUPLICATION("IDEMPOTENT_DUPLICATION", 409, "이미 처리된 요청입니다."),

    // 422 - 비즈니스 규칙 위반
    INVALID_PAYMENT_STATUS("INVALID_PAYMENT_STATUS", 422, "현재 결제 상태에서는 취소할 수 없습니다."),
    INVALID_PAYMENT_ITEM_STATUS("INVALID_PAYMENT_ITEM_STATUS", 422, "이미 취소된 항목입니다."),
    CANCEL_AMOUNT_EXCEEDED("CANCEL_AMOUNT_EXCEEDED", 422, "취소 금액이 잔여 취소 가능액을 초과했습니다."),
    MERCHANT_CANCEL_LIMIT_EXCEEDED("MERCHANT_CANCEL_LIMIT_EXCEEDED", 422, "가맹점 일일 취소한도를 초과했습니다."),
    MERCHANT_CANCEL_LIMIT_NOT_FOUND("MERCHANT_CANCEL_LIMIT_NOT_FOUND", 422, "가맹점 취소한도가 설정되지 않았습니다."),
    CANCEL_PERIOD_EXCEEDED("CANCEL_PERIOD_EXCEEDED", 422, "취소 가능 기간이 지났습니다."),
    INVALID_ORDER_STATUS("INVALID_ORDER_STATUS", 422, "현재 주문 상태에서는 취소할 수 없습니다."),
    MERCHANT_SUSPENDED("MERCHANT_SUSPENDED", 422, "정지된 가맹점의 취소 요청은 처리할 수 없습니다."),
    RISK_REJECTED("RISK_REJECTED", 422, "위험관리 정책에 의해 취소가 거부되었습니다."),

    // 500 - 서버 오류
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다."),

    // 503 - 외부 모듈 장애
    MERCHANT_LIMIT_SERVICE_UNAVAILABLE("MERCHANT_LIMIT_SERVICE_UNAVAILABLE", 503, "취소한도 서비스가 일시적으로 이용 불가합니다."),
    RISK_SERVICE_UNAVAILABLE("RISK_SERVICE_UNAVAILABLE", 503, "위험관리 서비스가 일시적으로 이용 불가합니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
```

---

## 예외 클래스 매핑

### common/exception

```java
@Getter
public abstract class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    // 상세 메시지가 필요할 때 (예: 현재 상태값 포함)
    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
```

### domain/exception — 비즈니스 규칙 위반

| 예외 클래스 | ErrorCode |
|------------|---------|
| `InvalidCancelAmountException` | `INVALID_CANCEL_AMOUNT` |
| `InvalidPaymentStatusException` | `INVALID_PAYMENT_STATUS` |
| `InvalidPaymentItemStatusException` | `INVALID_PAYMENT_ITEM_STATUS` |
| `CancelAmountExceededException` | `CANCEL_AMOUNT_EXCEEDED` |
| `CancelPeriodExceededException` | `CANCEL_PERIOD_EXCEEDED` |
| `InvalidCancelStateTransitionException` | `INVALID_PAYMENT_STATUS` |
| `MerchantSuspendedException` | `MERCHANT_SUSPENDED` |

### application/exception — 리소스 없음, 멱등 중복

| 예외 클래스 | ErrorCode |
|------------|---------|
| `PaymentNotFoundException` | `PAYMENT_NOT_FOUND` |
| `PaymentItemNotFoundException` | `PAYMENT_ITEM_NOT_FOUND` |
| `IdempotentDuplicationException` | `IDEMPOTENT_DUPLICATION` |
| `MerchantCancelLimitNotFoundException` | `MERCHANT_CANCEL_LIMIT_NOT_FOUND` |
| `MerchantCancelLimitExceededException` | `MERCHANT_CANCEL_LIMIT_EXCEEDED` |
| `CompensationMerchantMismatchException` | `COMPENSATION_MERCHANT_MISMATCH` |
| `DataInconsistencyException` | `INTERNAL_ERROR` |
| `InternalAuthenticationRequiredException` | `INTERNAL_AUTHENTICATION_REQUIRED` |
| `CancelOutboxForbiddenException` | `CANCEL_OUTBOX_REDRIVE_FORBIDDEN` |
| `CancelOutboxNotFoundException` | `CANCEL_OUTBOX_NOT_FOUND` |

### infrastructure/exception — 외부 연동 실패

| 예외 클래스 | ErrorCode |
|------------|---------|
| `MerchantLimitServiceException` | `MERCHANT_LIMIT_SERVICE_UNAVAILABLE` |
| `RiskServiceException` | `RISK_SERVICE_UNAVAILABLE` |
| `OrderVerifyUnavailableException` | `ORDER_VERIFY_UNAVAILABLE` |
| `OrderVerifyRejectedException` | `ORDER_ITEM_NOT_FOUND` / `ORDER_ITEMS_MULTIPLE_ORDERS` / `ORDER_OWNERSHIP_MISMATCH`(생성자 인자로 판정) |
