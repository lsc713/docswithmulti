package com.example.payment.domain.exception;

/**
 * 도메인 계층의 비즈니스 규칙 위반을 나타내는 기본 예외
 *
 * 이 예외는 도메인 규칙 위반 시에만 발생하며,
 * HTTP 상태코드 매핑은 presentation 레이어에서 처리한다.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
