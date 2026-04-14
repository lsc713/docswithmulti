package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;

import java.util.Optional;

/**
 * CancelRequest 영속성 인터페이스
 *
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
}
