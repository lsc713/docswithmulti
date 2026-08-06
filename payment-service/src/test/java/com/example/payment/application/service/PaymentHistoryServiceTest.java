package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelApprovalRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.presentation.dto.PaymentSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 주문내역 목록에 cancelRequestStatus 파생 로직 검증 (P3) — 취소 코어 무관, 읽기 전용. */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentHistoryService.list — cancelRequestStatus 파생")
class PaymentHistoryServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentItemRepository paymentItemRepository;
    @Mock CancelApprovalRepository cancelApprovalRepository;
    @InjectMocks PaymentHistoryService service;

    private Payment payment(long id) {
        return Payment.reconstruct(
            id, "pay_" + id, 1L, 7L, "TOSS", new BigDecimal("10000"), "KRW",
            7, PaymentStatus.COMPLETED, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("최신 승인건이 REQUESTED이면 cancelRequestStatus == \"REQUESTED\"")
    void latest_requested() {
        when(paymentRepository.findByUserId(7L, 0, 20)).thenReturn(List.of(payment(1L)));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(cancelApprovalRepository.findLatestByPaymentId(1L))
            .thenReturn(Optional.of(CancelApproval.request(1L, "pay_1", 7L, "단순 변심")));

        List<PaymentSummaryResponse> result = service.list(7L, 0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cancelRequestStatus()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("최신 승인건이 REJECTED이면 cancelRequestStatus == \"REJECTED\"")
    void latest_rejected() {
        CancelApproval rejected = CancelApproval.request(1L, "pay_1", 7L, "단순 변심");
        rejected.reject(9L, "ADMIN", "재고 부족");

        when(paymentRepository.findByUserId(7L, 0, 20)).thenReturn(List.of(payment(1L)));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(cancelApprovalRepository.findLatestByPaymentId(1L)).thenReturn(Optional.of(rejected));

        List<PaymentSummaryResponse> result = service.list(7L, 0, 20);

        assertThat(result.get(0).cancelRequestStatus()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("승인건이 없으면 cancelRequestStatus == null")
    void no_approval_is_null() {
        when(paymentRepository.findByUserId(7L, 0, 20)).thenReturn(List.of(payment(1L)));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(cancelApprovalRepository.findLatestByPaymentId(1L)).thenReturn(Optional.empty());

        List<PaymentSummaryResponse> result = service.list(7L, 0, 20);

        assertThat(result.get(0).cancelRequestStatus()).isNull();
    }
}
