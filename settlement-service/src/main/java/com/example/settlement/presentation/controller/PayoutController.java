package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.PayoutService;
import com.example.settlement.domain.entity.Payout;
import com.example.settlement.presentation.dto.PayoutResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지급 승인 (PAY-01). FINALIZED 정산 → PROCESSING 지급 + 은행 제출. 검증 실패는 400(GlobalExceptionHandler).
 * in-service ADMIN 검사 없음 — 배포 시 NetworkPolicy 로 게이트웨이 파드 ingress 만 허용(SettlementConfigController 동일 클래스).
 */
@RestController
@RequestMapping("/v1/settlements")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService service;

    @PostMapping("/{id}/payout")
    public PayoutResponse approve(@PathVariable long id) {
        Payout p = service.approve(id);
        return new PayoutResponse(p.getId(), p.getStatus(), p.getAmount());
    }

    /** 지급 상태 조회(PAY-03). 없으면 PAYOUT_NOT_FOUND(404, GlobalExceptionHandler). */
    @GetMapping("/{id}/payout")
    public PayoutResponse payout(@PathVariable long id) {
        Payout p = service.getPayout(id);
        return new PayoutResponse(p.getId(), p.getStatus(), p.getAmount());
    }
}
