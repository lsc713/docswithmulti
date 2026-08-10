package com.example.product.application.usecase;

import com.example.product.application.model.CancelRestoreLegStatus;

import java.util.List;

public interface InspectCancelRestoreUseCase {

    Result inspect(Command command);

    record Command(String cancelRequestId, String paymentKey, List<Item> items) {}

    record Item(long skuId, int quantity) {}

    record Evidence(
        long skuId,
        String currentStatus,
        Integer actualQuantity,
        int expectedQuantity
    ) {}

    record Result(CancelRestoreLegStatus status, List<Evidence> evidence) {
        public Result {
            evidence = List.copyOf(evidence);
        }
    }
}
