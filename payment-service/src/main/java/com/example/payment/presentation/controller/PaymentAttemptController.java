package com.example.payment.presentation.controller;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.application.usecase.PaymentAttemptUseCase;
import com.example.payment.application.usecase.PaymentAttemptUseCase.Prepared;
import com.example.payment.application.usecase.PaymentAttemptUseCase.Status;
import com.example.payment.presentation.dto.CreatePaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/v1/payment-attempts")
@RequiredArgsConstructor
public class PaymentAttemptController {
    private final PaymentAttemptUseCase useCase;

    @PostMapping
    public ResponseEntity<Prepared> prepare(
        @RequestHeader("X-User-Id") long userId,
        @RequestBody @Valid CreatePaymentRequest request
    ) {
        return ResponseEntity.ok(useCase.prepare(new CreatePaymentCommand(
            request.merchantId(), userId, request.pgType(), request.cancelPeriodDays(),
            request.items().stream().map(item -> new CreatePaymentCommand.Item(
                item.orderItemId(), item.productId(), item.itemName(), BigDecimal.ZERO,
                item.skuId(), item.quantity())).toList())));
    }

    @PostMapping("/{paymentRequestId}/confirm")
    public ResponseEntity<Status> confirm(
        @RequestHeader("X-User-Id") long userId,
        @PathVariable String paymentRequestId,
        @RequestBody @Valid ConfirmRequest request
    ) {
        return ResponseEntity.ok(useCase.confirm(
            paymentRequestId, userId, request.paymentKey(), request.orderId(), request.amount()));
    }

    @PostMapping("/{paymentRequestId}/fail")
    public ResponseEntity<Status> fail(
        @RequestHeader("X-User-Id") long userId,
        @PathVariable String paymentRequestId
    ) {
        return ResponseEntity.ok(useCase.fail(paymentRequestId, userId));
    }

    @GetMapping("/{paymentRequestId}")
    public ResponseEntity<Status> get(
        @RequestHeader("X-User-Id") long userId,
        @PathVariable String paymentRequestId
    ) {
        return ResponseEntity.ok(useCase.get(paymentRequestId, userId));
    }

    public record ConfirmRequest(
        @NotBlank String paymentKey,
        @NotBlank String orderId,
        @NotNull @Positive BigDecimal amount
    ) {}
}
