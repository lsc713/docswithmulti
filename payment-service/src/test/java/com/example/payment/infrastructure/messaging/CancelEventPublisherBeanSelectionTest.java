package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.CancelEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모드별 정확히 하나의 CancelEventPublisher 빈 활성 검증.
 * DB/Redis/Kafka 자동구성은 test application.yml 에서 제외됨.
 * KafkaTemplate 은 @MockitoBean 으로 대체.
 */
class CancelEventPublisherBeanSelectionTest {

    @SpringBootTest(
        classes = {InlineCancelEventPublisher.class, OutboxCancelEventPublisher.class, InlineAsyncCancelEventPublisher.class},
        properties = {"cancel.publish.mode=INLINE", "kafka.topic.payment-cancelled=payment.cancelled"}
    )
    @DisplayName("INLINE 모드: InlineCancelEventPublisher 빈 활성")
    static class InlineModeTest {
        @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
        @MockitoBean CancelEventOutboxRepository outboxRepository;
        @Autowired CancelEventPublisher publisher;

        @Test
        void inline_mode_activates_inline_publisher() {
            assertThat(publisher).isInstanceOf(InlineCancelEventPublisher.class);
        }
    }

    @SpringBootTest(
        classes = {InlineCancelEventPublisher.class, OutboxCancelEventPublisher.class, InlineAsyncCancelEventPublisher.class},
        properties = {"cancel.publish.mode=OUTBOX", "kafka.topic.payment-cancelled=payment.cancelled"}
    )
    @DisplayName("OUTBOX 모드: OutboxCancelEventPublisher 빈 활성")
    static class OutboxModeTest {
        @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
        @MockitoBean CancelEventOutboxRepository outboxRepository;
        @Autowired CancelEventPublisher publisher;

        @Test
        void outbox_mode_activates_outbox_publisher() {
            assertThat(publisher).isInstanceOf(OutboxCancelEventPublisher.class);
        }
    }

    @SpringBootTest(
        classes = {InlineCancelEventPublisher.class, OutboxCancelEventPublisher.class, InlineAsyncCancelEventPublisher.class},
        properties = {"cancel.publish.mode=INLINE_ASYNC", "kafka.topic.payment-cancelled=payment.cancelled"}
    )
    @DisplayName("INLINE_ASYNC 모드: InlineAsyncCancelEventPublisher 빈 활성")
    static class InlineAsyncModeTest {
        @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
        @MockitoBean CancelEventOutboxRepository outboxRepository;
        @Autowired CancelEventPublisher publisher;

        @Test
        void inline_async_mode_activates_inline_async_publisher() {
            assertThat(publisher).isInstanceOf(InlineAsyncCancelEventPublisher.class);
        }
    }
}
