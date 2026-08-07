package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.ReserveConfigService;
import com.example.settlement.domain.entity.MerchantReserveConfig;
import com.example.settlement.presentation.dto.ReserveConfigRequest;
import com.example.settlement.presentation.dto.ReserveConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 유보 정책 설정/조회 (RCFG-01/02). 값 검증은 서비스에서 → 잘못된 값 400·미설정 404(GlobalExceptionHandler).
 * in-service ADMIN 검사 없음 — 크로스-가맹점/위조 정책 쓰기는 배포 시 NetworkPolicy 로 게이트웨이 파드 ingress 만
 * 허용해 차단(SettlementConfigController 와 동일 클래스).
 */
@RestController
@RequestMapping("/v1/settlements/reserve-config")
@RequiredArgsConstructor
public class ReserveConfigController {

    private final ReserveConfigService service;

    @PutMapping("/{merchantId}")
    public ReserveConfigResponse setConfig(
        @PathVariable long merchantId,
        @RequestBody ReserveConfigRequest request
    ) {
        service.setConfig(merchantId, request.reserveRate(), request.reserveCap(), request.holdDays());
        return new ReserveConfigResponse(
            merchantId, request.reserveRate(), request.reserveCap(), request.holdDays());
    }

    /** 유보 정책 조회(RCFG-02). 없으면 RESERVE_CONFIG_NOT_FOUND(404, GlobalExceptionHandler). */
    @GetMapping("/{merchantId}")
    public ReserveConfigResponse get(@PathVariable long merchantId) {
        MerchantReserveConfig c = service.getConfig(merchantId);
        return new ReserveConfigResponse(
            c.getMerchantId(), c.getReserveRate(), c.getReserveCap(), c.getHoldDays());
    }
}
