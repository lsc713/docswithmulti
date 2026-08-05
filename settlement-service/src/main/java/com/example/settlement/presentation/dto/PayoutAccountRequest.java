package com.example.settlement.presentation.dto;

/** PUT /v1/settlements/payout-account/{merchantId} 본문. 비-blank 검증은 서비스에서(→400). */
public record PayoutAccountRequest(String bankCode, String accountNumber, String holderName) {}
