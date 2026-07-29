package com.example.payment.domain.entity;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.exception.InvalidCancelAmountException;
import com.example.payment.domain.exception.InvalidCancelStateTransitionException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelRequest 도메인 엔티티")
class CancelRequestTest {

    private CancelRequest cancelRequest;

    @BeforeEach
    void setUp() {
        cancelRequest = CancelRequest.create(1L, "hash-abc123", new BigDecimal("100000"), "고객 변심", List.of(1L, 2L), null);
    }

    @Test
    @DisplayName("should_create_with_pending_status_and_request_hash")
    void shouldCreateWithPendingStatusAndRequestHash() {
        assertEquals(1L, cancelRequest.getPaymentId());
        assertEquals("hash-abc123", cancelRequest.getRequestHash());
        assertEquals(new BigDecimal("100000"), cancelRequest.getCancelAmount());
        assertEquals(CancelStatus.PENDING, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getCreatedAt());
    }

    @Test
    @DisplayName("should_reject_zero_cancel_amount")
    void shouldRejectZeroCancelAmount() {
        InvalidCancelAmountException ex = assertThrows(InvalidCancelAmountException.class,
            () -> CancelRequest.create(1L, "hash-xyz", BigDecimal.ZERO, "변심", List.of(1L, 2L), null));
        assertEquals(ErrorCode.INVALID_CANCEL_AMOUNT, ex.getErrorCode());
    }

    @Test
    @DisplayName("should_transition_pending_to_processing")
    void shouldTransitionPendingToProcessing() {
        cancelRequest.toProcessing();
        assertEquals(CancelStatus.PROCESSING, cancelRequest.getStatus());
    }

    @Test
    @DisplayName("should_transition_processing_to_completed")
    void shouldTransitionProcessingToCompleted() {
        cancelRequest.toProcessing();
        cancelRequest.toCompleted();
        assertEquals(CancelStatus.COMPLETED, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getCompletedAt());
    }

    @Test
    @DisplayName("should_transition_processing_to_failed")
    void shouldTransitionProcessingToFailed() {
        cancelRequest.toProcessing();
        cancelRequest.toFailed();
        assertEquals(CancelStatus.FAILED, cancelRequest.getStatus());
    }

    @Test
    @DisplayName("should_allow_failed_to_raise_to_pending_for_retry")
    void shouldAllowFailedToRaiseToPendingForRetry() {
        cancelRequest.toProcessing();
        cancelRequest.toFailed();
        cancelRequest.raiseToPending();
        assertEquals(CancelStatus.PENDING, cancelRequest.getStatus());
    }

    @Test
    @DisplayName("should_reject_raise_to_pending_from_non_failed_status")
    void shouldRejectRaiseToPendingFromNonFailedStatus() {
        assertThrows(InvalidCancelStateTransitionException.class,
            cancelRequest::raiseToPending);
    }

    @Test
    @DisplayName("should_reject_transition_from_completed")
    void shouldRejectTransitionFromCompleted() {
        cancelRequest.toProcessing();
        cancelRequest.toCompleted();
        assertThrows(InvalidCancelStateTransitionException.class, cancelRequest::toProcessing);
        assertThrows(InvalidCancelStateTransitionException.class, cancelRequest::toFailed);
    }

    @Test
    @DisplayName("should_reject_transition_from_failed_to_processing")
    void shouldRejectTransitionFromFailedToProcessing() {
        cancelRequest.toProcessing();
        cancelRequest.toFailed();

        InvalidCancelStateTransitionException ex1 = assertThrows(
            InvalidCancelStateTransitionException.class,
            cancelRequest::toProcessing
        );
        assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, ex1.getErrorCode());

        InvalidCancelStateTransitionException ex2 = assertThrows(
            InvalidCancelStateTransitionException.class,
            cancelRequest::toCompleted
        );
        assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, ex2.getErrorCode());
    }

    @Test
    @DisplayName("markPgPending_setsTimestampWhenNull")
    void markPgPending_setsTimestampWhenNull() {
        assertNull(cancelRequest.getPgPendingSince());
        cancelRequest.markPgPending();
        assertNotNull(cancelRequest.getPgPendingSince());
    }

    @Test
    @DisplayName("markPgPending_isIdempotent")
    void markPgPending_isIdempotent() {
        cancelRequest.markPgPending();
        Instant first = cancelRequest.getPgPendingSince();
        cancelRequest.markPgPending();
        assertEquals(first, cancelRequest.getPgPendingSince());
    }

    @Test
    @DisplayName("incrementPgRetryCount_incrementsFromZero")
    void incrementPgRetryCount_incrementsFromZero() {
        assertEquals(0, cancelRequest.getPgRetryCount());
        cancelRequest.incrementPgRetryCount();
        assertEquals(1, cancelRequest.getPgRetryCount());
    }

    @Test
    @DisplayName("raiseToPending_resetsPgRetryCount")
    void raiseToPending_resetsPgRetryCount() {
        cancelRequest.incrementPgRetryCount();
        cancelRequest.incrementPgRetryCount();
        cancelRequest.toFailed();
        cancelRequest.raiseToPending();
        assertEquals(0, cancelRequest.getPgRetryCount());
    }

    // ──────────────────────────────────────────────────────────
    // 생성자 검증 — null/empty 분기
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelAmount가 null이면 InvalidCancelAmountException을 던진다")
    void shouldRejectNullCancelAmount() {
        assertThrows(InvalidCancelAmountException.class,
            () -> CancelRequest.create(1L, "hash", null, "변심", List.of(1L), null));
    }

    @Test
    @DisplayName("cancelItemIds가 null이면 IllegalArgumentException을 던진다")
    void shouldRejectNullCancelItemIds() {
        assertThrows(IllegalArgumentException.class,
            () -> CancelRequest.create(1L, "hash", new BigDecimal("100000"), "변심", null, null));
    }

    @Test
    @DisplayName("cancelItemIds가 빈 리스트이면 IllegalArgumentException을 던진다")
    void shouldRejectEmptyCancelItemIds() {
        assertThrows(IllegalArgumentException.class,
            () -> CancelRequest.create(1L, "hash", new BigDecimal("100000"), "변심", List.of(), null));
    }

    // ──────────────────────────────────────────────────────────
    // 잘못된 상태 전이 — 추가 분기
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("PROCESSING 상태에서 toProcessing()을 호출하면 예외를 던진다")
    void shouldRejectToProcessingFromProcessing() {
        cancelRequest.toProcessing();
        assertThrows(InvalidCancelStateTransitionException.class, cancelRequest::toProcessing);
    }

    @Test
    @DisplayName("PENDING 상태에서 toCompleted()를 호출하면 예외를 던진다")
    void shouldRejectToCompletedFromPending() {
        assertThrows(InvalidCancelStateTransitionException.class, cancelRequest::toCompleted);
    }

    @Test
    @DisplayName("FAILED 상태에서 toFailed()를 호출하면 예외를 던진다")
    void shouldRejectToFailedFromFailed() {
        cancelRequest.toProcessing();
        cancelRequest.toFailed();
        assertThrows(InvalidCancelStateTransitionException.class, cancelRequest::toFailed);
    }

    // ──────────────────────────────────────────────────────────
    // D-01 정정: PG(Toss) transactionKey 저장 (감사 + 부분취소 동일금액 tiebreaker)
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("assignPgTransactionKey_setsValue")
    void assignPgTransactionKey_setsValue() {
        assertNull(cancelRequest.getPgTransactionKey());
        cancelRequest.assignPgTransactionKey("toss-tx-abc123");
        assertEquals("toss-tx-abc123", cancelRequest.getPgTransactionKey());
    }

    @Test
    @DisplayName("reconstruct는 저장된 pgTransactionKey를 복원한다")
    void reconstruct_restoresPgTransactionKey() {
        CancelRequest reconstructed = CancelRequest.reconstruct(
            1L, 1L, "hash-abc123", new BigDecimal("100000"), "고객 변심", List.of(1L, 2L),
            CancelStatus.COMPLETED, 0, Instant.now(), null, Instant.now(), Instant.now(),
            "toss-tx-abc123", null);

        assertEquals("toss-tx-abc123", reconstructed.getPgTransactionKey());
    }
}
