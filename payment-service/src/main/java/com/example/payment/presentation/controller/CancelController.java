package com.example.payment.presentation.controller;

import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.common.exception.application.PaymentNotFoundException;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.example.payment.infrastructure.security.AuthenticatedUser;
import com.example.payment.presentation.dto.CancelPaymentRequest;
import com.example.payment.presentation.dto.CancelPaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class CancelController {

    private final CancelPaymentUseCase cancelPaymentUseCase;
    private final PaymentRepository paymentRepository;

    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancel(
        @PathVariable String paymentKey,
        @RequestBody @Valid CancelPaymentRequest request,
        Authentication authentication
    ) {
        AuthenticatedUser authenticatedUser = (AuthenticatedUser) authentication.getPrincipal();

        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
            .orElseThrow(() -> new PaymentNotFoundException(paymentKey));

        authenticatedUser.validateCancelAuthorization(payment);

        List<Long> itemIds = request.cancelItems().stream()
            .map(item -> item.paymentItemId())
            .toList();

        CancelPaymentCommand command = new CancelPaymentCommand(
            paymentKey, request.cancelReason(), itemIds);

        CancelRequest cancelRequest = cancelPaymentUseCase.cancel(command);

        return ResponseEntity.ok(
            CancelPaymentResponse.of(cancelRequest, paymentKey, List.of())
        );
    }
}
