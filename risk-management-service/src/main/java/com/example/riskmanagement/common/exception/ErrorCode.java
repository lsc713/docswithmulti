package com.example.riskmanagement.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    MERCHANT_CANCEL_LIMIT_EXCEEDED("MERCHANT_CANCEL_LIMIT_EXCEEDED", 422, "가맹점 일일 취소한도를 초과했습니다."),
    MERCHANT_CANCEL_LIMIT_NOT_FOUND("MERCHANT_CANCEL_LIMIT_NOT_FOUND", 422, "가맹점 취소한도가 설정되지 않았습니다."),
    MERCHANT_LIMIT_SERVICE_UNAVAILABLE("MERCHANT_LIMIT_SERVICE_UNAVAILABLE", 503, "취소한도 서비스가 일시적으로 이용 불가합니다."),
    RISK_SERVICE_UNAVAILABLE("RISK_SERVICE_UNAVAILABLE", 503, "위험관리 서비스가 일시적으로 이용 불가합니다."),
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
