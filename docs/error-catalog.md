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
| 403 | 인가 실패 (권한 없음) |
| 404 | 리소스 없음 |
| 409 | 멱등 중복 요청 |
| 422 | 비즈니스 규칙 위반 |
| 500 | 서버 내부 오류 |
| 503 | 외부 모듈 장애 |

---

## 에러 코드 목록

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

### 멱등 중복 (409)

| code | message | detail |
|------|---------|--------|
| `IDEMPOTENT_DUPLICATION` | 이미 처리된 요청입니다. | `{ "originalStatus": "COMPLETED", "cancelRequestId": "cr_abc" }` |

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

    // 403 - 인가 오류
    FORBIDDEN_PAYMENT("FORBIDDEN_PAYMENT", 403, "해당 결제에 대한 취소 권한이 없습니다."),

    // 404 - 리소스 없음
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", 404, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_ITEM_NOT_FOUND("PAYMENT_ITEM_NOT_FOUND", 404, "취소 항목을 찾을 수 없습니다."),

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

### infrastructure/exception — 외부 연동 실패

| 예외 클래스 | ErrorCode |
|------------|---------|
| `MerchantLimitServiceException` | `MERCHANT_LIMIT_SERVICE_UNAVAILABLE` |
| `RiskServiceException` | `RISK_SERVICE_UNAVAILABLE` |