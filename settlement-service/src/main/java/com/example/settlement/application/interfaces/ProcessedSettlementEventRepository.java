package com.example.settlement.application.interfaces;

public interface ProcessedSettlementEventRepository {

    /** 컨슈머 레벨 멱등 선체크(최적화). 권위 가드는 settlement_line.event_id UK. */
    boolean existsByEventId(String eventId);

    void save(String eventId);
}
