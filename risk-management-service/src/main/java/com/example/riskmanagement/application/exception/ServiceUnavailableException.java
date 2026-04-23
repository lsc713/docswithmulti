package com.example.riskmanagement.application.exception;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;

public class ServiceUnavailableException extends BusinessException {
    public ServiceUnavailableException() {
        super(ErrorCode.MERCHANT_LIMIT_SERVICE_UNAVAILABLE);
    }
}
