package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.SettlementConfigService;
import com.example.settlement.presentation.dto.SettlementConfigRequest;
import com.example.settlement.presentation.dto.SettlementConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정산 요율 설정 (FEE-01, 쓰기 경로). feeRate 검증은 서비스에서 → 잘못된 값은 400(GlobalExceptionHandler).
 * in-service ADMIN 검사 없음 — 크로스-가맹점/위조 요율 쓰기는 배포 시 NetworkPolicy로 게이트웨이 파드 ingress만 허용해 차단
 * (payment/product ingress 게이트와 동일 클래스, objective 결정).
 */
@RestController
@RequestMapping("/v1/settlements/config")
@RequiredArgsConstructor
public class SettlementConfigController {

    private final SettlementConfigService service;

    @PutMapping("/{merchantId}")
    public SettlementConfigResponse setRate(
        @PathVariable long merchantId,
        @RequestBody SettlementConfigRequest request
    ) {
        service.setRate(merchantId, request.feeRate());
        return new SettlementConfigResponse(merchantId, request.feeRate());
    }
}
