package com.example.product.infrastructure.config;

import com.example.product.application.interfaces.*;
import com.example.product.infrastructure.persistence.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.product.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public CategoryRepository categoryRepository(CategoryJpaRepository jpaRepository) {
        return new CategoryRepositoryImpl(jpaRepository);
    }

    @Bean
    public ProductRepository productRepository(ProductJpaRepository jpaRepository) {
        return new ProductRepositoryImpl(jpaRepository);
    }

    @Bean
    public ProductVersionRepository productVersionRepository(ProductVersionJpaRepository jpaRepository) {
        return new ProductVersionRepositoryImpl(jpaRepository);
    }

    @Bean
    public ProductSkuRepository productSkuRepository(ProductSkuJpaRepository jpaRepository) {
        return new ProductSkuRepositoryImpl(jpaRepository);
    }

    @Bean
    public ProductStockRepository productStockRepository(ProductStockJpaRepository jpaRepository) {
        return new ProductStockRepositoryImpl(jpaRepository);
    }

    @Bean
    public ProcessedStockEventRepository processedStockEventRepository(ProcessedStockEventJpaRepository jpaRepository) {
        return new ProcessedStockEventRepositoryImpl(jpaRepository);
    }
}
