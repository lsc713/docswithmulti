package com.example.settlement.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 400
    INVALID_STATUS("INVALID_STATUS", 400, "유효하지 않은 정산 상태입니다. (OPEN | FINALIZED)"),
    INVALID_PAYOUT_ACCOUNT("INVALID_PAYOUT_ACCOUNT", 400, "지급 계좌 정보가 올바르지 않습니다."),
    INVALID_RESERVE_CONFIG("INVALID_RESERVE_CONFIG", 400, "유보 정책 값이 올바르지 않습니다."),
    PAYOUT_NOT_PAYABLE("PAYOUT_NOT_PAYABLE", 400, "지급 승인할 수 없는 정산입니다."),
    PAYOUT_ACCOUNT_INACTIVE("PAYOUT_ACCOUNT_INACTIVE", 400, "활성 지급 계좌가 없습니다."),
    // 401
    PAYOUT_SIGNATURE_INVALID("PAYOUT_SIGNATURE_INVALID", 401, "지급 콜백 서명이 유효하지 않습니다."),
    // 409
    PAYOUT_ALREADY_EXISTS("PAYOUT_ALREADY_EXISTS", 409, "이미 지급 건이 존재합니다."),
    // 404
    SETTLEMENT_NOT_FOUND("SETTLEMENT_NOT_FOUND", 404, "정산 내역을 찾을 수 없습니다."),
    PAYOUT_NOT_FOUND("PAYOUT_NOT_FOUND", 404, "지급 건을 찾을 수 없습니다."),
    PAYOUT_ACCOUNT_NOT_FOUND("PAYOUT_ACCOUNT_NOT_FOUND", 404, "지급 계좌를 찾을 수 없습니다."),
    RESERVE_CONFIG_NOT_FOUND("RESERVE_CONFIG_NOT_FOUND", 404, "유보 정책을 찾을 수 없습니다."),
    RESERVE_NOT_FOUND("RESERVE_NOT_FOUND", 404, "유보금을 찾을 수 없습니다."),
    // 500
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
