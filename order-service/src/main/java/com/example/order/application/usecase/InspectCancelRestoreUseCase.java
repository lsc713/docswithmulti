package com.example.order.application.usecase;

import com.example.order.application.model.CancelRestoreLegStatus;

import java.util.List;

public interface InspectCancelRestoreUseCase {

    Result inspect(Command command);

    record Command(String cancelRequestId, List<Long> orderItemIds) {}

    record Evidence(long targetId, String currentStatus) {}

    record Result(CancelRestoreLegStatus status, List<Evidence> evidence) {
        public Result {
            evidence = List.copyOf(evidence);
        }
    }
}
