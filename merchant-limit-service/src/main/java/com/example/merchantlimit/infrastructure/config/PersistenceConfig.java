package com.example.merchantlimit.infrastructure.config;

import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.infrastructure.persistence.MerchantCancelLimitJpaRepository;
import com.example.merchantlimit.infrastructure.persistence.MerchantCancelLimitRepositoryImpl;
import com.example.merchantlimit.infrastructure.persistence.MerchantJpaRepository;
import com.example.merchantlimit.infrastructure.persistence.MerchantRepositoryImpl;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.merchantlimit.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public MerchantRepository merchantRepository(MerchantJpaRepository jpa) {
        return new MerchantRepositoryImpl(jpa);
    }

    @Bean
    public MerchantCancelLimitRepository merchantCancelLimitRepository(
        MerchantCancelLimitJpaRepository jpa) {
        return new MerchantCancelLimitRepositoryImpl(jpa);
    }
}
