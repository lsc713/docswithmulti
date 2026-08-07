package com.example.settlement.infrastructure.config;

import com.example.settlement.application.interfaces.MerchantPayoutAccountRepository;
import com.example.settlement.application.interfaces.MerchantReserveConfigRepository;
import com.example.settlement.application.interfaces.MerchantSettlementConfigRepository;
import com.example.settlement.application.interfaces.PayoutRepository;
import com.example.settlement.application.interfaces.ProcessedSettlementEventRepository;
import com.example.settlement.application.interfaces.ReserveRepository;
import com.example.settlement.application.interfaces.SettlementLineRepository;
import com.example.settlement.application.interfaces.SettlementRepository;
import com.example.settlement.infrastructure.persistence.MerchantPayoutAccountJpaRepository;
import com.example.settlement.infrastructure.persistence.MerchantPayoutAccountRepositoryImpl;
import com.example.settlement.infrastructure.persistence.MerchantReserveConfigJpaRepository;
import com.example.settlement.infrastructure.persistence.MerchantReserveConfigRepositoryImpl;
import com.example.settlement.infrastructure.persistence.MerchantSettlementConfigJpaRepository;
import com.example.settlement.infrastructure.persistence.MerchantSettlementConfigRepositoryImpl;
import com.example.settlement.infrastructure.persistence.PayoutJpaRepository;
import com.example.settlement.infrastructure.persistence.PayoutRepositoryImpl;
import com.example.settlement.infrastructure.persistence.ReserveJpaRepository;
import com.example.settlement.infrastructure.persistence.ReserveRepositoryImpl;
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

    @Bean
    public MerchantSettlementConfigRepository merchantSettlementConfigRepository(
        MerchantSettlementConfigJpaRepository jpa) {
        return new MerchantSettlementConfigRepositoryImpl(jpa);
    }

    @Bean
    public PayoutRepository payoutRepository(PayoutJpaRepository jpa) {
        return new PayoutRepositoryImpl(jpa);
    }

    @Bean
    public MerchantPayoutAccountRepository merchantPayoutAccountRepository(
        MerchantPayoutAccountJpaRepository jpa) {
        return new MerchantPayoutAccountRepositoryImpl(jpa);
    }

    @Bean
    public MerchantReserveConfigRepository merchantReserveConfigRepository(
        MerchantReserveConfigJpaRepository jpa) {
        return new MerchantReserveConfigRepositoryImpl(jpa);
    }

    @Bean
    public ReserveRepository reserveRepository(ReserveJpaRepository jpa) {
        return new ReserveRepositoryImpl(jpa);
    }
}
