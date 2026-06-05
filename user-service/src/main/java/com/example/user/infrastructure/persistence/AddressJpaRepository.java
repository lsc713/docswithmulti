package com.example.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AddressJpaRepository extends JpaRepository<AddressJpaEntity, Long> {
    List<AddressJpaEntity> findAllByUserId(Long userId);
    Optional<AddressJpaEntity> findByIdAndUserId(Long id, Long userId);
}
