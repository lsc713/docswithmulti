package com.example.payment.application.authz;

import com.example.payment.common.exception.domain.CancelNotAuthorizedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalAuthorizerTest {
    private final ApprovalAuthorizer authz = new ApprovalAuthorizer();

    @Test void admin_can_decide_any() {
        authz.authorizeDecision(new AuthenticatedUser("1", "ADMIN", null), 999L); // no throw
    }
    @Test void merchant_can_decide_own_merchant() {
        authz.authorizeDecision(new AuthenticatedUser("1", "MERCHANT", "42"), 42L); // no throw
    }
    @Test void merchant_cannot_decide_other_merchant() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeDecision(new AuthenticatedUser("1", "MERCHANT", "42"), 7L));
    }
    @Test void user_cannot_decide() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeDecision(new AuthenticatedUser("5", "USER", null), 42L));
    }
    @Test void merchant_with_malformed_merchantId_cannot_decide() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeDecision(new AuthenticatedUser("1", "MERCHANT", "not-a-number"), 42L));
    }
    @Test void null_role_cannot_decide() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeDecision(new AuthenticatedUser("1", null, null), 42L));
    }
    @Test void merchant_with_null_merchantId_cannot_decide() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeDecision(new AuthenticatedUser("1", "MERCHANT", null), 42L));
    }
    @Test void request_owner_ok() {
        authz.authorizeRequest(new AuthenticatedUser("7", "USER", null), 7L); // no throw
    }
    @Test void request_non_owner_rejected() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeRequest(new AuthenticatedUser("8", "USER", null), 7L));
    }
    @Test void request_malformed_userId_rejected() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeRequest(new AuthenticatedUser("not-a-number", "USER", null), 7L));
    }
    @Test void request_null_userId_rejected() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeRequest(new AuthenticatedUser(null, "USER", null), 7L));
    }
    @Test void request_blank_userId_rejected() {
        assertThrows(CancelNotAuthorizedException.class, () ->
            authz.authorizeRequest(new AuthenticatedUser("", "USER", null), 7L));
    }
}
