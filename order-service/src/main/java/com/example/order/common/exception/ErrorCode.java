package com.example.order.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 에러 코드 enum
 *
 * docs/error-catalog.md의 order-service 에러 코드를 코드 레벨 원본으로 관리한다.
 * 새 에러가 필요하면 여기에 먼저 추가한다. (payment-service의 ErrorCode와는 별개 원본)
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 403 - 인가 오류
    FORBIDDEN("FORBIDDEN", 403, "권한이 없습니다."),
    ORDER_OWNERSHIP_MISMATCH("ORDER_OWNERSHIP_MISMATCH", 403, "해당 주문에 대한 권한이 없습니다."),

    // 404 - 리소스 없음
    ORDER_ITEM_NOT_FOUND("ORDER_ITEM_NOT_FOUND", 404, "주문 항목을 찾을 수 없습니다."),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", 404, "주문을 찾을 수 없습니다."),
    CART_ITEM_NOT_FOUND("CART_ITEM_NOT_FOUND", 404, "장바구니 항목을 찾을 수 없습니다."),

    // 409 - 비즈니스 규칙 위반 (복수 order 걸침)
    ORDER_ITEMS_MULTIPLE_ORDERS("ORDER_ITEMS_MULTIPLE_ORDERS", 409, "요청된 항목이 여러 주문에 걸쳐 있습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
