package com.example.payment.application.usecase;

import com.example.payment.presentation.dto.PaymentDetailResponse;
import com.example.payment.presentation.dto.PaymentSummaryResponse;
import java.util.List;

/** 주문내역 조회 (본인 결제만, 읽기 전용) — P3. */
public interface PaymentHistoryQuery {
    List<PaymentSummaryResponse> list(long userId, int page, int size);

    /** 비소유 paymentKey는 PaymentNotFoundException(404, 존재 은닉). */
    PaymentDetailResponse detail(long userId, String paymentKey);
}
