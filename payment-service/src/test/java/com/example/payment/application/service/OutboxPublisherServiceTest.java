package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.OutboxEventPublisher;
import com.example.payment.application.interfaces.PendingOutbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private CancelEventOutboxRepository outboxRepository;

    @Mock
    private OutboxEventPublisher outboxEventPublisher;

    @InjectMocks
    private OutboxPublisherService service;

    @Test
    void PENDING_건_Kafka_발행_후_markPublished_호출() throws Exception {
        // given
        var outbox = new PendingOutbox(1L, "{\"cancelRequestId\":1}");
        given(outboxRepository.findPendingBatch(1000)).willReturn(List.of(outbox));

        // when
        service.publish();

        // then
        verify(outboxEventPublisher).publish(1L, "{\"cancelRequestId\":1}");
        verify(outboxRepository).markPublished(1L);
    }

    @Test
    void Kafka_발행_실패_시_해당_건_skip_나머지_정상_처리() throws Exception {
        // given
        var fail = new PendingOutbox(1L, "payload1");
        var success = new PendingOutbox(2L, "payload2");
        given(outboxRepository.findPendingBatch(1000)).willReturn(List.of(fail, success));
        willThrow(new RuntimeException("Kafka 연결 오류"))
            .given(outboxEventPublisher).publish(1L, "payload1");

        // when — 예외가 밖으로 전파되지 않아야 함
        service.publish();

        // then
        verify(outboxEventPublisher).publish(1L, "payload1");  // 시도는 했음
        verify(outboxRepository, never()).markPublished(1L);   // 실패 건은 PENDING 유지
        verify(outboxEventPublisher).publish(2L, "payload2");  // 나머지는 계속 처리
        verify(outboxRepository).markPublished(2L);
    }

    @Test
    void PENDING_건_없으면_아무_동작_안함() throws Exception {
        // given
        given(outboxRepository.findPendingBatch(1000)).willReturn(List.of());

        // when
        service.publish();

        // then
        verifyNoInteractions(outboxEventPublisher);
        verify(outboxRepository, never()).markPublished(anyLong());
    }
}
