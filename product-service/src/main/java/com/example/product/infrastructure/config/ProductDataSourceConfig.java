package com.example.product.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
public class ProductDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "primaryDataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource primaryDataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "product.datasource.replica", name = "enabled", havingValue = "true")
    @ConfigurationProperties("product.datasource.replica")
    DataSourceProperties replicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "replicaDataSource")
    @ConditionalOnProperty(prefix = "product.datasource.replica", name = "enabled", havingValue = "true")
    @ConfigurationProperties("product.datasource.replica.hikari")
    HikariDataSource replicaDataSource(
            @Qualifier("replicaDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "dataSource")
    @Primary
    DataSource dataSource(
            @Qualifier("primaryDataSource") DataSource primaryDataSource,
            @Qualifier("replicaDataSource") ObjectProvider<DataSource> replicaDataSource) {
        DataSource replica = replicaDataSource.getIfAvailable();
        ReplicaRoutingDataSource routing = new ReplicaRoutingDataSource();
        routing.setTargetDataSources(Map.of(
                ReplicaRoute.PRIMARY, primaryDataSource,
                ReplicaRoute.REPLICA, replica == null ? primaryDataSource : replica));
        routing.setDefaultTargetDataSource(primaryDataSource);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }
}
