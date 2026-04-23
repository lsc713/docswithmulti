package com.example.riskmanagement.infrastructure.config;

import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
import com.example.riskmanagement.infrastructure.persistence.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.riskmanagement.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public CancelLimitDomainService cancelLimitDomainService() {
        return new CancelLimitDomainService();
    }

    @Bean
    public MerchantCancelUsageRepository merchantCancelUsageRepository(
        MerchantCancelUsageJpaRepository jpa) {
        return new MerchantCancelUsageRepositoryImpl(jpa);
    }

    @Bean
    public CancelUsageHistoryRepository cancelUsageHistoryRepository(
        CancelUsageHistoryJpaRepository jpa) {
        return new CancelUsageHistoryRepositoryImpl(jpa);
    }

    @Bean
    public CancelUsageCompensationRepository cancelUsageCompensationRepository(
        CancelUsageCompensationJpaRepository jpa) {
        return new CancelUsageCompensationRepositoryImpl(jpa);
    }
}
