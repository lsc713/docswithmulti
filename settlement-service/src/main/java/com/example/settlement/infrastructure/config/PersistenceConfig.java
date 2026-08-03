package com.example.settlement.infrastructure.config;

import com.example.settlement.application.interfaces.ProcessedSettlementEventRepository;
import com.example.settlement.application.interfaces.SettlementLineRepository;
import com.example.settlement.application.interfaces.SettlementRepository;
import com.example.settlement.infrastructure.persistence.ProcessedSettlementEventJpaRepository;
import com.example.settlement.infrastructure.persistence.ProcessedSettlementEventRepositoryImpl;
import com.example.settlement.infrastructure.persistence.SettlementJpaRepository;
import com.example.settlement.infrastructure.persistence.SettlementLineJpaRepository;
import com.example.settlement.infrastructure.persistence.SettlementLineRepositoryImpl;
import com.example.settlement.infrastructure.persistence.SettlementRepositoryImpl;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.settlement.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public SettlementRepository settlementRepository(SettlementJpaRepository jpa) {
        return new SettlementRepositoryImpl(jpa);
    }

    @Bean
    public SettlementLineRepository settlementLineRepository(SettlementLineJpaRepository jpa) {
        return new SettlementLineRepositoryImpl(jpa);
    }

    @Bean
    public ProcessedSettlementEventRepository processedSettlementEventRepository(
        ProcessedSettlementEventJpaRepository jpa) {
        return new ProcessedSettlementEventRepositoryImpl(jpa);
    }
}
