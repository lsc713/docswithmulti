package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.PayoutService;
import com.example.settlement.presentation.dto.PayoutAccountRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지급 계좌 설정 (ACCT-01, 쓰기 경로). 비-blank 검증은 서비스에서 → 잘못된 값은 400(GlobalExceptionHandler).
 * in-service ADMIN 검사 없음 — 크로스-가맹점 계좌 쓰기(IDOR)는 배포 시 NetworkPolicy 로 게이트웨이 파드 ingress 만
 * 허용해 차단(SettlementConfigController 와 동일 클래스).
 */
@RestController
@RequestMapping("/v1/settlements/payout-account")
@RequiredArgsConstructor
public class PayoutAccountController {

    private final PayoutService service;

    @PutMapping("/{merchantId}")
    public ResponseEntity<Void> upsert(
        @PathVariable long merchantId,
        @RequestBody PayoutAccountRequest request
    ) {
        service.upsertAccount(merchantId, request.bankCode(), request.accountNumber(), request.holderName());
        return ResponseEntity.ok().build();
    }
}
