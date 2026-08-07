package com.example.settlement.presentation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** GET /v1/settlements/{id}/reserve 응답 (HOLD-04). PayoutResponse 미러 + hold_until/transfer_ref. */
public record ReserveResponse(Long id, String status, BigDecimal amount, LocalDate holdUntil, String transferRef) {}
