package com.example.riskmanagement.application.interfaces;

import com.example.riskmanagement.domain.entity.CancelUsageCompensation;

public interface CancelUsageCompensationRepository {
    CancelUsageCompensation save(CancelUsageCompensation compensation);
    boolean existsByCancelRequestId(String cancelRequestId);
}
