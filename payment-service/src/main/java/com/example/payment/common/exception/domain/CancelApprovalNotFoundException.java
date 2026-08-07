package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * 취소 승인 요청을 찾을 수 없음 (CANCEL_APPROVAL_NOT_FOUND, 404).
 */
public class CancelApprovalNotFoundException extends BusinessException {
    public CancelApprovalNotFoundException(long approvalId) {
        super(ErrorCode.CANCEL_APPROVAL_NOT_FOUND, "취소 승인 요청을 찾을 수 없습니다: " + approvalId);
    }
}
