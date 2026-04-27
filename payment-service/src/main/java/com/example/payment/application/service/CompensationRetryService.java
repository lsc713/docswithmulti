package com.example.payment.application.service;

import com.example.payment.application.interfaces.CompensationRetryRepository;
import com.example.payment.application.interfaces.CompensationRetryRepository.PendingCompensation;
import com.example.payment.application.interfaces.RiskManagementPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * compensation_retry 테이블의 PENDING 건을 순회하며 risk 보상 API를 재시도한다.
 *
 * 재시도 정책:
 *   - 최대 5회 시도
 *   - 시도 간격: attempt * 60초 (1분, 2분, 3분, 4분, 5분)
 *   - 5회 초과 시 status=FAILED로 고정 (수동 처리 필요)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationRetryService {

    private static final int MAX_ATTEMPTS = 5;

    private final CompensationRetryRepository compensationRetryRepository;
    private final RiskManagementPort riskManagementPort;

    public void retryAll() {
        LocalDateTime now = LocalDateTime.now();
        List<PendingCompensation> due = compensationRetryRepository.findDueForRetry(now);
        log.info("[compensation-retry] 실행 now={} 대상={}건", now, due.size());
        if (due.isEmpty()) {
            return;
        }
        due.forEach(this::retryOne);
    }

    private void retryOne(PendingCompensation pending) {
        int nextAttempt = pending.attemptCount() + 1;
        log.info("[compensation-retry] 시도 #{} cancelRequestId={} merchantId={}",
            nextAttempt, pending.cancelRequestId(), pending.merchantId());
        try {
            riskManagementPort.compensate(
                pending.cancelRequestId(), pending.merchantId(), pending.restoreAmount());
            compensationRetryRepository.markDone(pending.id());
            log.info("[compensation-retry] 성공 cancelRequestId={}", pending.cancelRequestId());
        } catch (Exception e) {
            log.warn("[compensation-retry] 실패 #{} cancelRequestId={}: {}",
                nextAttempt, pending.cancelRequestId(), e.getMessage());
            if (nextAttempt >= MAX_ATTEMPTS) {
                compensationRetryRepository.exhaust(pending.id(), nextAttempt, e.getMessage());
                log.error("[compensation-retry] 최대 시도({}) 초과 → 수동 처리 필요 cancelRequestId={}",
                    MAX_ATTEMPTS, pending.cancelRequestId());
            } else {
                LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds((long) nextAttempt * 60);
                compensationRetryRepository.markRetryLater(
                    pending.id(), nextAttempt, nextRetryAt, e.getMessage());
            }
        }
    }
}
