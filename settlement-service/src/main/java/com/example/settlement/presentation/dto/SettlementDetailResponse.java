package com.example.settlement.presentation.dto;

import com.example.settlement.application.service.SettlementQueryService.SettlementWithLines;

import java.util.List;

/** 정산 헤더 + 라인 명세 응답. */
public record SettlementDetailResponse(
    SettlementResponse settlement,
    List<SettlementLineResponse> lines
) {
    public static SettlementDetailResponse from(SettlementWithLines detail) {
        return new SettlementDetailResponse(
            SettlementResponse.from(detail.header()),
            detail.lines().stream().map(SettlementLineResponse::from).toList());
    }
}
