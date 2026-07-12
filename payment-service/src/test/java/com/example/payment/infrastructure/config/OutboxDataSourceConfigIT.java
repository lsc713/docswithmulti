package com.example.payment.infrastructure.config;

import com.example.payment.PaymentServiceApplication;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.application.interfaces.RiskManagementPort;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = PaymentServiceApplication.class, properties = "cancel.publish.mode=OUTBOX")
@DisplayName("OutboxDataSourceConfig: OUTBOX 전용 풀/템플릿 배선")
class OutboxDataSourceConfigIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // OUTBOX 컨텍스트 기동용 스텁 (ProcessingRecoveryOutboxIT과 동일한 MockitoBean 셋)
    @MockitoBean PgCancelPort pgCancelPort;
    @MockitoBean RiskManagementPort riskManagementPort;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean RedissonClient redissonClient;

    @Autowired DataSource dataSource;                                  // @Primary 메인
    @Autowired @Qualifier("cancelOutboxDataSource") HikariDataSource cancelOutboxDataSource;
    @Autowired NamedParameterJdbcTemplate cancelOutboxJdbcTemplate;

    @Test
    @DisplayName("메인 DataSource와 전용 풀(pool=2)이 각각 뜨고 격리된다")
    void beans_wired() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(cancelOutboxDataSource.getMaximumPoolSize()).isEqualTo(2);
        assertThat(cancelOutboxJdbcTemplate).isNotNull();
        assertThat(cancelOutboxDataSource).isNotSameAs(dataSource); // 격리

        // 메인 풀 Hikari 프로퍼티 바인딩 검증
        // initialization-fail-timeout: -1 은 DB 미기동 시 재시도를 위해 운영상 필수
        HikariDataSource main = (HikariDataSource) dataSource;
        assertThat(main.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(main.getConnectionTimeout()).isEqualTo(30000L);
    }
}
