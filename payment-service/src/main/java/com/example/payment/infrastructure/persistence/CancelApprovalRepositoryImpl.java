package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelApprovalRepository;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import java.util.List;
import java.util.Optional;

public class CancelApprovalRepositoryImpl implements CancelApprovalRepository {

    private final CancelApprovalJpaRepository jpa;

    public CancelApprovalRepositoryImpl(CancelApprovalJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public CancelApproval save(CancelApproval a) {
        return jpa.save(CancelApprovalJpaEntity.fromDomain(a)).toDomain();
    }

    @Override
    public Optional<CancelApproval> findById(long id) {
        return jpa.findById(id).map(CancelApprovalJpaEntity::toDomain);
    }

    @Override
    public Optional<CancelApproval> findActiveRequestedByPaymentId(long paymentId) {
        return jpa.findFirstByPaymentIdAndStatus(paymentId, CancelApprovalStatus.REQUESTED.name())
                  .map(CancelApprovalJpaEntity::toDomain);
    }

    @Override
    public List<CancelApproval> findByStatus(CancelApprovalStatus status) {
        return jpa.findByStatus(status.name()).stream().map(CancelApprovalJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<CancelApproval> findLatestByPaymentId(long paymentId) {
        return jpa.findFirstByPaymentIdOrderByIdDesc(paymentId).map(CancelApprovalJpaEntity::toDomain);
    }
}
