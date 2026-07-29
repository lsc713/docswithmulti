package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.*;
import com.example.payment.common.exception.BusinessException;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
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
                result = pgCancelPort.getStatus(payment.getPaymentKey(), cancelRequest.getCancelAmount());
            } catch (Exception e) {
                log.warn("[processing-recovery] PG 조회 실패, PROCESSING 유지 cancelRequestId={}: {}",
                    cancelRequest.getId(), e.getMessage());
                return;
            }

            if (result.isApproved()) {
                runTx3(cancelRequest, payment, result.pgTransactionId());
            } else if (result.isFailed()) {
                handleFailed(cancelRequest, payment, result);
            } else if (result.isPending()) {
                handlePgPending(cancelRequest, payment);
            } else {
                // WR-01: APPROVED/FAILED/PENDING 어디에도 안 걸리는 미지의 PG 상태 문자열
                // (오탈자, PG 스펙 변경 등) — 조용히 무시하면 이 건이 계속 PROCESSING으로 남아
                // 운영팀이 알아챌 방법이 없으므로 최소한 경고 로그를 남긴다.
                log.warn("[processing-recovery] 알 수 없는 PG 상태={} cancelRequestId={}",
                    result.status(), cancelRequest.getId());
            }
        } catch (InvalidPaymentItemStatusException e) {
            // WR-02: saveTx3 동시 재실행 시 패자 스레드가 던지는 정상적인 레이스 결과
            // (findAllByPaymentIdForUpdate 행 락으로 순서가 강제되고, 승자 커밋 후 재조회한 패자가
            // 이미 CANCELLED된 아이템을 보고 거부 — ProcessingRecoveryConcurrencyIT 검증(B)로 확인됨).
            // ERROR "데이터 정합성 문제"로 남기면 정상 동시 처리마다 온콜 오탐 알림이 발생하므로 WARN.
            log.warn("[processing-recovery] 동시 처리 경쟁(예상됨) — 다른 스레드가 먼저 처리함 cancelRequestId={}: {}",
                cancelRequest.getId(), e.getMessage());
        } catch (BusinessException e) {
            log.error("[processing-recovery] 도메인 규칙 위반 — 데이터 정합성 문제 cancelRequestId={}: {}",
                cancelRequest.getId(), e.getMessage(), e);
        } catch (Exception e) {
            log.warn("[processing-recovery] 처리 실패 cancelRequestId={}: {}",
                cancelRequest.getId(), e.getMessage());
        }
    }

    private void runTx3(CancelRequest cancelRequest, Payment payment, String pgTransactionKey) {
        // D-01: saveTx3는 CancelRequest를 재조회하지 않고 전달받은 객체를 그대로 저장하므로
        // 여기서 세팅하면 그대로 반영된다.
        if (pgTransactionKey != null) {
            cancelRequest.assignPgTransactionKey(pgTransactionKey);
        }
        CancelRequest completed = cancelTxWriter.saveTx3(
            cancelRequest, payment, cancelRequest.getCancelItemIds());
        recordHistory(completed.getId(), CancelStatus.COMPLETED, "processing-recovery");
    }

    private void handleFailed(CancelRequest cancelRequest, Payment payment, PgCancelResult result) {
        if (result.isRetryable() && cancelRequest.getPgRetryCount() < MAX_PG_RETRIES) {
            retryPgCancel(cancelRequest, payment);
        } else {
            compensateAndFail(cancelRequest, payment);
        }
    }

    private void retryPgCancel(CancelRequest cancelRequest, Payment payment) {
        // 원자 UPDATE — 객체 mutation+save 대신 단일 SQL로 DB 값만 증가(D-04, read-modify-write 경쟁 제거)
        cancelRequestRepository.incrementPgRetryCount(cancelRequest.getId());

        // ★ 원자 UPDATE 직후 로컬 cancelRequest 는 stale — 임계값 비교 전 반드시 재조회(RESEARCH Pitfall 2)
        CancelRequest refreshed = cancelRequestRepository
            .findByPaymentIdAndRequestHash(cancelRequest.getPaymentId(), cancelRequest.getRequestHash())
            .orElseThrow(() -> new IllegalStateException(
                "CancelRequest not found after pg_retry_count 증가: id=" + cancelRequest.getId()));

        if (refreshed.getPgRetryCount() >= MAX_PG_RETRIES) {
            // 재조회한 값이 상한에 도달 — PG 재호출 없이 즉시 보상+FAILED (재시도 폭주 방지, T-02-07)
            compensateAndFail(refreshed, payment);
            return;
        }

        PgCancelResult retryResult;
        try {
            retryResult = pgCancelPort.cancel(
                payment.getPaymentKey(), refreshed.getCancelAmount(), refreshed.getCancelReason());
        } catch (Exception e) {
            log.warn("[processing-recovery] PG 재호출 실패 #{} cancelRequestId={}: {}",
                refreshed.getPgRetryCount(), refreshed.getId(), e.getMessage());
            return;
        }

        if (retryResult.isApproved()) {
            runTx3(refreshed, payment, retryResult.pgTransactionId());
        }
        // 임계 미만 & 미승인 → PROCESSING 유지, 다음 주기 재시도
    }

    private void handlePgPending(CancelRequest cancelRequest, Payment payment) {
        cancelRequest.markPgPending();

        if (cancelRequest.getPgPendingSince() != null
                && cancelRequest.getPgPendingSince().plus(PG_PENDING_TIMEOUT).isBefore(Instant.now())) {
            // Timeout: no need to save PENDING state, compensateAndFail transitions PROCESSING→FAILED atomically
            compensateAndFail(cancelRequest, payment);
            try {
                operationAlertPort.alertPgPendingTimeout(
                    cancelRequest.getId(), payment.getPaymentKey(), cancelRequest.getPgPendingSince());
            } catch (Exception ex) {
                log.warn("[processing-recovery] 운영팀 알림 실패 cancelRequestId={}: {}",
                    cancelRequest.getId(), ex.getMessage());
            }
        } else {
            // Not timed out: persist the pgPendingSince timestamp
            cancelRequestRepository.save(cancelRequest);
        }
    }

    private void compensateAndFail(CancelRequest cancelRequest, Payment payment) {
        // CR-03: PROCESSING→FAILED 조건부 원자 UPDATE(incrementPgRetryCount와 동일한 D-04 패턴)를
        // compensate 호출 전에 먼저 시도 — 이 전이에 성공한 스레드만 compensate 진행.
        // 레코드 단위 분산락 대신 상태 전이 자체를 멱등 가드로 사용(D-04, 락 추가 금지).
        // 스케줄러 Redis 락(leaseTime=55s, 워치독 미사용)이 만료돼 중복 처리 창이 열려도
        // risk-management.compensate()가 두 번 호출되는 것을 막는다.
        int updated = cancelRequestRepository.compareAndSetFailed(cancelRequest.getId());
        if (updated == 0) {
            log.info("[processing-recovery] compensateAndFail skip — 이미 다른 스레드가 FAILED 전이 완료(중복 보상 방지) cancelRequestId={}",
                cancelRequest.getId());
            return;
        }

        try {
            riskManagementPort.compensate(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        } catch (Exception ex) {
            log.warn("[processing-recovery] 보상 실패 cancelRequestId={}: {}",
                cancelRequest.getId(), ex.getMessage());
            compensationRetryRepository.save(
                cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
        }
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
