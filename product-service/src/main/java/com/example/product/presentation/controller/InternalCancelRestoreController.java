package com.example.product.presentation.controller;

import com.example.product.application.usecase.InspectCancelRestoreUseCase;
import com.example.product.presentation.dto.InspectCancelRestoreRequest;
import com.example.product.presentation.dto.InspectCancelRestoreResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalCancelRestoreController {

    private final InspectCancelRestoreUseCase useCase;

    public InternalCancelRestoreController(InspectCancelRestoreUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/internal/cancel-restores/{cancelRequestId}:inspect")
    public InspectCancelRestoreResponse inspect(
        @PathVariable String cancelRequestId,
        @Valid @RequestBody InspectCancelRestoreRequest request
    ) {
        var items = request.items().stream()
            .map(item -> new InspectCancelRestoreUseCase.Item(item.skuId(), item.quantity()))
            .toList();
        var result = useCase.inspect(new InspectCancelRestoreUseCase.Command(
            cancelRequestId, request.paymentKey(), items));
        return InspectCancelRestoreResponse.from(result);
    }
}
