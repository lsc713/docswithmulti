package com.example.product.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_REQUEST("INVALID_REQUEST", 400, "요청 형식이 올바르지 않습니다."),
    STOCK_INSUFFICIENT("STOCK_001", 409, "재고가 부족합니다."),
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
