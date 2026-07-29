package com.example.payment.presentation.controller;

import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.presentation.dto.CancelPaymentRequest;
import com.example.payment.presentation.dto.CancelPaymentResponse;
import com.example.payment.presentation.exception.InvalidIdempotencyKeyException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class CancelController {

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;

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

        // spec §8: null/blank Idempotency-Key는 미전송으로 취급 (content-hash fallback)
        String idem = StringUtils.hasText(idempotencyKey) ? idempotencyKey : null;
        if (idem != null && idem.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }

        CancelPaymentCommand command = new CancelPaymentCommand(
            paymentKey, request.cancelReason(), itemIds, idem);

        CancelRequest cancelRequest = cancelPaymentUseCase.cancel(command);

        return ResponseEntity.ok(
            CancelPaymentResponse.of(cancelRequest, paymentKey, List.of())
        );
    }
}
