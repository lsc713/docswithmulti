package com.example.riskmanagement.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    CANCEL_LIMIT_EXCEEDED("CANCEL_LIMIT_EXCEEDED", 422, "가맹점 일일 취소한도를 초과했습니다."),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", 503, "일시적 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
