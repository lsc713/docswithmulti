package com.example.order.domain.exception;

import com.example.order.common.exception.BusinessException;
import com.example.order.common.exception.ErrorCode;

/** order.userId != 요청자(X-User-Id)일 때. */
public class OrderOwnershipMismatchException extends BusinessException {

    public OrderOwnershipMismatchException() {
        super(ErrorCode.ORDER_OWNERSHIP_MISMATCH);
    }
}
