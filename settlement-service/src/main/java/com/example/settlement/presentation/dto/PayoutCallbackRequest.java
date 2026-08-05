package com.example.settlement.presentation.dto;

/** POST /v1/payouts/callback 본문(은행→서비스). result = PAID | FAILED. 서명은 X-Bank-Signature 헤더로 전송. */
public record PayoutCallbackRequest(String transferRef, String result) {}
