package com.example.product.presentation.dto;

import com.example.product.application.usecase.InspectCancelRestoreUseCase;

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

    public record EvidenceResponse(
        long skuId,
        String currentStatus,
        Integer actualQuantity,
        int expectedQuantity
    ) {
        private static EvidenceResponse from(InspectCancelRestoreUseCase.Evidence evidence) {
            return new EvidenceResponse(
                evidence.skuId(), evidence.currentStatus(),
                evidence.actualQuantity(), evidence.expectedQuantity());
        }
    }
}
