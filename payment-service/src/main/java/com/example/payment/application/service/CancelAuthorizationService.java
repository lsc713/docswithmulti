package com.example.payment.application.service;

import com.example.payment.application.authz.AuthenticatedUser;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.CancelAuthorizationUseCase;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.service.CancelAuthorizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 취소 인가 read-only 오케스트레이션.
 *
 * <p>신뢰 경계 가정 (D-P3-6): payment(8080) ingress 는 게이트웨이 파드에서만 허용되어야 한다
 * (k3s NetworkPolicy — 게이트웨이 파드만 ingress; 배포 시점 이관, Plan 02 필수 게이트).
 * payment 는 이 경계를 넘어온 X-User-* 헤더 role 을 <b>무검증 신뢰</b>한다 — JWT 재검증·spring-security
 * 의존 없음 (D-P3-3). payment 로 직접 도달해 헤더를 위조하는 스푸핑은 코드가 아니라 NetworkPolicy 로 막는다.
 *
 * <p>로드 최소화 (D-P3-5): ADMIN 은 payment 로드 없이 즉시 domain 위임. MERCHANT 경로에서만
 * findByPaymentKey 로 대상 payment 를 read-only 1회 로드해 targetMerchantId 를 얻는다.
 * USER·role 누락 경로는 로드 없이 targetMerchantId=null 로 domain 이 403 판정.
 */
@Service
@RequiredArgsConstructor
public class CancelAuthorizationService implements CancelAuthorizationUseCase {

    private final PaymentRepository paymentRepository;
    private final CancelAuthorizer cancelAuthorizer = new CancelAuthorizer();

    @Override
    public void authorize(AuthenticatedUser user, String paymentKey) {
        String role = user.role();

        // ADMIN: 전체 허용 — payment 로드 생략 (D-P3-5)
        if ("ADMIN".equals(role)) {
            cancelAuthorizer.authorize(role, null, null);
            return;
        }

        // 비정상 X-Merchant-Id 는 500 대신 null 로 흡수 → domain 이 403 판정 (T-03-04)
        Long headerMerchantId = parseMerchantId(user.merchantId());

        // MERCHANT 경로에서만 대상 payment 를 read-only 1회 로드 (D-P3-5)
        Long targetMerchantId = null;
        if ("MERCHANT".equals(role)) {
            Payment payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentNotFoundException(paymentKey));
            targetMerchantId = payment.getMerchantId();
        }

        cancelAuthorizer.authorize(role, headerMerchantId, targetMerchantId);
    }

    private Long parseMerchantId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
