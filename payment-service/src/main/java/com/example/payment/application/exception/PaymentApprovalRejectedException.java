package com.example.payment.application.exception;

public class PaymentApprovalRejectedException extends RuntimeException {
    public PaymentApprovalRejectedException() {
        super("토스 결제 승인이 거절되었습니다.");
    }
}
