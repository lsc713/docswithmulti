package com.example.payment.domain.service;

import com.example.payment.common.exception.domain.CancelNotAuthorizedException;

/**
 * 취소 인가 판정 도메인 규칙 (순수 POJO). primitive 만 받는다.
 * 정책:
 *   - ADMIN                          → 전체 허용
 *   - MERCHANT                       → headerMerchantId == targetMerchantId (둘 다 non-null)
 *   - USER + requestUserId == targetUserId (둘 다 non-null) → 본인 결제 취소 허용 (P3, 정책 전환)
 *   - 그 외(role 누락·불일치·소유 불일치·merchantId 누락) → 403
 */
public class CancelAuthorizer {

    public void authorize(String role, Long requestUserId, Long targetUserId,
                          Long headerMerchantId, Long targetMerchantId) {
        if ("ADMIN".equals(role)) {
            return;
        }
        if ("MERCHANT".equals(role)
                && headerMerchantId != null && targetMerchantId != null
                && headerMerchantId.equals(targetMerchantId)) {
            return;
        }
        if ("USER".equals(role)
                && requestUserId != null && targetUserId != null
                && requestUserId.equals(targetUserId)) {
            return;
        }
        throw new CancelNotAuthorizedException();
    }
}
