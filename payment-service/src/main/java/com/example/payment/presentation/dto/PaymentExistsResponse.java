package com.example.payment.presentation.dto;

/**
 * GET /v1/payments/{paymentKey}/exists 응답 (RST-03).
 */
public record PaymentExistsResponse(boolean exists) {
}
