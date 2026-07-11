package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;

/** 이력 배치 기록용 버퍼 항목. occurredAt은 상태전이 순간에 캡처된 시각. */
public record CancelHistoryEntry(
    long cancelRequestId, CancelStatus status, String reason, Instant occurredAt
) {}
