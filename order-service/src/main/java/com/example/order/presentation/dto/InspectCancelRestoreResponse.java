package com.example.order.presentation.dto;

import com.example.order.application.usecase.InspectCancelRestoreUseCase;

import java.util.List;

public record InspectCancelRestoreResponse(
    String status,
    List<EvidenceResponse> evidence
) {
    public static InspectCancelRestoreResponse from(InspectCancelRestoreUseCase.Result result) {
        return new InspectCancelRestoreResponse(
            result.status().name(),
            result.evidence().stream().map(EvidenceResponse::from).toList());
    }

    public record EvidenceResponse(long targetId, String currentStatus) {
        private static EvidenceResponse from(InspectCancelRestoreUseCase.Evidence evidence) {
            return new EvidenceResponse(evidence.targetId(), evidence.currentStatus());
        }
    }
}
