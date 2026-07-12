package com.example.payment.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 메인 DataSource를 명시적으로 정의(Boot auto-config 대체)하고,
 * OUTBOX 모드에서만 폴러 배수 전용 소형 풀 + JdbcTemplate 을 추가한다.
 * 2번째 DataSource 빈 추가 시 Boot가 메인을 백오프하므로 메인도 여기서 @Primary 로 정의.
 */
@Configuration
public class OutboxDataSourceConfig {

    // ── 메인 DataSource (기존 auto-config 대체, 바인딩 동일) ──
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder()
            .type(HikariDataSource.class).build();
    }

    // ── OUTBOX 전용 폴러 풀 (같은 DB, 소형) ──
    @Bean
    @ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
    public HikariDataSource cancelOutboxDataSource(
            DataSourceProperties dataSourceProperties,
            OutboxPoolProperties poolProperties) {
        // 메인 props(url/user/pass) 재사용 → 같은 payment_db, hikari 블록만 별도
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dataSourceProperties.determineUrl());
        config.setUsername(dataSourceProperties.determineUsername());
        config.setPassword(dataSourceProperties.determinePassword());
        config.setDriverClassName(dataSourceProperties.determineDriverClassName());
        config.setMaximumPoolSize(poolProperties.getMaximumPoolSize());
        config.setConnectionTimeout(poolProperties.getConnectionTimeout());
        config.setPoolName("CancelOutboxPool");
        return new HikariDataSource(config);
    }

    @Bean
    @ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
    public NamedParameterJdbcTemplate cancelOutboxJdbcTemplate(HikariDataSource cancelOutboxDataSource) {
        return new NamedParameterJdbcTemplate(cancelOutboxDataSource);
    }

    /**
     * OUTBOX 전용 풀 Hikari 설정 프로퍼티 (cancel.outbox.datasource.hikari.*)
     */
    @Bean
    @ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
    @ConfigurationProperties("cancel.outbox.datasource.hikari")
    public OutboxPoolProperties outboxPoolProperties() {
        return new OutboxPoolProperties();
    }

    public static class OutboxPoolProperties {
        private int maximumPoolSize = 2;
        private long connectionTimeout = 5000;

        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    }
}
