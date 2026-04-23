package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.CancelUsageHistoryRepository;
import com.example.riskmanagement.application.usecase.CheckChargeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckChargeService implements CheckChargeUseCase {

    private final CancelUsageHistoryRepository historyRepository;

    @Override
    @Transactional(readOnly = true)
    public Result execute(String cancelRequestId) {
        return historyRepository.findByCancelRequestId(cancelRequestId)
            .map(h -> new Result(cancelRequestId, true, h.getMerchantId(), h.getCancelAmount()))
            .orElseGet(() -> new Result(cancelRequestId, false, null, null));
    }
}
