package com.example.payment.domain.entity;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.exception.InvalidCancelAmountException;
import com.example.payment.domain.exception.InvalidCancelStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelRequest 도메인 엔티티")
class CancelRequestTest {

    @Test
    @DisplayName("should_create_cancel_request_with_valid_parameters")
    void shouldCreateCancelRequestWithValidParameters() {
        // given
        Long paymentId = 1L;
        String idempotencyKey = "idem-key-123";
        BigDecimal cancelAmount = new BigDecimal("100000");
        String cancelReason = "고객 변심";
        CancellerType cancellerType = CancellerType.USER;
        Long cancelledBy = 10L;

        // when
        CancelRequest cancelRequest = CancelRequest.create(
            paymentId,
            idempotencyKey,
            cancelAmount,
            cancelReason,
            cancellerType,
            cancelledBy
        );

        // then
        assertEquals(paymentId, cancelRequest.getPaymentId());
        assertEquals(idempotencyKey, cancelRequest.getIdempotencyKey());
        assertEquals(cancelAmount, cancelRequest.getCancelAmount());
        assertEquals(cancelReason, cancelRequest.getCancelReason());
        assertEquals(cancellerType, cancelRequest.getCancellerType());
        assertEquals(cancelledBy, cancelRequest.getCancelledBy());
        assertEquals(CancelStatus.PENDING, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getCreatedAt());
    }

    @Test
    @DisplayName("should_reject_zero_cancel_amount")
    void shouldRejectZeroCancelAmount() {
        // given & when & then
        InvalidCancelAmountException ex = assertThrows(InvalidCancelAmountException.class, () ->
            CancelRequest.create(
                1L, "idem-123", BigDecimal.ZERO,
                "고객 변심", CancellerType.USER, 10L
            )
        );
        assertEquals(ErrorCode.INVALID_CANCEL_AMOUNT, ex.getErrorCode());
    }

    @Test
    @DisplayName("should_reject_negative_cancel_amount")
    void shouldRejectNegativeCancelAmount() {
        // given & when & then
        BigDecimal negativeAmount = new BigDecimal("-100000");

        InvalidCancelAmountException ex = assertThrows(InvalidCancelAmountException.class, () ->
            CancelRequest.create(
                1L, "idem-123", negativeAmount,
                "고객 변심", CancellerType.USER, 10L
            )
        );
        assertEquals(ErrorCode.INVALID_CANCEL_AMOUNT, ex.getErrorCode());
    }

    @Test
    @DisplayName("should_transition_from_pending_to_processing")
    void shouldTransitionFromPendingToProcessing() {
        // given
        CancelRequest cancelRequest = CancelRequest.create(
            1L, "idem-123", new BigDecimal("100000"),
            "고객 변심", CancellerType.USER, 10L
        );
        assertEquals(CancelStatus.PENDING, cancelRequest.getStatus());

        // when
        cancelRequest.toProcessing();

        // then
        assertEquals(CancelStatus.PROCESSING, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getProcessingStartedAt());
    }

    @Test
    @DisplayName("should_transition_from_processing_to_completed")
    void shouldTransitionFromProcessingToCompleted() {
        // given
        CancelRequest cancelRequest = CancelRequest.create(
            1L, "idem-123", new BigDecimal("100000"),
            "고객 변심", CancellerType.USER, 10L
        );
        cancelRequest.toProcessing();

        // when
        cancelRequest.toCompleted();

        // then
        assertEquals(CancelStatus.COMPLETED, cancelRequest.getStatus());
        assertNotNull(cancelRequest.getCompletedAt());
    }

    @Test
    @DisplayName("should_transition_from_processing_to_failed")
    void shouldTransitionFromProcessingToFailed() {
        // given
        CancelRequest cancelRequest = CancelRequest.create(
            1L, "idem-123", new BigDecimal("100000"),
            "고객 변심", CancellerType.USER, 10L
        );
        cancelRequest.toProcessing();
        String failureReason = "DB 타임아웃";

        // when
        cancelRequest.toFailed(failureReason);

        // then
        assertEquals(CancelStatus.FAILED, cancelRequest.getStatus());
        assertEquals(failureReason, cancelRequest.getFailedReason());
        assertNull(cancelRequest.getCompletedAt());
    }

    @Test
    @DisplayName("should_reject_transition_from_completed_to_other_status")
    void shouldRejectTransitionFromCompletedToOtherStatus() {
        // given
        CancelRequest cancelRequest = CancelRequest.create(
            1L, "idem-123", new BigDecimal("100000"),
            "고객 변심", CancellerType.USER, 10L
        );
        cancelRequest.toProcessing();
        cancelRequest.toCompleted();

        // when & then
        InvalidCancelStateTransitionException ex1 = assertThrows(
            InvalidCancelStateTransitionException.class,
            cancelRequest::toProcessing
        );
        assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, ex1.getErrorCode());

        InvalidCancelStateTransitionException ex2 = assertThrows(
            InvalidCancelStateTransitionException.class,
            () -> cancelRequest.toFailed("reason")
        );
        assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, ex2.getErrorCode());
    }

    @Test
    @DisplayName("should_reject_transition_from_failed_to_other_status")
    void shouldRejectTransitionFromFailedToOtherStatus() {
        // given
        CancelRequest cancelRequest = CancelRequest.create(
            1L, "idem-123", new BigDecimal("100000"),
            "고객 변심", CancellerType.USER, 10L
        );
        cancelRequest.toProcessing();
        cancelRequest.toFailed("processing failed");

        // when & then
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
    @DisplayName("should_reject_transition_to_processing_from_non_pending_status")
    void shouldRejectTransitionToProcessingFromNonPendingStatus() {
        // given
        CancelRequest cancelRequest = CancelRequest.create(
            1L, "idem-123", new BigDecimal("100000"),
            "고객 변심", CancellerType.USER, 10L
        );
        cancelRequest.toProcessing();
        cancelRequest.toCompleted();

        // when & then
        InvalidCancelStateTransitionException ex = assertThrows(
            InvalidCancelStateTransitionException.class,
            cancelRequest::toProcessing
        );
        assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, ex.getErrorCode());
    }

    @Test
    @DisplayName("should_reject_transition_to_completed_from_non_processing_status")
    void shouldRejectTransitionToCompletedFromNonProcessingStatus() {
        // given
        CancelRequest cancelRequest = CancelRequest.create(
            1L, "idem-123", new BigDecimal("100000"),
            "고객 변심", CancellerType.USER, 10L
        );

        // when & then
        InvalidCancelStateTransitionException ex = assertThrows(
            InvalidCancelStateTransitionException.class,
            cancelRequest::toCompleted
        );
        assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, ex.getErrorCode());
    }
}
