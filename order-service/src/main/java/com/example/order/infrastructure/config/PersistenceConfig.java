package com.example.order.infrastructure.config;

import com.example.order.application.interfaces.CancelRestoreDlqRepository;
import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import com.example.order.application.service.ProcessCancelledItemsService;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.example.order.infrastructure.persistence.*;
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
@EnableJpaRepositories(basePackages = "com.example.order.infrastructure.persistence")
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
    public OrderRepository orderRepository(OrderJpaRepository jpa) {
        return new OrderRepositoryImpl(jpa);
    }

    @Bean
    public OrderItemRepository orderItemRepository(OrderItemJpaRepository jpa) {
        return new OrderItemRepositoryImpl(jpa);
    }

    @Bean
    public ProcessedCancelEventRepository processedCancelEventRepository(
        ProcessedCancelEventJpaRepository jpa) {
        return new ProcessedCancelEventRepositoryImpl(jpa);
    }

    @Bean
    public CancelRestoreDlqRepository cancelRestoreDlqRepository(CancelRestoreDlqJpaRepository jpa) {
        return new CancelRestoreDlqRepositoryImpl(jpa);
    }

    @Bean
    public ProcessCancelledItemsUseCase processCancelledItemsUseCase(
        OrderRepository orderRepository,
        OrderItemRepository orderItemRepository,
        ProcessedCancelEventRepository processedCancelEventRepository,
        TransactionTemplate transactionTemplate) {
        return new ProcessCancelledItemsService(
            orderRepository, orderItemRepository, processedCancelEventRepository, transactionTemplate);
    }
}
