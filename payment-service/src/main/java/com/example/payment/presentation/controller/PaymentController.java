package com.example.payment.presentation.controller;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.application.usecase.CreatePaymentUseCase;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.application.usecase.PaymentExistsQuery;
import com.example.payment.presentation.dto.CreatePaymentRequest;
import com.example.payment.presentation.dto.CreatePaymentResponse;
import com.example.payment.presentation.dto.PaymentExistsResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final CreatePaymentUseCase createPaymentUseCase;
    private final PaymentExistsQuery paymentExistsQuery;

    @PostMapping
    public ResponseEntity<CreatePaymentResponse> create(
        @RequestHeader("X-User-Id") long userId,
        @RequestBody @Valid CreatePaymentRequest request
    ) {
        CreatePaymentCommand command = new CreatePaymentCommand(
            request.merchantId(),
            userId,
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

    /**
     * paymentKey로 커밋된 payment 존재 여부 조회 (RST-03 orphan 복구).
     * 존재/미존재 모두 200 — 바디의 exists 로 판별.
     */
    @GetMapping("/{paymentKey}/exists")
    public ResponseEntity<PaymentExistsResponse> exists(@PathVariable String paymentKey) {
        return ResponseEntity.ok(new PaymentExistsResponse(paymentExistsQuery.exists(paymentKey)));
    }
}
