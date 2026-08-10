package com.example.payment.presentation.dto;

import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;

import java.util.List;

public record CancelOutboxInspectionResponse(
    long outboxId,
    long cancelRequestId,
    String decision,
    String reasonCode,
    LegResponse order,
    LegResponse stock
) {
    public static CancelOutboxInspectionResponse from(
        CancelOutboxInspectionUseCase.Result result
    ) {
        return new CancelOutboxInspectionResponse(
            result.outboxId(),
            result.cancelRequestId(),
            result.decision().name(),
            result.reasonCode() == null ? null : result.reasonCode().name(),
            LegResponse.from(result.order()),
            LegResponse.from(result.stock()));
    }

    public record LegResponse(String status, List<EvidenceResponse> evidence) {
        private static LegResponse from(CancelRestoreLegSnapshot snapshot) {
            if (snapshot == null) {
                return null;
            }
            return new LegResponse(
                snapshot.status().name(),
                snapshot.evidence().stream().map(EvidenceResponse::from).toList());
        }

        public LegResponse {
            evidence = List.copyOf(evidence);
        }
    }

    public record EvidenceResponse(
        long targetId,
        String currentStatus,
        Integer actualQuantity,
        Integer expectedQuantity
    ) {
        private static EvidenceResponse from(CancelRestoreLegSnapshot.Evidence evidence) {
            return new EvidenceResponse(
                evidence.targetId(),
                evidence.currentStatus(),
                evidence.actualQuantity(),
                evidence.expectedQuantity());
        }
    }
}
