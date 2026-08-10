package com.example.payment.application.authz;

import com.example.payment.application.exception.CancelOutboxForbiddenException;
import com.example.payment.application.exception.InternalAuthenticationRequiredException;
import org.springframework.stereotype.Component;

@Component
public class InternalOperatorAccess {

    public void requireAdmin(String role, String operatorId) {
        if (role == null || role.isBlank()) {
            throw new InternalAuthenticationRequiredException();
        }
        if (!"ADMIN".equals(role) || operatorId == null || operatorId.isBlank()) {
            throw new CancelOutboxForbiddenException();
        }
    }
}
