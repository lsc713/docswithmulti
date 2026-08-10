package com.example.order.presentation.controller;

import com.example.order.application.usecase.InspectCancelRestoreUseCase;
import com.example.order.presentation.dto.InspectCancelRestoreRequest;
import com.example.order.presentation.dto.InspectCancelRestoreResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InternalCancelRestoreController {

    private final InspectCancelRestoreUseCase useCase;

    @PostMapping("/internal/cancel-restores/{cancelRequestId}:inspect")
    public InspectCancelRestoreResponse inspect(
        @PathVariable String cancelRequestId,
        @Valid @RequestBody InspectCancelRestoreRequest request
    ) {
        var result = useCase.inspect(new InspectCancelRestoreUseCase.Command(
            cancelRequestId, request.orderItemIds()));
        return InspectCancelRestoreResponse.from(result);
    }
}
