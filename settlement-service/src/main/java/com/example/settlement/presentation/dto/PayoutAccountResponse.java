package com.example.settlement.presentation.dto;

/** GET /v1/settlements/payout-account/{merchantId} 응답 (활성 계좌). */
public record PayoutAccountResponse(
    long merchantId, String bankCode, String accountNumber, String holderName, boolean active) {}
