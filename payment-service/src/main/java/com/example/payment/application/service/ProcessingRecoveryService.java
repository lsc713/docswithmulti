package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.*;
import com.example.payment.common.exception.BusinessException;
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
 * PROCESSING 5분 초과 건 복구.
 *
 * PG 조회 실패  → PROCESSING 유지
 * PG APPROVED  → TX3 재실행
 * PG FAILED    → retryable: 재호출(최대 5회) / 비retryable: 보상+FAILED
 * PG PENDING   → markPgPending, 1시간 초과 시 보상+FAILED+운영팀 알림
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingRecoveryService {

    private static final Duration PROCESSING_THRESHOLD = Duration.ofMinutes(5);
    private static final Duration PG_PENDING_TIMEOUT = Duration.ofHours(1);
    private static final int MAX_PG_RETRIES = 5;

    private final CancelRequestRepository cancelRequestRepository;
    private final CancelRequestHistoryRepository historyRepository;
    private final RiskManagementPort riskManagementPort;
    private final CompensationRetryRepository compensationRetryRepository;
    private final PaymentRepository paymentRepository;
    private final PgCancelPort pgCancelPort;
    private final CancelTxWriter cancelTxWriter;
    private final OperationAlertPort operationAlertPort;

    public void recoverAll() {
        Instant threshold = Instant.now().minus(PROCESSING_THRESHOLD);
        List<CancelRequest> stale = cancelRequestRepository.findProcessingUpdatedBefore(threshold);
        log.info("[processing-recovery] 대상={}건 threshold={}", stale.size(), threshold);
        stale.forEach(this::recoverOne);
    }

    private void recoverOne(CancelRequest cancelRequest) {
        try {
            Payment payment = paymentRepository.findById(cancelRequest.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + cancelRequest.getPaymentId()));

            PgCancelResult result;
            try {
                result = pgCancelPort.getStatus(payment.getPaymentKey());
            } catch (Exception e) {
                log.warn("[processing-recovery] PG 조회 실패, PROCESSING 유지 cancelRequestId={}: {}",
                    cancelRequest.getId(), e.getMessage());
                return;
            }

            if (result.isApproved()) {
                runTx3(cancelRequest, payment);
            } else if (result.isFailed()) {
                handleFailed(cancelRequest, payment, result);
            } else if (result.isPending()) {
                handlePgPending(cancelRequest, payment);
            }
        } catch (BusinessException e) {
            log.error("[processing-recovery] 도메인 규칙 위반 — 데이터 정합성 문제 cancelRequestId={}: {}",
                cancelRequest.getId(), e.getMessage(), e);
        } catch (Exception e) {
            log.warn("[processing-recovery] 처리 실패 cancelRequestId={}: {}",
                cancelRequest.getId(), e.getMessage());
        }
    }

    private void runTx3(CancelRequest cancelRequest, Payment payment) {
        CancelRequest completed = cancelTxWriter.saveTx3(
            cancelRequest, payment, cancelRequest.getCancelItemIds());
        recordHistory(completed.getId(), CancelStatus.COMPLETED, "processing-recovery");
    }

    private void handleFailed(CancelRequest cancelRequest, Payment payment, PgCancelResult result) {
        if (result.retryable() && cancelRequest.getPgRetryCount() < MAX_PG_RETRIES) {
            retryPgCancel(cancelRequest, payment);
        } else {
            compensateAndFail(cancelRequest, payment);
        }
    }

    private void retryPgCancel(CancelRequest cancelRequest, Payment payment) {
        cancelRequest.incrementPgRetryCount();
        cancelRequestRepository.save(cancelRequest);

        PgCancelResult retryResult;
        try {
            retryResult = pgCancelPort.cancel(
                payment.getPaymentKey(), cancelRequest.getCancelAmount(), cancelRequest.getCancelReason());
        } catch (Exception e) {
            log.warn("[processing-recovery] PG 재호출 실패 #{} cancelRequestId={}: {}",
                cancelRequest.getPgRetryCount(), cancelRequest.getId(), e.getMessage());
            if (cancelRequest.getPgRetryCount() >= MAX_PG_RETRIES) {
                compensateAndFail(cancelRequest, payment);
            }
            return;
        }

        if (retryResult.isApproved()) {
            runTx3(cancelRequest, payment);
        } else if (cancelRequest.getPgRetryCount() >= MAX_PG_RETRIES) {
            compensateAndFail(cancelRequest, payment);
        }
    }

    private void handlePgPending(CancelRequest cancelRequest, Payment payment) {
        cancelRequest.markPgPending();
        cancelRequestRepository.save(cancelRequest);

        if (cancelRequest.getPgPendingSince() != null
                && cancelRequest.getPgPendingSince().plus(PG_PENDING_TIMEOUT).isBefore(Instant.now())) {
            compensateAndFail(cancelRequest, payment);
            operationAlertPort.alertPgPendingTimeout(
                cancelRequest.getId(), payment.getPaymentKey(), cancelRequest.getPgPendingSince());
        }
    }

    private void compensateAndFail(CancelRequest cancelRequest, Payment payment) {
        try {
            riskManagementPort.compensate(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        } catch (Exception ex) {
            log.warn("[processing-recovery] 보상 실패 cancelRequestId={}: {}",
                cancelRequest.getId(), ex.getMessage());
            compensationRetryRepository.save(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        }
        cancelRequest.toFailed();
        cancelRequestRepository.save(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.FAILED, "processing-recovery");
    }

    private void recordHistory(long cancelRequestId, CancelStatus status, String reason) {
        try {
            historyRepository.record(cancelRequestId, status, reason);
        } catch (Exception e) {
            log.warn("[processing-recovery] 이력 기록 실패 (비즈니스 영향 없음) cancelRequestId={}", cancelRequestId, e);
        }
    }
}
