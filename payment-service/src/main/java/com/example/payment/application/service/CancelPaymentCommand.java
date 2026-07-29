package com.example.payment.application.service;

import java.util.List;

public record CancelPaymentCommand(
    String paymentKey,
    String cancelReason,
    List<Long> cancelPaymentItemIds,  // paymentItemId 목록 (오름차순 정렬로 request_hash 생성)
    String idempotencyKey  // 클라 Idempotency-Key 헤더 값(optional, nullable)
) {}
