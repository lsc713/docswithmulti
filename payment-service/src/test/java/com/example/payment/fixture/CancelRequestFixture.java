package com.example.payment.fixture;

import com.example.payment.domain.entity.CancelRequest;
import java.math.BigDecimal;

public class CancelRequestFixture {

    public static CancelRequest pending(Long paymentId, BigDecimal cancelAmount) {
        return CancelRequest.create(paymentId, "hash_" + paymentId, cancelAmount, "고객 변심");
    }

    public static CancelRequest completed(Long paymentId, BigDecimal cancelAmount) {
        CancelRequest r = pending(paymentId, cancelAmount);
        r.toProcessing();
        r.toCompleted();
        return r;
    }

    public static CancelRequest failed(Long paymentId, BigDecimal cancelAmount) {
        CancelRequest r = pending(paymentId, cancelAmount);
        r.toProcessing();
        r.toFailed("오류");
        return r;
    }

    private CancelRequestFixture() {}
}
