package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxCancelEventPublisherTest {

    @Mock CancelEventOutboxRepository outboxRepository;
    @InjectMocks OutboxCancelEventPublisher publisher;

    @Test
    @DisplayName("publish는 outbox INSERT를 위임하고 Kafka 발행은 하지 않는다")
    void inserts_outbox() {
        publisher.publish(88L, "{\"cancelRequestId\":88}");
        verify(outboxRepository).insertPending(88L, "{\"cancelRequestId\":88}");
    }
}
