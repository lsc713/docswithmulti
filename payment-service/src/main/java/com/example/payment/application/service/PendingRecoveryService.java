package com.example.payment.application.service;

import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PENDING 5분 초과 건 복구.
 *
 * risk.isCharged=true  → compensate → FAILED
 * risk.isCharged=false → FAILED (보상 불필요)
 * 각 건 예외 시 log.warn + 다음 건 계속 (스케줄러 중단 없음)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingRecoveryService {

    private static final Duration PENDING_THRESHOLD = Duration.ofMinutes(5);

    private final CancelRequestRepository cancelRequestRepository;
    private final CancelRequestHistoryRepository historyRepository;
    private final RiskManagementPort riskManagementPort;
    private final CompensationRetryRepository compensationRetryRepository;
    private final PaymentRepository paymentRepository;

    public void recoverAll() {
        Instant threshold = Instant.now().minus(PENDING_THRESHOLD);
        List<CancelRequest> stale = cancelRequestRepository.findPendingCreatedBefore(threshold);
        log.info("[pending-recovery] 대상={}건 threshold={}", stale.size(), threshold);
        stale.forEach(this::recoverOne);
    }

    private void recoverOne(CancelRequest cancelRequest) {
        try {
            Payment payment = paymentRepository.findById(cancelRequest.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + cancelRequest.getPaymentId()));

            boolean charged = riskManagementPort.isCharged(cancelRequest.getId());
            if (charged) {
                tryCompensate(cancelRequest, payment);
            }
            cancelRequest.toFailed();
            cancelRequestRepository.save(cancelRequest);
            recordHistory(cancelRequest.getId(), CancelStatus.FAILED, "pending-recovery");
        } catch (Exception e) {
            log.warn("[pending-recovery] 처리 실패 cancelRequestId={}: {}", cancelRequest.getId(), e.getMessage());
        }
    }

    private void tryCompensate(CancelRequest cancelRequest, Payment payment) {
        try {
            riskManagementPort.compensate(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        } catch (Exception ex) {
            log.warn("[pending-recovery] 보상 실패 cancelRequestId={}: {}", cancelRequest.getId(), ex.getMessage());
            compensationRetryRepository.save(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        }
    }

    private void recordHistory(long cancelRequestId, CancelStatus status, String reason) {
        try {
            historyRepository.record(cancelRequestId, status, reason);
        } catch (Exception e) {
            log.warn("[pending-recovery] 이력 기록 실패 (비즈니스 영향 없음) cancelRequestId={}", cancelRequestId, e);
        }
    }
}
