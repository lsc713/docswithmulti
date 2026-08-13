package com.example.payment.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 에러 코드 enum
 *
 * error-catalog.md의 에러 코드를 코드 레벨 원본으로 관리한다.
 * 새 에러가 필요하면 여기에 먼저 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400 - 요청 형식 오류
    INVALID_REQUEST("INVALID_REQUEST", 400, "요청 형식이 올바르지 않습니다."),
    CANCEL_AMOUNT_MISMATCH("CANCEL_AMOUNT_MISMATCH", 400, "취소 항목 합계가 총 취소 금액과 일치하지 않습니다."),
    DUPLICATE_PAYMENT_ITEM("DUPLICATE_PAYMENT_ITEM", 400, "동일한 항목이 중복 포함되어 있습니다."),
    EMPTY_CANCEL_ITEMS("EMPTY_CANCEL_ITEMS", 400, "취소 항목이 비어있습니다."),
    INVALID_CANCEL_AMOUNT("INVALID_CANCEL_AMOUNT", 400, "취소 금액은 1원 이상이어야 합니다."),

    // 401 - 내부 인증 오류
    INTERNAL_AUTHENTICATION_REQUIRED("INTERNAL_AUTHENTICATION_REQUIRED", 401, "내부 인증 정보가 필요합니다."),

    // 403 - 인가 오류
    FORBIDDEN_PAYMENT("FORBIDDEN_PAYMENT", 403, "해당 결제에 대한 취소 권한이 없습니다."),
    ORDER_OWNERSHIP_MISMATCH("ORDER_OWNERSHIP_MISMATCH", 403, "해당 주문에 대한 권한이 없습니다."),
    CANCEL_OUTBOX_REDRIVE_FORBIDDEN("CANCEL_OUTBOX_REDRIVE_FORBIDDEN", 403, "취소 아웃박스 복구 권한이 없습니다."),

    // 404 - 리소스 없음
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", 404, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_ITEM_NOT_FOUND("PAYMENT_ITEM_NOT_FOUND", 404, "취소 항목을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_FOUND("ORDER_ITEM_NOT_FOUND", 404, "주문 항목을 찾을 수 없습니다."),
    CANCEL_APPROVAL_NOT_FOUND("CANCEL_APPROVAL_NOT_FOUND", 404, "취소 승인 요청을 찾을 수 없습니다."),
    CANCEL_OUTBOX_NOT_FOUND("CANCEL_OUTBOX_NOT_FOUND", 404, "취소 아웃박스를 찾을 수 없습니다."),
    PAYMENT_ATTEMPT_NOT_FOUND("PAYMENT_ATTEMPT_NOT_FOUND", 404, "결제 시도를 찾을 수 없습니다."),
    CANCEL_OUTBOX_REDRIVE_NOT_FOUND("CANCEL_OUTBOX_REDRIVE_NOT_FOUND", 404, "취소 아웃박스 복구 요청을 찾을 수 없습니다."),

    // 409 - 멱등 중복 / 재고 부족
    IDEMPOTENT_DUPLICATION("IDEMPOTENT_DUPLICATION", 409, "이미 처리된 요청입니다."),
    STOCK_INSUFFICIENT("STOCK_INSUFFICIENT", 409, "재고가 부족하여 결제를 생성할 수 없습니다."),
    IDEMPOTENCY_KEY_CONFLICT("IDEMPOTENCY_KEY_CONFLICT", 409, "이미 다른 요청에 사용된 Idempotency-Key입니다."),
    ORDER_ITEMS_MULTIPLE_ORDERS("ORDER_ITEMS_MULTIPLE_ORDERS", 409, "요청된 항목이 여러 주문에 걸쳐 있습니다."),
    CANCEL_APPROVAL_CONFLICT("CANCEL_APPROVAL_CONFLICT", 409, "이미 진행 중이거나 결정된 취소 승인 요청입니다."),
    CANCEL_OUTBOX_NOT_DEAD("CANCEL_OUTBOX_NOT_DEAD", 409, "DEAD 상태의 취소 아웃박스만 복구할 수 있습니다."),
    ACTIVE_REDRIVE_EXISTS("ACTIVE_REDRIVE_EXISTS", 409, "이미 진행 중인 취소 아웃박스 복구 요청이 있습니다."),
    REDRIVE_ALREADY_RESOLVED("REDRIVE_ALREADY_RESOLVED", 409, "이미 해결된 취소 아웃박스 복구 요청이 있습니다."),
    PAYMENT_ATTEMPT_CONFLICT("PAYMENT_ATTEMPT_CONFLICT", 409, "진행 중인 결제 시도가 이미 있습니다."),

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
    PAYMENT_CONFIRM_MISMATCH("PAYMENT_CONFIRM_MISMATCH", 422, "결제 승인 정보가 저장된 결제 시도와 다릅니다."),
    PAYMENT_APPROVAL_REJECTED("PAYMENT_APPROVAL_REJECTED", 422, "결제 승인이 거절되었습니다."),

    // 500 - 서버 오류
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다."),

    // 503 - 외부 모듈 장애
    MERCHANT_LIMIT_SERVICE_UNAVAILABLE("MERCHANT_LIMIT_SERVICE_UNAVAILABLE", 503, "취소한도 서비스가 일시적으로 이용 불가합니다."),
    RISK_SERVICE_UNAVAILABLE("RISK_SERVICE_UNAVAILABLE", 503, "위험관리 서비스가 일시적으로 이용 불가합니다."),
    PG_SERVICE_UNAVAILABLE("PG_SERVICE_UNAVAILABLE", 503, "PG 서비스가 일시적으로 이용 불가합니다."),
    PRODUCT_SERVICE_UNAVAILABLE("PRODUCT_SERVICE_UNAVAILABLE", 503, "상품 재고 서비스가 일시적으로 이용 불가합니다."),
    ORDER_VERIFY_UNAVAILABLE("ORDER_VERIFY_UNAVAILABLE", 503, "주문 검증 서비스가 일시적으로 이용 불가합니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
