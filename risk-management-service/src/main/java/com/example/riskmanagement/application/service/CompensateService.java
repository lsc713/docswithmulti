package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageCompensation;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
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
    private final CancelLimitDomainService domainService;

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
            throw new IllegalArgumentException(
                "merchantId 불일치: 요청=" + cmd.merchantId() + ", 이력=" + history.getMerchantId());
        }
        MerchantCancelUsage usage = usageRepository
            .findByMerchantIdAndKstDateForUpdate(history.getMerchantId(), history.getKstDate())
            .orElseThrow(() -> new IllegalStateException("MerchantCancelUsage not found for history: " + history.getCancelRequestId()));

        domainService.applyCompensation(usage, cmd.restoreAmount());
        usageRepository.save(usage);
        compensationRepository.save(CancelUsageCompensation.record(
            cmd.cancelRequestId(), history.getMerchantId(), cmd.restoreAmount()));

        return new Result(cmd.cancelRequestId(), true, null);
    }
}
