package com.example.payment.infrastructure.config;

import com.example.payment.application.interfaces.CancelApprovalRepository;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.infrastructure.persistence.CancelApprovalJpaRepository;
import com.example.payment.infrastructure.persistence.CancelApprovalRepositoryImpl;
import com.example.payment.infrastructure.persistence.CancelOutboxRedriveRepositoryImpl;
import com.example.payment.infrastructure.persistence.CancelRequestJpaRepository;
import com.example.payment.infrastructure.persistence.CancelRequestRepositoryImpl;
import com.example.payment.infrastructure.persistence.PaymentItemJpaRepository;
import com.example.payment.infrastructure.persistence.PaymentItemRepositoryImpl;
import com.example.payment.infrastructure.persistence.PaymentJpaRepository;
import com.example.payment.infrastructure.persistence.PaymentRepositoryImpl;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA/영속성 설정
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.payment.infrastructure.persistence")
public class PersistenceConfig {

    /**
     * 트랜잭션 관리자 설정
     */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    /**
     * PaymentRepository 구현체 주입
     */
    @Bean
    public PaymentRepository paymentRepository(PaymentJpaRepository jpaRepository) {
        return new PaymentRepositoryImpl(jpaRepository);
    }

    /**
     * PaymentItemRepository 구현체 주입
     */
    @Bean
    public PaymentItemRepository paymentItemRepository(PaymentItemJpaRepository jpaRepository) {
        return new PaymentItemRepositoryImpl(jpaRepository);
    }

    /**
     * CancelRequestRepository 구현체 주입
     */
    @Bean
    public CancelRequestRepository cancelRequestRepository(CancelRequestJpaRepository jpaRepository) {
        return new CancelRequestRepositoryImpl(jpaRepository);
    }

    /**
     * CancelApprovalRepository 구현체 주입
     */
    @Bean
    public CancelApprovalRepository cancelApprovalRepository(CancelApprovalJpaRepository jpaRepository) {
        return new CancelApprovalRepositoryImpl(jpaRepository);
    }

    @Bean
    public CancelOutboxRedriveRepository cancelOutboxRedriveRepository(
        @Qualifier("dataSource") DataSource dataSource
    ) {
        return new CancelOutboxRedriveRepositoryImpl(new NamedParameterJdbcTemplate(dataSource));
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CancelPeriodPolicy cancelPeriodPolicy(Clock clock) {
        return new CancelPeriodPolicy(clock);
    }

    @Bean
    public CancelDomainService cancelDomainService(CancelPeriodPolicy cancelPeriodPolicy) {
        return new CancelDomainService(cancelPeriodPolicy);
    }
}
