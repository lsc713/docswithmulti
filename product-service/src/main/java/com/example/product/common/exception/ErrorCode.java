package com.example.product.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_REQUEST("INVALID_REQUEST", 400, "요청 형식이 올바르지 않습니다."),
    STOCK_INSUFFICIENT("STOCK_001", 409, "재고가 부족합니다."),
    CATEGORY_DEPTH_EXCEEDED("CATEGORY_001", 400, "카테고리 깊이는 3단계까지만 허용됩니다."),
    CATEGORY_NAME_DUPLICATE("CATEGORY_002", 409, "같은 부모 아래 이름이 중복됩니다."),
    CATEGORY_NOT_FOUND("CATEGORY_003", 404, "카테고리를 찾을 수 없습니다."),
    PRODUCT_CATEGORY_INVALID("PRODUCT_001", 400, "상품은 소분류(leaf) 카테고리에만 등록할 수 있습니다."),
    PRODUCT_NOT_FOUND("PRODUCT_002", 404, "상품을 찾을 수 없습니다."),
    FORBIDDEN("FORBIDDEN", 403, "권한이 없습니다."),
    IMAGE_KEY_INVALID("IMAGE_001", 400, "존재하지 않는 이미지 키입니다."),
    IMAGE_NOT_FOUND("IMAGE_002", 404, "이미지를 찾을 수 없습니다."),
    ATTRIBUTE_NAME_DUPLICATE("ATTRIBUTE_001", 409, "속성 이름이 중복됩니다."),
    ATTRIBUTE_VALUE_DUPLICATE("ATTRIBUTE_002", 409, "같은 속성 내 값이 중복됩니다."),
    ATTRIBUTE_NOT_FOUND("ATTRIBUTE_003", 404, "속성을 찾을 수 없습니다."),
    VARIANT_INCOMPLETE("VARIANT_001", 400, "SKU 변형 조합이 선언된 변형 속성을 정확히 하나씩 커버해야 합니다."),
    VARIANT_COMBINATION_DUPLICATE("VARIANT_002", 409, "상품 내 변형 조합이 중복됩니다."),
    VARIANT_VALUE_INVALID("VARIANT_003", 400, "변형값이 그 상품의 변형 속성 소속이 아닙니다."),
    DESCRIPTIVE_VALUE_INVALID("DESCRIPTIVE_001", 400, "서술값이 그 상품의 서술 속성 소속이 아닙니다."),
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
