package com.example.payment.application.model;

import java.util.List;

public record CancelRestoreLegSnapshot(
    CancelRestoreLegStatus status,
    List<Evidence> evidence
) {
    public CancelRestoreLegSnapshot {
        evidence = List.copyOf(evidence);
    }

    public record Evidence(
        long targetId,
        String currentStatus,
        Integer actualQuantity,
        Integer expectedQuantity
    ) {}
}
