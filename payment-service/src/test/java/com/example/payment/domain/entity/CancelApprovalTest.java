package com.example.payment.domain.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CancelApprovalTest {
    private CancelApproval requested() {
        return CancelApproval.request(10L, "pay_key_1", 7L, "단순 변심");
    }

    @Test
    void request_starts_in_REQUESTED() {
        CancelApproval a = requested();
        assertEquals(CancelApprovalStatus.REQUESTED, a.getStatus());
        assertEquals(7L, a.getRequesterUserId());
        assertEquals("단순 변심", a.getReason());
        assertNull(a.getCancelRequestId());
    }

    @Test
    void approve_transitions_to_APPROVED_and_links_cancelRequest() {
        CancelApproval a = requested();
        a.approve(99L, "ADMIN", 555L);
        assertEquals(CancelApprovalStatus.APPROVED, a.getStatus());
        assertEquals(99L, a.getDecidedByUserId());
        assertEquals("ADMIN", a.getDecidedRole());
        assertEquals(555L, a.getCancelRequestId());
    }

    @Test
    void reject_transitions_to_REJECTED_with_reason() {
        CancelApproval a = requested();
        a.reject(99L, "MERCHANT", "재고 이미 출고");
        assertEquals(CancelApprovalStatus.REJECTED, a.getStatus());
        assertEquals("재고 이미 출고", a.getDecisionReason());
    }

    @Test
    void cannot_re_decide_after_terminal() {
        CancelApproval a = requested();
        a.approve(99L, "ADMIN", 555L);
        assertThrows(IllegalStateException.class, () -> a.reject(1L, "ADMIN", "x"));
        assertThrows(IllegalStateException.class, () -> a.approve(1L, "ADMIN", 1L));
    }
}
