package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.PaymentItem;

import java.util.List;

/**
 * PaymentItem 영속성 인터페이스
 *
 * infrastructure/persistence에서 JPA로 구현
 */
public interface PaymentItemRepository {

    /**
     * Payment에 속한 모든 PaymentItem 조회
     * (상태 전이 판단을 위해 전체 조회 필요)
     */
    List<PaymentItem> findAllByPaymentId(Long paymentId);

    /**
     * PaymentItem 저장 또는 업데이트
     */
    PaymentItem save(PaymentItem item);

    /**
     * 여러 PaymentItem 저장
     */
    void saveAll(List<PaymentItem> items);
}
