package com.example.payment.fixture;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class CancelRequestFixture {

    /**
     * id 없는 PENDING — persistence 테스트용 (JPA INSERT).
     */
    public static CancelRequest pending(Long paymentId, BigDecimal cancelAmount) {
        return CancelRequest.create(paymentId, "hash_" + paymentId, cancelAmount, "고객 변심",
            List.of(paymentId * 10, paymentId * 10 + 1), null);
    }

    /**
     * id 있는 PENDING — unit 테스트용 (DB 없이 mock).
     * id = paymentId (테스트 전용 synthetic id)
     */
    public static CancelRequest pendingWithId(Long paymentId, BigDecimal cancelAmount) {
        return CancelRequest.reconstruct(
            paymentId,          // id (synthetic: paymentId as id for test)
            paymentId,          // paymentId
            "hash_" + paymentId,
            cancelAmount,
            "고객 변심",
            List.of(paymentId * 10, paymentId * 10 + 1),
            CancelStatus.PENDING,
            0,
            null,
            null,
            Instant.now(),
            null,
            null, null);
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
