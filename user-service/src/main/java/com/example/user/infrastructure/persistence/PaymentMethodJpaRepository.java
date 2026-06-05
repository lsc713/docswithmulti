package com.example.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodJpaRepository extends JpaRepository<PaymentMethodJpaEntity, Long> {
    List<PaymentMethodJpaEntity> findAllByUserId(Long userId);
    Optional<PaymentMethodJpaEntity> findByIdAndUserId(Long id, Long userId);
}
