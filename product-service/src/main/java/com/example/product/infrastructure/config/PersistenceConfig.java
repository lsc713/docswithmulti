package com.example.product.infrastructure.config;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProcessedCancelEventRepository;
import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.infrastructure.persistence.*;
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
@EnableJpaRepositories(basePackages = "com.example.product.infrastructure.persistence")
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
    public ProductRepository productRepository(ProductJpaRepository jpa) {
        return new ProductRepositoryImpl(jpa);
    }

    @Bean
    public CategoryRepository categoryRepository(CategoryJpaRepository jpa) {
        return new CategoryRepositoryImpl(jpa);
    }

    @Bean
    public ProductSkuRepository productSkuRepository(ProductSkuJpaRepository jpa) {
        return new ProductSkuRepositoryImpl(jpa);
    }

    @Bean
    public ProductStockRepository productStockRepository(ProductStockJpaRepository jpa) {
        return new ProductStockRepositoryImpl(jpa);
    }

    @Bean
    public StockReservationRepository stockReservationRepository(StockReservationJpaRepository jpa) {
        return new StockReservationRepositoryImpl(jpa);
    }

    @Bean
    public ProcessedCancelEventRepository processedCancelEventRepository(ProcessedCancelEventJpaRepository jpa) {
        return new ProcessedCancelEventRepositoryImpl(jpa);
    }
}
