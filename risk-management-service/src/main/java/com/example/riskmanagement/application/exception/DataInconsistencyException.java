package com.example.riskmanagement.application.exception;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;

/**
 * "일어날 수 없는" 데이터 불일치(예: ensureRow 직후인데 usage row 부재, history 있는데 usage 없음).
 * 비즈니스 조건이 아니라 버그/손상 신호 → {@link ErrorCode#INTERNAL_ERROR}(500).
 * raw {@code IllegalStateException} 대신 예외 계층(BusinessException)으로 일관되게 처리하기 위함.
 */
public class DataInconsistencyException extends BusinessException {
    public DataInconsistencyException(String message) {
        super(ErrorCode.INTERNAL_ERROR, message);
    }
}
