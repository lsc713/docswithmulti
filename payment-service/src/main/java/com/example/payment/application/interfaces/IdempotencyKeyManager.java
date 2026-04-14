package com.example.payment.application.interfaces;

import java.util.Optional;

/**
 * Idempotency-Key 관리 인터페이스
 *
 * domain-rules.md 5-1: Idempotency-Key 유효 기간은 24시간
 * architecture.md: UK 제약으로 동시 요청 시에도 하나만 INSERT 성공
 *
 * infrastructure/persistence에서 DB 기반 구현
 */
public interface IdempotencyKeyManager {

    /**
     * Idempotency-Key로 기존 CancelRequest 조회
     *
     * @return 기존 취소 요청이 있으면 cancelRequestId, 없으면 empty
     */
    Optional<Long> getCancelRequestId(String idempotencyKey);

    /**
     * Idempotency-Key 기록
     *
     * @throws com.example.payment.application.exception.IdempotentDuplicationException
     *         동시성으로 인해 동일 키가 이미 기록된 경우
     */
    void recordIdempotencyKey(String idempotencyKey, Long cancelRequestId);
}
