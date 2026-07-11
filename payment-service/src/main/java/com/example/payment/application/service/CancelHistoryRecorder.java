package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelHistoryEntry;
import com.example.payment.application.interfaces.CancelRequestHistoryRepository;
import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 취소 1건 동안 이력을 ThreadLocal 버퍼에 모아 종료 시 한 번에 기록한다(커밋 1개).
 * add는 전이 순간의 시각을 캡처만 하고(메모리), flush가 REQUIRES_NEW 배치 INSERT를 위임한다.
 * 실패는 삼킨다(best-effort) — 비즈니스에 영향 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelHistoryRecorder {

    private final CancelRequestHistoryRepository historyRepository;
    private final ThreadLocal<List<CancelHistoryEntry>> buffer =
        ThreadLocal.withInitial(ArrayList::new);

    public void add(long cancelRequestId, CancelStatus status, String reason) {
        buffer.get().add(new CancelHistoryEntry(cancelRequestId, status, reason, Instant.now()));
    }

    public void flush() {
        List<CancelHistoryEntry> entries = buffer.get();
        try {
            if (!entries.isEmpty()) {
                historyRepository.recordAll(List.copyOf(entries));
            }
        } catch (Exception e) {
            log.warn("이력 배치 기록 실패 (비즈니스 영향 없음). count={}", entries.size(), e);
        } finally {
            buffer.remove();
        }
    }
}
