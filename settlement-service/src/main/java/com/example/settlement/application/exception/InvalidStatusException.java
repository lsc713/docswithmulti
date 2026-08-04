package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

public class InvalidStatusException extends BusinessException {
    public InvalidStatusException(String status) {
        super(ErrorCode.INVALID_STATUS, "유효하지 않은 정산 상태입니다: " + status);
    }
}
