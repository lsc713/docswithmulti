package com.example.payment.application.usecase;

import com.example.payment.application.dto.CancelPaymentRequest;
import com.example.payment.application.dto.CancelPaymentResponse;

/**
 * 취소 유스케이스 인터페이스
 *
 * 책임:
 * - Idempotency-Key 중복 체크
 * - Payment & PaymentItem 조회
 * - CancelDomainService 오케스트레이션
 * - RiskManagementService 호출 (한도 검증 + 선차감)
 * - CancelRequest 상태 전이
 * - CancelEventOutbox 생성
 * - DB 저장
 *
 * 트랜잭션 경계: 전체 프로세스는 하나의 트랜잭션 내에서 수행
 * (PROCESSING → COMPLETED까지 포함)
 */
public interface CancelPaymentUseCase {

    /**
     * 결제를 취소한다.
     *
     * @param paymentKey Payment.paymentKey
     * @param userId 취소 요청자 ID
     * @param idempotencyKey 멱등성 키 (24시간 유효)
     * @param request 취소 요청 정보
     * @return 취소 결과
     * @throws com.example.payment.application.exception.PaymentNotFoundException 결제 없음
     * @throws com.example.payment.application.exception.IdempotentDuplicationException 멱등 중복
     * @throws com.example.payment.domain.exception.InvalidPaymentStatusException 결제 상태 불가
     * @throws com.example.payment.domain.exception.InvalidPaymentItemStatusException 항목 상태 불가
     * @throws com.example.payment.domain.exception.CancelAmountExceededException 취소액 초과
     * @throws com.example.payment.domain.exception.CancelPeriodExceededException 기간 초과
     * @throws com.example.payment.application.exception.MerchantCancelLimitExceededException 한도 초과
     * @throws com.example.payment.infrastructure.exception.RiskServiceException 위험관리 서비스 장애
     */
    CancelPaymentResponse cancel(
        String paymentKey,
        Long userId,
        String idempotencyKey,
        CancelPaymentRequest request
    );
}
