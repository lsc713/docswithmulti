package com.example.settlement.application.interfaces;

import com.example.settlement.domain.entity.SettlementLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface SettlementLineRepository {

    /** event_id UK가 중복 적재를 DB 레벨에서 차단(멱등의 권위 가드). 충돌 시 DataIntegrityViolationException. */
    void insert(long settlementId, String type, String paymentKey,
                BigDecimal amount, String eventId, Instant occurredAt);

    List<SettlementLine> findBySettlementId(long settlementId);

    /** type('SALE'/'CANCEL') → Σamount. 없는 타입은 키 부재(호출부가 default 0). 리컨실 drift 탐지의 권위 집계. */
    Map<String, BigDecimal> sumLinesByType(long settlementId);
}
