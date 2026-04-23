package com.example.payment.application.dto;

public record PgCancelResult(
    String pgTransactionId,
    String status   // "APPROVED" | "FAILED" | "PENDING"
) {
    public boolean isApproved() { return "APPROVED".equals(status); }
    public boolean isFailed()   { return "FAILED".equals(status); }
    public boolean isPending()  { return "PENDING".equals(status); }
}
