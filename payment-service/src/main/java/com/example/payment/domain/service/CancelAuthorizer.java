package com.example.payment.domain.service;

import com.example.payment.common.exception.domain.CancelNotAuthorizedException;

/**
 * 취소 인가 판정 도메인 규칙 (순수 POJO).
 *
 * Spring/JPA/lombok 어노테이션·repository·carrier 타입 의존 없음 — primitive 만 받는다 (D-P3-4).
 * 정책(D-P3-2):
 *   - ADMIN            → 전체 허용
 *   - MERCHANT         → headerMerchantId == targetMerchantId (둘 다 non-null) 일 때만 허용
 *   - USER·role 누락·불일치·merchantId 누락 → 403 (CancelNotAuthorizedException)
 *
 * 참조 자산의 USER self-cancel 분기는 채택하지 않는다 (우리 정책 USER→403).
 */
public class CancelAuthorizer {

    public void authorize(String role, Long headerMerchantId, Long targetMerchantId) {
        if ("ADMIN".equals(role)) {
            return;
        }
        if ("MERCHANT".equals(role)
                && headerMerchantId != null
                && targetMerchantId != null
                && headerMerchantId.equals(targetMerchantId)) {
            return;
        }
        throw new CancelNotAuthorizedException();
    }
}
