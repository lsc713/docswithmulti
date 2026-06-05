package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.CancelStatus;
import lombok.Getter;

/**
 * 취소 요청의 상태 전이가 불가능할 때 발생
 *
 * 예: COMPLETED 상태에서 PROCESSING으로 전이 시도
 */
@Getter
public class InvalidCancelStateTransitionException extends BusinessException {

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

}
