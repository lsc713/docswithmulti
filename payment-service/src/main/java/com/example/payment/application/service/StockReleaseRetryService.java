package com.example.payment.application.service;

import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.application.interfaces.StockReleaseRetryRepository;
import com.example.payment.application.interfaces.StockReleaseRetryRepository.PendingRelease;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * stock_release_retry 의 PENDING 건을 순회하며 product release 보상을 재시도한다(RSV-03, D-P2-6).
 * CompensationRetryService 동형 — release는 paymentKey+sku 멱등이라 재시도 안전(이미 RELEASED면 product no-op).
 *
 * 재시도 정책: 최대 5회, 간격 attempt*60초, 5회 초과 시 status=FAILED 고정(수동 처리 필요).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReleaseRetryService {

    private static final int MAX_ATTEMPTS = 5;
    private static final TypeReference<List<ProductStockPort.Item>> ITEMS_TYPE = new TypeReference<>() {};
    // payment-service에는 ObjectMapper 빈이 없음(LongListConverter 관행) → 내부 인스턴스 사용(스레드 안전).
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StockReleaseRetryRepository stockReleaseRetryRepository;
    private final ProductStockPort productStockPort;

    public void retryAll() {
        LocalDateTime now = LocalDateTime.now();
        List<PendingRelease> due = stockReleaseRetryRepository.findDueForRetry(now);
        log.info("[stock-release-retry] 실행 now={} 대상={}건", now, due.size());
        if (due.isEmpty()) {
            return;
        }
        due.forEach(this::retryOne);
    }

    private void retryOne(PendingRelease pending) {
        int nextAttempt = pending.attemptCount() + 1;
        log.info("[stock-release-retry] 시도 #{} paymentKey={}", nextAttempt, pending.paymentKey());
        try {
            List<ProductStockPort.Item> items = OBJECT_MAPPER.readValue(pending.itemsJson(), ITEMS_TYPE);
            productStockPort.release(pending.paymentKey(), items);
            stockReleaseRetryRepository.markDone(pending.id());
            log.info("[stock-release-retry] 성공 paymentKey={}", pending.paymentKey());
        } catch (Exception e) {
            log.warn("[stock-release-retry] 실패 #{} paymentKey={}: {}",
                nextAttempt, pending.paymentKey(), e.getMessage());
            if (nextAttempt >= MAX_ATTEMPTS) {
                stockReleaseRetryRepository.exhaust(pending.id(), nextAttempt, e.getMessage());
                log.error("[stock-release-retry] 최대 시도({}) 초과 → 수동 처리 필요 paymentKey={}",
                    MAX_ATTEMPTS, pending.paymentKey());
            } else {
                LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds((long) nextAttempt * 60);
                stockReleaseRetryRepository.markRetryLater(
                    pending.id(), nextAttempt, nextRetryAt, e.getMessage());
            }
        }
    }
}
