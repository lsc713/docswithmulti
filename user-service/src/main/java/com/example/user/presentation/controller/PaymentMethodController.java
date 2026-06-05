package com.example.user.presentation.controller;

import com.example.user.application.usecase.PaymentMethodUseCase;
import com.example.user.application.usecase.PaymentMethodUseCase.CreateCommand;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/v1/users/me/payment-methods")
@RequiredArgsConstructor
public class PaymentMethodController {
    private final PaymentMethodUseCase paymentMethodUseCase;

    @GetMapping
    public ResponseEntity<List<PaymentMethodResponse>> getPaymentMethods(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(paymentMethodUseCase.getPaymentMethods(userId).stream()
                .map(PaymentMethodResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<PaymentMethodResponse> create(Authentication authentication,
                                                         @RequestBody @Valid PaymentMethodRequest request) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(PaymentMethodResponse.from(paymentMethodUseCase.create(userId, new CreateCommand(
                request.type(), request.cardNumber(), request.cardCompany(),
                request.bankName(), request.accountNumber(), request.isDefault()))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable long id) {
        long userId = (long) authentication.getPrincipal();
        paymentMethodUseCase.delete(userId, id);
        return ResponseEntity.ok().build();
    }
}
