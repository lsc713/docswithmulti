package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.exception.CompensationMerchantMismatchException;
import com.example.riskmanagement.application.exception.DataInconsistencyException;
import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageCompensation;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CompensateService implements CompensateUseCase {

    private final CancelUsageCompensationRepository compensationRepository;
    private final CancelUsageHistoryRepository historyRepository;
    private final MerchantCancelUsageRepository usageRepository;

    @Override
    @Transactional
    public Result execute(Command cmd) {
        if (compensationRepository.existsByCancelRequestId(cmd.cancelRequestId()))
            return new Result(cmd.cancelRequestId(), false, "ALREADY_COMPENSATED");

        Optional<CancelUsageHistory> historyOpt =
            historyRepository.findByCancelRequestId(cmd.cancelRequestId());
        if (historyOpt.isEmpty())
            return new Result(cmd.cancelRequestId(), false, "NOT_CHARGED");

        CancelUsageHistory history = historyOpt.get();
        if (history.getMerchantId() != cmd.merchantId()) {
            throw new CompensationMerchantMismatchException(cmd.merchantId(), history.getMerchantId());
        }

        // 원자 복원: used_amount = GREATEST(used - amount, 0). read-modify-write 갭 없음(lost update 방지).
        // 대상은 이력의 (merchantId, kstDate) — 소진 원장의 원본. 유효 상태면 항상 1행.
        int restored = usageRepository.tryRestore(
            history.getMerchantId(), history.getKstDate(), cmd.restoreAmount());
        if (restored == 0) {
            throw new DataInconsistencyException(
                "MerchantCancelUsage not found for history: " + history.getCancelRequestId());
        }

        compensationRepository.save(CancelUsageCompensation.record(
            cmd.cancelRequestId(), history.getMerchantId(), cmd.restoreAmount()));

        return new Result(cmd.cancelRequestId(), true, null);
    }
}
