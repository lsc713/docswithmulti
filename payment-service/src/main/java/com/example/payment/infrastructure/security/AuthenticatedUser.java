package com.example.payment.infrastructure.security;

import com.example.payment.domain.entity.Payment;
import com.example.payment.common.exception.domain.CancelNotAuthorizedException;

public record AuthenticatedUser(long userId, String role, Long merchantId) {

    public void validateCancelAuthorization(Payment payment) {
        if ("ADMIN".equals(role)) return;
        if ("MERCHANT".equals(role) && merchantId != null
                && merchantId == payment.getMerchantId()) return;
        if ("USER".equals(role) && userId == payment.getUserId()) return;

        throw new CancelNotAuthorizedException();
    }
}
