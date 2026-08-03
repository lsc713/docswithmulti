package com.example.settlement.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 400
    INVALID_STATUS("INVALID_STATUS", 400, "유효하지 않은 정산 상태입니다. (OPEN | FINALIZED)"),
    // 404
    SETTLEMENT_NOT_FOUND("SETTLEMENT_NOT_FOUND", 404, "정산 내역을 찾을 수 없습니다."),
    // 500
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
