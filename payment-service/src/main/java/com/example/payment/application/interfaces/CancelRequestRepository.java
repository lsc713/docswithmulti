package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * CancelRequest 영속성 인터페이스
 * infrastructure/persistence에서 JPA로 구현
 */
public interface CancelRequestRepository {

    /**
     * CancelRequest 저장
     */
    CancelRequest save(CancelRequest cancelRequest);

    /**
     * cancelRequestId로 조회
     */
    Optional<CancelRequest> findById(Long id);

    /**
     * paymentId로 해당 결제의 모든 취소 요청 조회
     */
    List<CancelRequest> findAllByPaymentId(Long paymentId);

    /**
     * paymentId로 COMPLETED 상태인 취소 요청만 조회
     */
    List<CancelRequest> findCompletedByPaymentId(Long paymentId);

    /**
     * 지정된 시간 이전에 PROCESSING 상태로 스택된 취소 요청 조회
     * (서버 재시작 후 5분 초과 처리 중 건 찾기)
     */
    List<CancelRequest> findStuckProcessingRequests(LocalDateTime before);
}
