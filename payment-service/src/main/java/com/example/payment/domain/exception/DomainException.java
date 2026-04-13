package com.example.payment.domain.exception;

import com.example.payment.common.exception.BusinessException;

/**
 * 도메인 계층의 비즈니스 규칙 위반을 나타내는 기본 예외
 *
 * 이 예외는 도메인 규칙 위반 시에만 발생하며,
 * HTTP 상태코드 매핑은 presentation 레이어에서 처리한다.
 */
public abstract class DomainException extends BusinessException {

    protected DomainException(String errorCode, String message) {
        super(errorCode, message);
    }

    protected DomainException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
