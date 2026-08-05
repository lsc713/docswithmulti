package com.example.payment.application.authz;

import com.example.payment.common.exception.domain.CancelNotAuthorizedException;

/**
 * 취소 승인 워크플로우 인가 판정 (순수 POJO).
 *
 * 정책:
 *   - authorizeDecision (승인/반려): ADMIN → 전체 허용. MERCHANT → merchantId == targetMerchantId
 *     (둘 다 non-null). USER 및 그 외 → 항상 거부.
 *   - authorizeRequest (요청 생성): userId == paymentOwnerUserId (둘 다 non-null) 인 경우만 허용.
 *
 * 기존 {@link com.example.payment.domain.service.CancelAuthorizer} 와 동일하게
 * {@link CancelNotAuthorizedException}(FORBIDDEN_PAYMENT, 403) 을 재사용한다 — 신규 예외 타입 없음.
 * id 파싱은 {@code CancelAuthorizationService.parseLong} 과 동일 idiom: malformed/null → 인가 실패.
 */
public class ApprovalAuthorizer {

    public void authorizeDecision(AuthenticatedUser user, long targetMerchantId) {
        String role = user.role();
        if ("ADMIN".equals(role)) {
            return;
        }
        Long merchantId = parseLong(user.merchantId());
        if ("MERCHANT".equals(role) && merchantId != null && merchantId.equals(targetMerchantId)) {
            return;
        }
        throw new CancelNotAuthorizedException();
    }

    public void authorizeRequest(AuthenticatedUser user, long paymentOwnerUserId) {
        Long userId = parseLong(user.userId());
        if (userId != null && userId.equals(paymentOwnerUserId)) {
            return;
        }
        throw new CancelNotAuthorizedException();
    }

    private Long parseLong(String raw) {
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
