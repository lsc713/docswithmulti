package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.PaymentItem;
import java.util.List;

public interface PaymentItemRepository {

    List<PaymentItem> findAllByPaymentIdOrderByIdAsc(long paymentId);

    /** TX3 내부에서 최신 상태 재조회 + 비관적 락 */
    List<PaymentItem> findAllByPaymentIdForUpdate(long paymentId);

    List<PaymentItem> saveAll(List<PaymentItem> items);
}
