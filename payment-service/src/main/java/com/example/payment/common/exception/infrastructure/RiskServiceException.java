package com.example.payment.common.exception.infrastructure;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class RiskServiceException extends BusinessException {

    public RiskServiceException(String message) {
        super(ErrorCode.RISK_SERVICE_UNAVAILABLE, message);
    }

    public RiskServiceException(String message, Throwable cause) {
        super(ErrorCode.RISK_SERVICE_UNAVAILABLE, message, cause);
    }
}
