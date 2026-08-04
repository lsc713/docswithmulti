package com.example.payment.presentation.controller;

import com.example.payment.application.usecase.PaymentHistoryQuery;
import com.example.payment.presentation.dto.PaymentDetailResponse;
import com.example.payment.presentation.dto.PaymentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 주문내역 조회 (본인 결제만) — P3. */
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentHistoryController {

    private final PaymentHistoryQuery paymentHistoryQuery;

    @GetMapping
    public List<PaymentSummaryResponse> list(
        @RequestHeader("X-User-Id") long userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return paymentHistoryQuery.list(userId, safePage, safeSize);
    }

    @GetMapping("/{paymentKey}")
    public PaymentDetailResponse detail(
        @RequestHeader("X-User-Id") long userId,
        @PathVariable String paymentKey) {
        return paymentHistoryQuery.detail(userId, paymentKey);
    }
}
