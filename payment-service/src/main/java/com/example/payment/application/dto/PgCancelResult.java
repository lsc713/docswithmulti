package com.example.payment.application.dto;

public record PgCancelResult(
    String pgTransactionId,
    String status,
    boolean retryable
) {
    public boolean isApproved() { return "APPROVED".equals(status); }
    public boolean isFailed()   { return "FAILED".equals(status); }
    public boolean isPending()  { return "PENDING".equals(status); }
    public boolean isRetryable() { return retryable; }

    public static PgCancelResult approved(String pgTransactionId) {
        return new PgCancelResult(pgTransactionId, "APPROVED", false);
    }

    /** 재시도 불가 실패 (카드사 정책, 취소 기간 만료 등) */
    public static PgCancelResult failed(String pgTransactionId) {
        return new PgCancelResult(pgTransactionId, "FAILED", false);
    }

    /** 재시도 가능 실패 (네트워크 오류, 일시적 PG 오류 등) */
    public static PgCancelResult retryableFailed(String pgTransactionId) {
        return new PgCancelResult(pgTransactionId, "FAILED", true);
    }

    public static PgCancelResult pending(String pgTransactionId) {
        return new PgCancelResult(pgTransactionId, "PENDING", false);
    }
}
