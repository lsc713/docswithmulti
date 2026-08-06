package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.CancelApproval;

public record CancelApprovalResponse(
    long id,
    String paymentKey,
    String status,
    Long cancelRequestId,
    String reason,
    String decisionReason,
    long requesterUserId,
    java.time.Instant createdAt
) {
    public static CancelApprovalResponse of(CancelApproval a) {
        return new CancelApprovalResponse(
            a.getId(), a.getPaymentKey(), a.getStatus().name(),
            a.getCancelRequestId(), a.getReason(), a.getDecisionReason(),
            a.getRequesterUserId(), a.getCreatedAt());
    }
}
