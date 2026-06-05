package com.example.payment.fixture;

import com.example.payment.domain.entity.PaymentItem;
import java.math.BigDecimal;

public class PaymentItemFixture {

    public static PaymentItem active(long paymentId, long orderItemId, BigDecimal amount) {
        return PaymentItem.of(paymentId, orderItemId, 100L, 200L, 0L, 1, "상품", amount);
    }

    public static PaymentItem cancelled(long paymentId, long orderItemId, BigDecimal amount) {
        PaymentItem item = active(paymentId, orderItemId, amount);
        item.cancel();
        return item;
    }

    private PaymentItemFixture() {}
}
