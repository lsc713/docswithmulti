package com.example.payment.application.service;

import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.application.interfaces.StockReleaseRetryRepository;
import com.example.payment.application.interfaces.StockReleaseRetryRepository.PendingRelease;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockReleaseRetryService (예약 해제 보상 재시도)")
class StockReleaseRetryServiceTest {

    @Mock StockReleaseRetryRepository stockReleaseRetryRepository;
    @Mock ProductStockPort productStockPort;

    private StockReleaseRetryService service;

    // ProductStockPort.Item(skuId, qty) 직렬화 형태
    private static final String ITEMS_JSON = "[{\"skuId\":500,\"qty\":2},{\"skuId\":501,\"qty\":1}]";

    @BeforeEach
    void setUp() {
        service = new StockReleaseRetryService(
            stockReleaseRetryRepository, productStockPort);
    }

    @Test
    @DisplayName("대상 건이 없으면 아무 작업도 하지 않는다")
    void noPending_noop() {
        when(stockReleaseRetryRepository.findDueForRetry(any())).thenReturn(List.of());

        service.retryAll();

        verify(productStockPort, never()).release(anyString(), any());
    }

    @Test
    @DisplayName("release 성공 → items 역직렬화 후 release 호출 + markDone")
    void releaseSucceeds_markDone() {
        PendingRelease pending = new PendingRelease(1L, "pay_a", ITEMS_JSON, 0);
        when(stockReleaseRetryRepository.findDueForRetry(any())).thenReturn(List.of(pending));

        service.retryAll();

        verify(productStockPort).release("pay_a",
            List.of(new ProductStockPort.Item(500L, 2), new ProductStockPort.Item(501L, 1)));
        verify(stockReleaseRetryRepository).markDone(1L);
        verify(stockReleaseRetryRepository, never()).markRetryLater(anyLong(), anyInt(), any(), any());
        verify(stockReleaseRetryRepository, never()).exhaust(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("release 실패 + 최대 시도 미만 → markRetryLater(미래 시각)")
    void releaseFails_belowMax_markRetryLater() {
        PendingRelease pending = new PendingRelease(2L, "pay_b", ITEMS_JSON, 2); // nextAttempt=3 < 5
        when(stockReleaseRetryRepository.findDueForRetry(any())).thenReturn(List.of(pending));
        doThrow(new RuntimeException("product 오류"))
            .when(productStockPort).release(anyString(), any());

        LocalDateTime before = LocalDateTime.now();
        service.retryAll();

        verify(stockReleaseRetryRepository, never()).markDone(anyLong());
        verify(stockReleaseRetryRepository, never()).exhaust(anyLong(), anyInt(), any());
        org.mockito.ArgumentCaptor<LocalDateTime> nextCaptor =
            org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(stockReleaseRetryRepository).markRetryLater(
            eq(2L), eq(3), nextCaptor.capture(), contains("product 오류"));
        assertTrue(nextCaptor.getValue().isAfter(before), "next_retry_at은 미래여야 한다");
    }

    @Test
    @DisplayName("release 실패 + 최대 시도(5회) 도달 → exhaust")
    void releaseFails_reachesMax_exhaust() {
        PendingRelease pending = new PendingRelease(3L, "pay_c", ITEMS_JSON, 4); // nextAttempt=5 >= MAX(5)
        when(stockReleaseRetryRepository.findDueForRetry(any())).thenReturn(List.of(pending));
        doThrow(new RuntimeException("최종 실패"))
            .when(productStockPort).release(anyString(), any());

        service.retryAll();

        verify(stockReleaseRetryRepository, never()).markDone(anyLong());
        verify(stockReleaseRetryRepository, never()).markRetryLater(anyLong(), anyInt(), any(), any());
        verify(stockReleaseRetryRepository).exhaust(eq(3L), eq(5), contains("최종 실패"));
    }
}
