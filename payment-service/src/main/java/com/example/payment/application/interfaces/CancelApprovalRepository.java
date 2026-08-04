package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import java.util.List;
import java.util.Optional;

public interface CancelApprovalRepository {
    CancelApproval save(CancelApproval approval);
    Optional<CancelApproval> findById(long id);
    Optional<CancelApproval> findActiveRequestedByPaymentId(long paymentId);
    List<CancelApproval> findByStatus(CancelApprovalStatus status);
}
