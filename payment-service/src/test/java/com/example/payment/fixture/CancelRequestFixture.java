package com.example.payment.fixture;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancellerType;

import java.math.BigDecimal;

/**
 * CancelRequest 도메인 엔티티 테스트 픽스처
 *
 * 테스트에서 공통으로 사용되는 CancelRequest 객체를 생성한다.
 * CancelRequest의 정적 팩토리 메서드를 활용한다.
 */
public class CancelRequestFixture {

    /**
     * 기본 취소 요청 (PENDING 상태)
     * - paymentId: 1
     * - cancelAmount: 50,000원
     */
    public static CancelRequest pendingCancelRequest() {
        return CancelRequest.create(
            1L,
            "idem_key_001",
            BigDecimal.valueOf(50000),
            "고객 단순 변심",
            CancellerType.USER,
            1L
        );
    }

    /**
     * 커스텀 취소 요청
     */
    public static CancelRequest cancelRequest(
        Long paymentId,
        BigDecimal cancelAmount,
        String idempotencyKey
    ) {
        return CancelRequest.create(
            paymentId,
            idempotencyKey,
            cancelAmount,
            "reason",
            CancellerType.USER,
            1L
        );
    }

    private CancelRequestFixture() {
    }
}
