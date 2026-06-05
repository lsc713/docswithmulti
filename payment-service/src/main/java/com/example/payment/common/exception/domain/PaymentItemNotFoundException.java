package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import lombok.Getter;

/**
 * PaymentItem을 찾을 수 없을 때 발생
 *
 * orderItemId에 해당하는 PaymentItem이 Payment에 존재하지 않을 때 발생한다.
 * 이는 데이터 무결성 문제로, Application 레이어가 올바른 데이터를 전달하지 못했음을 의미한다.
 */
@Getter
public class PaymentItemNotFoundException extends BusinessException {

    private final long orderItemId;

    public PaymentItemNotFoundException(long orderItemId) {
        super(
            ErrorCode.PAYMENT_ITEM_NOT_FOUND,
            String.format(
                "PaymentItem(orderItemId=%d)을 찾을 수 없습니다.",
                orderItemId
            )
        );
        this.orderItemId = orderItemId;
    }

}
