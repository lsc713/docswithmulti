package com.example.settlement.presentation.dto;

import java.math.BigDecimal;

/** POST /v1/settlements/{id}/payout 응답. */
public record PayoutResponse(Long id, String status, BigDecimal amount) {}
