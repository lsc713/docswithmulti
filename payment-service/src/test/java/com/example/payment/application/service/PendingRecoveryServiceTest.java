package com.example.payment.application.service;

import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import com.example.payment.fixture.CancelRequestFixture;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingRecoveryService")
class PendingRecoveryServiceTest {

    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock CancelRequestHistoryRepository historyRepository;
    @Mock RiskManagementPort riskManagementPort;
    @Mock CompensationRetryRepository compensationRetryRepository;
    @Mock PaymentRepository paymentRepository;

    PendingRecoveryService service;
    Payment payment;
    CancelRequest pendingRequest;

    @BeforeEach
    void setUp() {
        service = new PendingRecoveryService(
            cancelRequestRepository, historyRepository,
            riskManagementPort, compensationRetryRepository, paymentRepository
        );
        payment = PaymentFixture.completedPayment(); // merchantId=1L
        pendingRequest = CancelRequestFixture.pendingWithId(1L, BigDecimal.valueOf(50000));
    }

    @Test
    @DisplayName("charged=true: 보상 성공 → FAILED + 이력")
    void charged_true_compensate_success() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(riskManagementPort.isCharged(anyLong())).thenReturn(true);

        service.recoverAll();

        verify(riskManagementPort).compensate(anyLong(), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(cancelRequestRepository).save(argThat(r -> r.getStatus() == CancelStatus.FAILED));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("charged=true: 보상 실패 → compensationRetry 저장 + FAILED + 이력")
    void charged_true_compensate_fails() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(riskManagementPort.isCharged(anyLong())).thenReturn(true);
        doThrow(new RuntimeException("risk 장애")).when(riskManagementPort)
            .compensate(anyLong(), anyLong(), any());

        service.recoverAll();

        verify(compensationRetryRepository).save(anyLong(), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(cancelRequestRepository).save(argThat(r -> r.getStatus() == CancelStatus.FAILED));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("charged=false: 보상 없이 FAILED + 이력")
    void charged_false_direct_failed() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(riskManagementPort.isCharged(anyLong())).thenReturn(false);

        service.recoverAll();

        verify(riskManagementPort, never()).compensate(anyLong(), anyLong(), any());
        verify(cancelRequestRepository).save(argThat(r -> r.getStatus() == CancelStatus.FAILED));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("isCharged 예외 발생 시 해당 건 skip, 스케줄러 중단 없음")
    void exception_during_recover_skips_and_continues() {
        CancelRequest second = CancelRequestFixture.pendingWithId(2L, BigDecimal.valueOf(30000));
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest, second));
        when(paymentRepository.findById(anyLong())).thenReturn(Optional.of(payment));
        when(riskManagementPort.isCharged(anyLong()))
            .thenThrow(new RuntimeException("첫 번째 건 오류"))
            .thenReturn(false);

        service.recoverAll();

        // 두 번째 건은 정상 처리됨
        verify(cancelRequestRepository, times(1)).save(argThat(r -> r.getStatus() == CancelStatus.FAILED));
    }

    @Test
    @DisplayName("대상 없으면 아무 작업 없음")
    void no_stale_pending_does_nothing() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of());

        service.recoverAll();

        verifyNoInteractions(riskManagementPort, paymentRepository, compensationRetryRepository);
    }

    @Test
    @DisplayName("payment not found 시 해당 건 skip")
    void payment_not_found_skips() {
        when(cancelRequestRepository.findPendingCreatedBefore(any())).thenReturn(List.of(pendingRequest));
        when(paymentRepository.findById(anyLong())).thenReturn(Optional.empty());

        service.recoverAll();

        verify(riskManagementPort, never()).isCharged(anyLong());
        verify(cancelRequestRepository, never()).save(any());
    }
}
