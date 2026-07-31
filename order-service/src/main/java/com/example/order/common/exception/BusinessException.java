package com.example.order.common.exception;

import lombok.Getter;

/**
 * 모든 비즈니스 예외의 부모 클래스
 *
 * 이 예외는 business logic 계층에서 발생하며,
 * presentation 레이어에서 errorCode 기반으로 통일된 응답을 생성한다.
 * (payment-service common/exception/BusinessException과 동일 패턴 미러)
 */
@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
