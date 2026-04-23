package com.example.payment.domain.entity;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.exception.InvalidCancelAmountException;
import com.example.payment.domain.exception.InvalidCancelStateTransitionException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelRequest 도메인 엔티티")
class CancelRequestTest {

    private CancelRequest cancelRequest;

    @BeforeEach
    void setUp() {
        cancelRequest = CancelRequest.create(1L, "hash-abc123", new BigDecimal("100000"), "고객 변심");
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
            () -> CancelRequest.create(1L, "hash-xyz", BigDecimal.ZERO, "변심"));
        assertEquals(ErrorCode.INVALID_CANCEL_AMOUNT, ex.getErrorCode());
    }

    @Test
    @DisplayName("should_transition_pending_to_processing")
    void shouldTransitionPendingToProcessing() {
        cancelRequest.toProcessing();
        assertEquals(CancelStatus.PROCESSING, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getProcessingStartedAt());
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
        cancelRequest.toFailed("DB 타임아웃");
        assertEquals(CancelStatus.FAILED, cancelRequest.getStatus());
        assertEquals("DB 타임아웃", cancelRequest.getFailedReason());
    }

    @Test
    @DisplayName("should_allow_failed_to_raise_to_pending_for_retry")
    void shouldAllowFailedToRaiseToPendingForRetry() {
        cancelRequest.toProcessing();
        cancelRequest.toFailed("일시 오류");
        cancelRequest.raiseToPending();
        assertEquals(CancelStatus.PENDING, cancelRequest.getStatus());
        assertNull(cancelRequest.getFailedReason());
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
        assertThrows(InvalidCancelStateTransitionException.class, () -> cancelRequest.toFailed("x"));
    }

    @Test
    @DisplayName("should_reject_transition_from_failed_to_processing")
    void shouldRejectTransitionFromFailedToProcessing() {
        cancelRequest.toProcessing();
        cancelRequest.toFailed("processing failed");

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
}
