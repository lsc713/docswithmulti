package com.example.order.domain.exception;

import com.example.order.common.exception.BusinessException;
import com.example.order.common.exception.ErrorCode;

import java.util.List;

/**
 * items:verify 검증 전용 — 요청된 orderItemId 중 존재하지 않는 항목이 있을 때.
 * application/exception/OrderItemNotFoundException(Kafka 컨슈머/RetryRouter 경로)과는
 * 별개 — 재사용하지 않는다(load-bearing, D-CONTEXT-2 별도 관리 결정).
 */
public class VerifyOrderItemNotFoundException extends BusinessException {

    public VerifyOrderItemNotFoundException(List<Long> missingOrderItemIds) {
        super(ErrorCode.ORDER_ITEM_NOT_FOUND,
            ErrorCode.ORDER_ITEM_NOT_FOUND.getDefaultMessage() + " orderItemIds=" + missingOrderItemIds);
    }
}
