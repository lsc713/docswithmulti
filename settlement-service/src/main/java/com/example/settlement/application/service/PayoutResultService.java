package com.example.settlement.application.service;

import com.example.settlement.application.interfaces.PayoutRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 지급 결과 수렴 chokepoint(CONFIRM-03). webhook(PayoutCallbackController)·poll(PayoutPollScheduler)이
 * 모두 applyResult 한 지점으로 funnel — status-guarded UPDATE 가 승자 1건만 반영, 나머지는 0-row no-op.
 * (poll 로직 pollStuckProcessing 은 Task 2 에서 추가.)
 */
@Slf4j
@Service
public class PayoutResultService {

    private final PayoutRepository payoutRepo;

    public PayoutResultService(PayoutRepository payoutRepo) {
        this.payoutRepo = payoutRepo;
    }

    /** status-guarded UPDATE 위임. 0-row = 이미 terminal/경합 → no-op(mirror reconcile 0-row alert). */
    public int applyResult(String transferRef, String result, String err) {
        int rows = payoutRepo.applyResult(transferRef, result, err);
        if (rows == 0) {
            log.info("[payout] applyResult no-op (raced/terminal) ref={} result={}", transferRef, result);
        }
        return rows;
    }
}
