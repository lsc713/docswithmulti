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
        MerchantCancelUsage usage = usageRepository
            .findByMerchantIdAndKstDate(history.getMerchantId(), history.getKstDate())
            .orElseThrow();

        domainService.applyCompensation(usage, cmd.restoreAmount());
        usageRepository.save(usage);
        compensationRepository.save(CancelUsageCompensation.record(
            cmd.cancelRequestId(), cmd.merchantId(), cmd.restoreAmount()));

        return new Result(cmd.cancelRequestId(), true, null);
    }
}
