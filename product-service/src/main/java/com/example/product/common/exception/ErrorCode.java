package com.example.product.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 에러 코드 enum
 *
 * 새 에러가 필요하면 여기에 먼저 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400 - 요청 형식 오류
    INVALID_REQUEST("INVALID_REQUEST", 400, "요청 형식이 올바르지 않습니다."),

    // 403 - 인가 오류
    FORBIDDEN_PRODUCT("FORBIDDEN_PRODUCT", 403, "해당 상품에 대한 접근 권한이 없습니다."),

    // 404 - 리소스 없음
    PRODUCT_NOT_FOUND("PRODUCT_NOT_FOUND", 404, "상품 정보를 찾을 수 없습니다."),
    SKU_NOT_FOUND("SKU_NOT_FOUND", 404, "SKU 정보를 찾을 수 없습니다."),
    CATEGORY_NOT_FOUND("CATEGORY_NOT_FOUND", 404, "카테고리 정보를 찾을 수 없습니다."),

    // 409 - 중복
    DUPLICATE_SKU_CODE("DUPLICATE_SKU_CODE", 409, "이미 존재하는 SKU 코드입니다."),

    // 422 - 비즈니스 규칙 위반
    INSUFFICIENT_STOCK("INSUFFICIENT_STOCK", 422, "재고가 부족합니다."),
    CATEGORY_HAS_CHILDREN("CATEGORY_HAS_CHILDREN", 422, "하위 카테고리가 존재하여 삭제할 수 없습니다."),

    // 500 - 서버 오류
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
