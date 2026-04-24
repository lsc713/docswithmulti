package com.example.riskmanagement.application.exception;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;

public class ServiceUnavailableException extends BusinessException {

    public ServiceUnavailableException(ErrorCode errorCode) {
        super(errorCode);
    }

    public static ServiceUnavailableException merchantLimitUnavailable() {
        return new ServiceUnavailableException(ErrorCode.MERCHANT_LIMIT_SERVICE_UNAVAILABLE);
    }

    public static ServiceUnavailableException riskServiceUnavailable() {
        return new ServiceUnavailableException(ErrorCode.RISK_SERVICE_UNAVAILABLE);
    }
}
