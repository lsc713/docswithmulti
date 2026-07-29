package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CancelRequestRepository {

    Optional<CancelRequest> findByPaymentIdAndRequestHash(long paymentId, String requestHash);

    // D-idem: id(PK)로 재조회. request_hash/dedup_key 둘 다 payment당 유일하지 않을 수 있어
    // 원자 UPDATE 직후 같은 행을 다시 읽을 때는 이 유일키 조회를 쓴다(ProcessingRecoveryService).
    Optional<CancelRequest> findById(long id);

    // D-idem: dedup_key(V15, "ik:"+idempotencyKey 또는 "ch:"+requestHash)로 조회.
    // request_hash는 같은 items+다른 idempotencyKey에서 중복될 수 있어 더 이상 payment당 유일하지 않음.
    Optional<CancelRequest> findByPaymentIdAndDedupKey(long paymentId, String dedupKey);

    CancelRequest save(CancelRequest cancelRequest);

    /**
     * pending-recovery용: PENDING + createdAt < before
     * TX1 이후 상태 변경 없는 건 → createdAt 기준
     */
    List<CancelRequest> findPendingCreatedBefore(Instant before);

    /**
     * processing-recovery용: PROCESSING + updatedAt < before
     * TX2(PROCESSING UPDATE)가 updatedAt 기준점 → updatedAt 기준
     */
    List<CancelRequest> findProcessingUpdatedBefore(Instant before);

    /**
     * pg_retry_count 원자 UPDATE(read-modify-write 경쟁 제거, D-04).
     * 호출 후 로컬 CancelRequest 객체는 stale — 임계값 비교 전 반드시 재조회할 것.
     * @return 1 = 성공, 0 = 대상 없음
     */
    int incrementPgRetryCount(long id);

    /**
     * PROCESSING → FAILED 조건부 원자 UPDATE(D-04 패턴 재사용, CR-03).
     * processing-recovery의 compensateAndFail에서 risk-management.compensate() 호출 전에
     * 먼저 시도 — 이 UPDATE로 전이에 성공한 스레드만 compensate를 진행해 이중 보상을 막는다
     * (레코드 단위 분산락 대신 상태 전이 자체를 멱등 가드로 사용 — D-04, 락 추가 금지).
     * @return 1 = 성공(내가 FAILED 전이를 완료), 0 = 이미 다른 스레드가 처리함(스킵 대상)
     */
    int compareAndSetFailed(long id);
}
