package com.example.payment.integration;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.application.service.PaymentAttemptTxWriter;
import com.example.payment.domain.entity.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "cancel.publish.mode=INLINE")
@Testcontainers
class PaymentAttemptTxWriterIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean RedissonClient redissonClient;
    @Autowired PaymentAttemptTxWriter writer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void pending_attempt_is_unique_per_order_and_completion_writes_one_outbox() {
        String requestId = "4d36e967-e325-11ce-bfc1-08002be10318";
        writer.prepare(requestId, command(), BigDecimal.valueOf(20_000), 77L);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM payment WHERE payment_request_id = ?", String.class, requestId))
            .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
            "SELECT payment_key FROM payment WHERE payment_request_id = ?", String.class, requestId))
            .isNull();

        assertThatThrownBy(() -> writer.prepare(
            "5d36e967-e325-11ce-bfc1-08002be10318", command(), BigDecimal.valueOf(20_000), 77L))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(writer.attach(requestId, 42L, "toss_payment_key").shouldConfirm()).isTrue();
        assertThat(writer.complete(requestId).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(writer.complete(requestId).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM payment_event_outbox WHERE payment_key = 'toss_payment_key'",
            Integer.class)).isEqualTo(1);
    }

    private CreatePaymentCommand command() {
        return new CreatePaymentCommand(1L, 42L, "NORMAL", 90, List.of(
            new CreatePaymentCommand.Item(
                10L, 200L, "상품", BigDecimal.valueOf(20_000), 500L, 2)));
    }
}
