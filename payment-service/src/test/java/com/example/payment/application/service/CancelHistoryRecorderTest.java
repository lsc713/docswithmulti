package com.example.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.payment.application.interfaces.CancelHistoryEntry;
import com.example.payment.application.interfaces.CancelRequestHistoryRepository;
import com.example.payment.domain.entity.CancelStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CancelHistoryRecorderTest {

    @Mock CancelRequestHistoryRepository repository;
    @Captor ArgumentCaptor<List<CancelHistoryEntry>> captor;

    @Test
    void flush_writes_buffered_entries_once_then_clears() {
        CancelHistoryRecorder recorder = new CancelHistoryRecorder(repository);
        recorder.add(1L, CancelStatus.PENDING, null);
        recorder.add(1L, CancelStatus.PROCESSING, null);
        recorder.add(1L, CancelStatus.COMPLETED, null);

        recorder.flush();

        verify(repository, times(1)).recordAll(captor.capture());
        assertThat(captor.getValue()).extracting(CancelHistoryEntry::status)
            .containsExactly(CancelStatus.PENDING, CancelStatus.PROCESSING, CancelStatus.COMPLETED);

        // 두 번째 flush는 버퍼가 비어 recordAll을 호출하지 않는다 (버퍼 정리 확인)
        recorder.flush();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void flush_with_empty_buffer_does_not_call_repository() {
        CancelHistoryRecorder recorder = new CancelHistoryRecorder(repository);
        recorder.flush();
        verifyNoInteractions(repository);
    }

    @Test
    void flush_swallows_repository_exception() {
        CancelHistoryRecorder recorder = new CancelHistoryRecorder(repository);
        doThrow(new RuntimeException("db down")).when(repository).recordAll(anyList());
        recorder.add(1L, CancelStatus.PENDING, null);

        recorder.flush(); // 예외 전파 없이 반환해야 한다 (best-effort)

        // 버퍼는 정리됐다 — 이후 flush는 no-op
        recorder.flush();
        verify(repository, times(1)).recordAll(anyList());
    }

    @Test
    void buffers_are_isolated_per_thread() throws Exception {
        CancelHistoryRecorder recorder = new CancelHistoryRecorder(repository);
        recorder.add(1L, CancelStatus.PENDING, null);

        Thread other = new Thread(() -> recorder.add(2L, CancelStatus.COMPLETED, null));
        other.start();
        other.join();

        recorder.flush(); // 현재 스레드 버퍼(1건)만 flush
        verify(repository).recordAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).cancelRequestId()).isEqualTo(1L);
    }
}
