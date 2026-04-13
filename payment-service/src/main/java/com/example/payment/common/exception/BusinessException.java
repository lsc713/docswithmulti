package com.example.payment.common.exception;

/**
 * 모든 비즈니스 예외의 부모 클래스
 *
 * 이 예외는 business logic 계층에서 발생하며,
 * presentation 레이어에서 errorCode 기반으로 통일된 응답을 생성한다.
 *
 * errorCode는 error-catalog.md의 코드와 1:1 매핑된다.
 */
public abstract class BusinessException extends RuntimeException {

    private final String errorCode;

    protected BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected BusinessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
