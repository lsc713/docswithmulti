package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * 취소 인가 실패 (FORBIDDEN_PAYMENT, 403).
 *
 * 신규 에러코드를 만들지 않고 기존 FORBIDDEN_PAYMENT 를 재사용한다 (D-P3-4).
 * presentation pre-check 에서 던지면 기존 GlobalExceptionHandler 가 403 으로 매핑한다.
 */
public class CancelNotAuthorizedException extends BusinessException {
    public CancelNotAuthorizedException() {
        super(ErrorCode.FORBIDDEN_PAYMENT);
    }
}
