package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import java.util.List;

public interface CancelEventOutboxRepository {
    /** TX3 내부에서 호출. cancel_request_id UK로 중복 방어. */
    void insertIfAbsent(CancelRequest cancelRequest, Payment payment, List<PaymentItem> cancelledItems);
}
