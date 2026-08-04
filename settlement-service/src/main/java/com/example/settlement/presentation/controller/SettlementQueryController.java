package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.SettlementQueryService;
import com.example.settlement.presentation.dto.SettlementDetailResponse;
import com.example.settlement.presentation.dto.SettlementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 정산 원장 조회 (QUERY-01). Phase 1은 caller 인가 검사 없음 —
 * 크로스-가맹점 재무 노출(IDOR)은 배포 시 NetworkPolicy로 게이트웨이 파드에만 ingress 허용해 차단
 * (payment/product ingress 게이트와 동일 클래스, objective 결정).
 */
@RestController
@RequestMapping("/v1/settlements")
@RequiredArgsConstructor
public class SettlementQueryController {

    private final SettlementQueryService queryService;

    @GetMapping
    public List<SettlementResponse> list(
        @RequestParam long merchantId,
        @RequestParam(required = false) String status
    ) {
        return queryService.list(merchantId, status).stream()
            .map(SettlementResponse::from)
            .toList();
    }

    @GetMapping("/{id}")
    public SettlementDetailResponse detail(@PathVariable long id) {
        return SettlementDetailResponse.from(queryService.detail(id));
    }
}
