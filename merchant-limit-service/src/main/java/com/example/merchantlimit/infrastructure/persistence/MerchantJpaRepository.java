package com.example.merchantlimit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface MerchantJpaRepository extends JpaRepository<MerchantJpaEntity, Long> {
    Optional<MerchantJpaEntity> findByMerchantKey(String merchantKey);
    boolean existsByMerchantKey(String merchantKey);
    Page<MerchantJpaEntity> findByStatus(String status, Pageable pageable);
}
