package com.example.payment.presentation.controller;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.application.usecase.CreatePaymentUseCase;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.presentation.dto.CreatePaymentRequest;
import com.example.payment.presentation.dto.CreatePaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final CreatePaymentUseCase createPaymentUseCase;

    @PostMapping
    public ResponseEntity<CreatePaymentResponse> create(
        @RequestBody @Valid CreatePaymentRequest request
    ) {
        CreatePaymentCommand command = new CreatePaymentCommand(
            request.merchantId(),
            request.userId(),
            request.pgType(),
            request.cancelPeriodDays(),
            request.items().stream()
                .map(item -> new CreatePaymentCommand.Item(
                    item.orderItemId(),
                    item.productId(),
                    item.itemName(),
                    item.itemAmount(),
                    item.skuId(),
                    item.quantity()
                ))
                .toList()
        );

        Result result = createPaymentUseCase.create(command);
        return ResponseEntity.ok(CreatePaymentResponse.from(result));
    }
}
