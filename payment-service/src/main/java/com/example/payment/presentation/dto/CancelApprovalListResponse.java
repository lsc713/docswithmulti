package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.CancelApproval;

import java.util.List;

public record CancelApprovalListResponse(List<CancelApprovalResponse> items) {
    public static CancelApprovalListResponse of(List<CancelApproval> approvals) {
        return new CancelApprovalListResponse(approvals.stream().map(CancelApprovalResponse::of).toList());
    }
}
