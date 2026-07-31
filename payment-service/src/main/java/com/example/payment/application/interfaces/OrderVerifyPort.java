package com.example.payment.application.interfaces;

import java.util.List;

/**
 * order-service 상류 주문 검증 포트 (PLINK-01/02/03).
 *
 * 결제 생성 흐름 최전방(재고 예약·persist보다 먼저, TX 밖)에서 호출한다.
 * fail-closed: order-service 장애/타임아웃/비200 → 예외 전파(결제 거부).
 * 요청자(X-User-Id)를 그대로 order-service에 포워딩해 소유 검증을 위임한다.
 */
public interface OrderVerifyPort {

    /**
     * orderItemIds 전부 존재 + 동일 order 소속 + 소유(order.user_id == userId) 검증.
     *
     * @param userId       요청자(X-User-Id, payment가 포워딩)
     * @param orderItemIds 결제 대상 orderItemId 목록
     * @return 검증된 단일 orderId
     */
    long verify(long userId, List<Long> orderItemIds);
}
