package com.example.payment.application.usecase;

import com.example.payment.domain.entity.CancelOutboxRedrive;

public interface CancelOutboxRedriveQuery {
    CancelOutboxRedrive get(long redriveId);
}
