package com.example.payment.presentation.controller;

import com.example.payment.application.usecase.PaymentExistsQuery;
import com.example.payment.presentation.dto.PaymentExistsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentExistsQuery paymentExistsQuery;

    /**
     * paymentKey로 커밋된 payment 존재 여부 조회 (RST-03 orphan 복구).
     * 존재/미존재 모두 200 — 바디의 exists 로 판별.
     */
    @GetMapping("/{paymentKey}/exists")
    public ResponseEntity<PaymentExistsResponse> exists(@PathVariable String paymentKey) {
        return ResponseEntity.ok(new PaymentExistsResponse(paymentExistsQuery.exists(paymentKey)));
    }
}
