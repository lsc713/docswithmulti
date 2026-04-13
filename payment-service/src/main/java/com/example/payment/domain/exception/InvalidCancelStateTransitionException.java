package com.example.payment.domain.exception;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.CancelStatus;

/**
 * 취소 요청의 상태 전이가 불가능할 때 발생
 *
 * 예: COMPLETED 상태에서 PROCESSING으로 전이 시도
 */
public class InvalidCancelStateTransitionException extends DomainException {

    private final CancelStatus currentStatus;
    private final CancelStatus requestedStatus;

    public InvalidCancelStateTransitionException(
        CancelStatus currentStatus,
        CancelStatus requestedStatus
    ) {
        super(
            ErrorCode.INVALID_PAYMENT_STATUS,
            String.format(
                "취소 요청을 %s 상태에서 %s 상태로 전이할 수 없습니다",
                currentStatus, requestedStatus
            )
        );
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

    public CancelStatus getCurrentStatus() {
        return currentStatus;
    }

    public CancelStatus getRequestedStatus() {
        return requestedStatus;
    }
}
