package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

public class SettlementNotFoundException extends BusinessException {
    public SettlementNotFoundException(long id) {
        super(ErrorCode.SETTLEMENT_NOT_FOUND, "정산 내역을 찾을 수 없습니다. id=" + id);
    }
}
