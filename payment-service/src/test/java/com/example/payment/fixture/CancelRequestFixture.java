package com.example.payment.fixture;

import com.example.payment.domain.entity.CancelRequest;
import java.math.BigDecimal;
import java.util.List;

public class CancelRequestFixture {

    public static CancelRequest pending(Long paymentId, BigDecimal cancelAmount) {
        return CancelRequest.create(paymentId, "hash_" + paymentId, cancelAmount, "고객 변심",
            List.of(paymentId * 10, paymentId * 10 + 1));
    }

    public static CancelRequest completed(Long paymentId, BigDecimal cancelAmount) {
        CancelRequest r = pending(paymentId, cancelAmount);
        r.toProcessing();
        r.toCompleted();
        return r;
    }

    public static CancelRequest failed(Long paymentId, BigDecimal cancelAmount) {
        CancelRequest r = pending(paymentId, cancelAmount);
        r.toFailed();
        return r;
    }

    private CancelRequestFixture() {}
}
