package com.example.riskmanagement.application.interfaces;

import com.example.riskmanagement.domain.entity.CancelUsageHistory;

import java.util.Optional;

public interface CancelUsageHistoryRepository {
    CancelUsageHistory save(CancelUsageHistory history);
    Optional<CancelUsageHistory> findByCancelRequestId(String cancelRequestId);
}
