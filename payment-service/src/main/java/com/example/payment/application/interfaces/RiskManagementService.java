package com.example.payment.application.interfaces;

import java.math.BigDecimal;

/**
 * 위험관리 서비스 외부 인터페이스
 *
 * 동기 HTTP 호출로 취소 가능 여부를 검증하고
 * 가맹점 일일 취소한도에서 선차감한다.
 *
 * architecture.md:
 * - 모듈 간 통신 전략: 동기 HTTP
 * - 한도 검증 실패 시 즉시 취소 플로우 중단
 * - Circuit Breaker로 장애 전파 방지
 *
 * domain-rules.md 3: 가맹점 취소한도 정책
 * - 선차감 방식: 검증 통과 → used_amount 선차감
 * - 취소 FAILED → 보상 트랜잭션으로 복구
 *
 * infrastructure/http에서 HTTP 클라이언트로 구현
 */
public interface RiskManagementService {

    /**
     * 취소 가능 여부를 검증하고 한도를 선차감한다.
     *
     * @param merchantId 가맹점 ID
     * @param paymentId 결제 ID
     * @param cancelAmount 취소 금액
     * @return 성공 시 처리 ID (보상 시 사용)
     * @throws com.example.payment.application.exception.MerchantCancelLimitNotFoundException
     *         가맹점 한도 미설정
     * @throws com.example.payment.application.exception.MerchantCancelLimitExceededException
     *         일일 한도 초과
     * @throws com.example.payment.infrastructure.exception.RiskServiceException
     *         서비스 장애 (Circuit Breaker)
     */
    Long validateAndReserveLimit(
        Long merchantId,
        Long paymentId,
        BigDecimal cancelAmount
    );
}
