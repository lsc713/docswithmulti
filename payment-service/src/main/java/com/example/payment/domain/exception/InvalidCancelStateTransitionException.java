package com.example.payment.domain.exception;

import com.example.payment.domain.entity.CancelStatus;

/**
 * 취소 요청의 상태 전이가 불가능할 때 발생
 *
 * 예: COMPLETED 상태에서 PROCESSING으로 전이 시도
 *
 * 가정:
 * - 이 예외는 결제 상태 불일치로 매핑됨
 * - 대응하는 HTTP 상태코드: 422 INVALID_PAYMENT_STATUS
 * - (error-catalog.md의 "현재 결제 상태에서는 취소할 수 없습니다" 참조)
 */
public class InvalidCancelStateTransitionException extends DomainException {

    private final CancelStatus currentStatus;
    private final CancelStatus requestedStatus;

    public InvalidCancelStateTransitionException(
        CancelStatus currentStatus,
        CancelStatus requestedStatus
    ) {
        super(
            "INVALID_PAYMENT_STATUS",
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
