package com.example.payment.presentation.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * Idempotency-Key 헤더가 형식 제약(길이 ≤255)을 위반함
 *
 * spec §8: 키 길이/형식 - 255자 초과 시 400.
 */
public class InvalidIdempotencyKeyException extends BusinessException {

    public InvalidIdempotencyKeyException() {
        super(ErrorCode.INVALID_REQUEST, "Idempotency-Key는 255자를 초과할 수 없습니다.");
    }
}
