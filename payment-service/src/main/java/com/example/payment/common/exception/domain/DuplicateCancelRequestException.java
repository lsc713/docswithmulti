package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * 취소 승인 요청 충돌 (CANCEL_APPROVAL_CONFLICT, 409).
 *
 * 두 가지 경우에 재사용한다:
 *   - 동일 결제에 이미 진행 중인(REQUESTED) 승인 요청이 있는데 새 요청을 시도
 *   - 이미 결정된(APPROVED/REJECTED) 승인 요청에 대해 다시 승인/반려를 시도
 */
public class DuplicateCancelRequestException extends BusinessException {
    public DuplicateCancelRequestException(String message) {
        super(ErrorCode.CANCEL_APPROVAL_CONFLICT, message);
    }
}
