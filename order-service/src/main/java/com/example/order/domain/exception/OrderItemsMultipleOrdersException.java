package com.example.order.domain.exception;

import com.example.order.common.exception.BusinessException;
import com.example.order.common.exception.ErrorCode;

/** 요청된 orderItemId들이 2개 이상의 order에 걸쳐 있을 때. */
public class OrderItemsMultipleOrdersException extends BusinessException {

    public OrderItemsMultipleOrdersException() {
        super(ErrorCode.ORDER_ITEMS_MULTIPLE_ORDERS);
    }
}
