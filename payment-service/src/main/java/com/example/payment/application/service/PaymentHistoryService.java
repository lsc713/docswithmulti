package com.example.payment.application.service;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.CancelApprovalRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.PaymentHistoryQuery;
import com.example.payment.domain.entity.Payment;
import com.example.payment.presentation.dto.PaymentDetailResponse;
import com.example.payment.presentation.dto.PaymentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 주문내역 조회 서비스 (읽기 전용, 취소 코어 TX와 무관) — P3. */
@Service
@RequiredArgsConstructor
public class PaymentHistoryService implements PaymentHistoryQuery {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final CancelApprovalRepository cancelApprovalRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentSummaryResponse> list(long userId, int page, int size) {
        // ponytail: 결제당 items/승인건 조회(N+1) — 데모 규모 전제. 규모 시 배치 조회로 교체.
        return paymentRepository.findByUserId(userId, page, size).stream()
            .map(p -> {
                String crs = cancelApprovalRepository.findLatestByPaymentId(p.getId())
                    .map(a -> switch (a.getStatus()) {
                        case REQUESTED -> "REQUESTED";
                        case REJECTED -> "REJECTED";
                        default -> null;   // APPROVED → payment.status 가 CANCELLED 로 이미 표현
                    })
                    .orElse(null);
                return PaymentSummaryResponse.from(p, paymentItemRepository.findAllByPaymentIdOrderByIdAsc(p.getId()), crs);
            })
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentDetailResponse detail(long userId, String paymentKey) {
        Payment p = paymentRepository.findByPaymentKey(paymentKey)
            .filter(pay -> pay.getUserId() == userId)   // 소유 아니면 존재 은닉(404)
            .orElseThrow(() -> new PaymentNotFoundException(paymentKey));
        return PaymentDetailResponse.from(p, paymentItemRepository.findAllByPaymentIdOrderByIdAsc(p.getId()));
    }
}
