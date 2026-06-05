package com.example.user.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_REQUEST("INVALID_REQUEST", 400, "요청 형식이 올바르지 않습니다."),
    INVALID_CREDENTIALS("USER_002", 401, "이메일 또는 비밀번호가 일치하지 않습니다."),
    EXPIRED_TOKEN("USER_005", 401, "만료된 토큰입니다."),
    INVALID_TOKEN("USER_006", 401, "유효하지 않은 토큰입니다."),
    EXPIRED_REFRESH_TOKEN("USER_007", 401, "만료된 리프레시 토큰입니다."),
    SUSPENDED_ACCOUNT("USER_004", 403, "정지된 계정입니다."),
    FORBIDDEN("USER_010", 403, "권한이 없습니다."),
    USER_NOT_FOUND("USER_003", 404, "유저를 찾을 수 없습니다."),
    ADDRESS_NOT_FOUND("USER_008", 404, "배송지를 찾을 수 없습니다."),
    PAYMENT_METHOD_NOT_FOUND("USER_009", 404, "결제수단을 찾을 수 없습니다."),
    DUPLICATE_EMAIL("USER_001", 409, "이미 등록된 이메일입니다."),
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
