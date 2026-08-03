package com.example.settlement.application.interfaces;

import com.example.settlement.domain.entity.SettlementLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface SettlementLineRepository {

    /** event_id UK가 중복 적재를 DB 레벨에서 차단(멱등의 권위 가드). 충돌 시 DataIntegrityViolationException. */
    void insert(long settlementId, String type, String paymentKey,
                BigDecimal amount, String eventId, Instant occurredAt);

    List<SettlementLine> findBySettlementId(long settlementId);
}
