package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.PayoutResultService;
import com.example.settlement.presentation.dto.PayoutCallbackRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 은행 지급 결과 webhook (CONFIRM-01). <b>은행→서비스</b> push 라우트 — 게이트웨이 JWT 인증 라우트가 아니다.
 * settlement-service 에는 Spring Security 가 없어 서명(shared-secret)만으로 인증:
 * X-Bank-Signature 헤더를 ${payout.webhook.secret} 과 constant-time(MessageDigest.isEqual) 비교,
 * 불일치 시 401 을 ResponseEntity 로 <b>직접 반환</b>(예외 throw 시 GlobalExceptionHandler 가 500 으로 삼킴).
 *
 * <p><b>배포 NetworkPolicy 주의</b>: 이 콜백은 (mock) 은행이 직접 호출해야 하므로 query/config 라우트처럼
 * 게이트웨이 파드 전용 ingress 로 묶으면 안 된다(SettlementQueryController 와 ingress 클래스가 다름).
 */
@Slf4j
@RestController
public class PayoutCallbackController {

    private final PayoutResultService resultService;
    private final byte[] secretBytes;

    public PayoutCallbackController(PayoutResultService resultService,
                                    @Value("${payout.webhook.secret}") String secret) {
        this.resultService = resultService;
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/v1/payouts/callback")
    public ResponseEntity<Void> callback(
        @RequestHeader(value = "X-Bank-Signature", required = false) String signature,
        @RequestBody PayoutCallbackRequest request
    ) {
        byte[] provided = signature == null ? new byte[0] : signature.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(secretBytes, provided)) {
            log.warn("[payout-callback] 서명 불일치 — 401, 상태 무변경 ref={}", request.transferRef());
            return ResponseEntity.status(401).build();
        }
        resultService.applyResult(request.transferRef(), request.result(), null);
        return ResponseEntity.ok().build();
    }
}
