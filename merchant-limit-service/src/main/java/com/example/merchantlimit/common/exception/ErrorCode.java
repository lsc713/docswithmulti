package com.example.merchantlimit.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 404
    MERCHANT_NOT_FOUND("MERCHANT_NOT_FOUND", 404, "가맹점 정보를 찾을 수 없습니다."),
    // 409
    MERCHANT_KEY_DUPLICATED("MERCHANT_KEY_DUPLICATED", 409, "이미 사용 중인 가맹점 키입니다."),
    // 422
    MERCHANT_CANCEL_LIMIT_NOT_FOUND("MERCHANT_CANCEL_LIMIT_NOT_FOUND", 422, "가맹점 취소한도가 설정되지 않았습니다."),
    INVALID_LIMIT_AMOUNT("INVALID_LIMIT_AMOUNT", 422, "한도는 1원 이상이어야 합니다."),
    MERCHANT_SUSPENDED("MERCHANT_SUSPENDED", 422, "정지된 가맹점의 한도를 변경할 수 없습니다."),
    // 500
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
