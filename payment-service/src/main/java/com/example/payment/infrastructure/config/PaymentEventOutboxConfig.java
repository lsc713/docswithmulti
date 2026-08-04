package com.example.payment.infrastructure.config;

import com.example.payment.application.interfaces.PaymentEventOutboxRepository;
import com.example.payment.infrastructure.persistence.PaymentEventOutboxJpaRepository;
import com.example.payment.infrastructure.persistence.PaymentEventOutboxRepositoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * payment.completed 아웃박스 리포지토리 빈 배선 (메인 datasource JPA 위임).
 * cancel 아웃박스 datasource/풀 배선(OutboxDataSourceConfig)과 무관 — 조건 없이 항상 활성
 * (생성 경로가 cancel.publish.mode 와 무관하게 항상 아웃박스 INSERT 하므로).
 */
@Configuration
public class PaymentEventOutboxConfig {

    @Bean
    public PaymentEventOutboxRepository paymentEventOutboxRepository(
            PaymentEventOutboxJpaRepository jpaRepository) {
        return new PaymentEventOutboxRepositoryImpl(jpaRepository);
    }
}
