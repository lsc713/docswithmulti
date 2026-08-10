package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.CancelOutboxSourcePort;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class CancelOutboxSourceAdapterIT extends AbstractRepositoryTest {

    private static final long PAYMENT_ID = 9_100_001L;
    private static final long CANCEL_REQUEST_ID = 9_200_001L;

    @Autowired
    private CancelEventOutboxJpaRepository jpa;

    @Autowired
    private DataSource dataSource;

    private CancelEventOutboxRepository outboxRepository;
    private CancelOutboxSourcePort sourcePort;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        var adapter = new CancelEventOutboxRepositoryImpl(jpa, namedJdbc);
        outboxRepository = adapter;
        sourcePort = adapter;
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void loadsDeadOutboxWithCancelAndPaymentSnapshotWithoutMutation() throws Exception {
        seedCancelledPaymentAndCompletedRequest();
        String payload = """
            {"cancelRequestId":9200001,"paymentKey":"pay_redrive_source",
             "cancelledItems":[{"orderItemId":10,"skuId":8,"quantity":2}]}
            """;
        outboxRepository.insertPending(CANCEL_REQUEST_ID, payload);
        long outboxId = outboxRepository.findPendingBatch(10).stream()
            .filter(row -> row.cancelRequestId() == CANCEL_REQUEST_ID)
            .findFirst()
            .orElseThrow()
            .id();
        outboxRepository.markDead(outboxId, "broker unavailable");
        String storedPayload = jdbc.queryForObject(
            "SELECT CAST(payload AS CHAR) FROM cancel_event_outbox WHERE id = ?",
            String.class, outboxId);

        var source = sourcePort.findById(outboxId).orElseThrow();

        assertThat(source.outboxId()).isEqualTo(outboxId);
        assertThat(source.cancelRequestId()).isEqualTo(CANCEL_REQUEST_ID);
        assertThat(source.payload()).isEqualTo(storedPayload);
        var sourceJson = new ObjectMapper().readTree(source.payload());
        assertThat(sourceJson.path("cancelRequestId").asLong()).isEqualTo(CANCEL_REQUEST_ID);
        assertThat(sourceJson.path("paymentKey").asText()).isEqualTo("pay_redrive_source");
        assertThat(sourceJson.path("cancelledItems").get(0).path("orderItemId").asLong())
            .isEqualTo(10L);
        assertThat(source.outboxStatus()).isEqualTo("DEAD");
        assertThat(source.cancelStatus()).isEqualTo(CancelStatus.COMPLETED);
        assertThat(source.paymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM cancel_event_outbox WHERE id = ?", String.class, outboxId))
            .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject(
            "SELECT last_error FROM cancel_event_outbox WHERE id = ?", String.class, outboxId))
            .isEqualTo("broker unavailable");
    }

    @Test
    void returnsEmptyForUnknownOutbox() {
        assertThat(sourcePort.findById(Long.MAX_VALUE)).isEmpty();
    }

    private void seedCancelledPaymentAndCompletedRequest() {
        jdbc.update("""
            INSERT INTO payment
                (id, payment_key, merchant_id, user_id, pg_type, total_amount,
                 currency, status, order_id)
            VALUES (?, 'pay_redrive_source', 1, 7, 'CARD', 1000, 'KRW', 'CANCELLED', 100)
            """, PAYMENT_ID);
        jdbc.update("""
            INSERT INTO cancel_request
                (id, payment_id, request_hash, cancel_amount, cancel_reason, status,
                 completed_at, cancel_item_ids)
            VALUES (?, ?, 'redrive-source-hash', 1000, 'operator fixture', 'COMPLETED',
                    CURRENT_TIMESTAMP(3), JSON_ARRAY(1))
            """, CANCEL_REQUEST_ID, PAYMENT_ID);
    }
}
