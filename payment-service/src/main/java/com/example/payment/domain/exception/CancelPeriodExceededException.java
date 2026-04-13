package com.example.payment.domain.exception;

import com.example.payment.common.exception.ErrorCode;
import java.time.LocalDateTime;

/**
 * 취소 가능 기간을 초과했을 때 발생
 *
 * Payment.created_at + cancel_period_days를 초과한 경우
 */
public class CancelPeriodExceededException extends DomainException {

    private final LocalDateTime createdAt;
    private final int cancelPeriodDays;

    public CancelPeriodExceededException(LocalDateTime createdAt, int cancelPeriodDays) {
        super(
            ErrorCode.CANCEL_PERIOD_EXCEEDED,
            String.format(
                "취소 가능 기간(%d일)을 초과했습니다. (결제일: %s)",
                cancelPeriodDays, createdAt
            )
        );
        this.createdAt = createdAt;
        this.cancelPeriodDays = cancelPeriodDays;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public int getCancelPeriodDays() {
        return cancelPeriodDays;
    }
}
