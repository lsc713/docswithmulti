package com.example.payment.presentation.controller;

import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.presentation.dto.CancelPaymentRequest;
import com.example.payment.presentation.dto.CancelPaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class CancelController {

    private final CancelPaymentUseCase cancelPaymentUseCase;

    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancel(
        @PathVariable String paymentKey,
        @RequestBody @Valid CancelPaymentRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        List<Long> itemIds = request.cancelItems().stream()
            .map(item -> item.paymentItemId())
            .toList();

        CancelPaymentCommand command = new CancelPaymentCommand(
            paymentKey, request.cancelReason(), itemIds, idempotencyKey);

        CancelRequest cancelRequest = cancelPaymentUseCase.cancel(command);

        return ResponseEntity.ok(
            CancelPaymentResponse.of(cancelRequest, paymentKey, List.of())
        );
    }
}
