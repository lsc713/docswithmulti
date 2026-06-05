package com.example.product.application.service;

import com.example.product.application.interfaces.ProcessedStockEventRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.usecase.StockRestoreUseCase;
import com.example.product.domain.entity.ProcessedStockEvent;
import com.example.product.domain.entity.ProductStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StockRestoreServiceTest {

    @Mock
    private ProductStockRepository productStockRepository;

    @Mock
    private ProcessedStockEventRepository processedStockEventRepository;

    private StockRestoreService stockRestoreService;

    @BeforeEach
    void setUp() {
        stockRestoreService = new StockRestoreService(productStockRepository, processedStockEventRepository);
    }

    @Test
    @DisplayName("정상 재고 복원 - 수량 증가 + 처리 이벤트 저장")
    void restoreNormal() {
        // given
        long cancelRequestId = 500L;
        ProductStock stock = ProductStock.reconstruct(1L, 100L, 30, LocalDateTime.now());

        given(processedStockEventRepository.existsByCancelRequestId(cancelRequestId)).willReturn(false);
        given(productStockRepository.findBySkuId(100L)).willReturn(Optional.of(stock));
        given(productStockRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(processedStockEventRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        StockRestoreUseCase.Command command = new StockRestoreUseCase.Command(
                cancelRequestId,
                List.of(new StockRestoreUseCase.CancelledItem(100L, 10))
        );

        // when
        stockRestoreService.execute(command);

        // then
        verify(productStockRepository).save(any());
        verify(processedStockEventRepository).save(any());
    }

    @Test
    @DisplayName("중복 cancelRequestId - 처리 건너뜀")
    void restoreDuplicateSkip() {
        // given
        long cancelRequestId = 500L;
        given(processedStockEventRepository.existsByCancelRequestId(cancelRequestId)).willReturn(true);

        StockRestoreUseCase.Command command = new StockRestoreUseCase.Command(
                cancelRequestId,
                List.of(new StockRestoreUseCase.CancelledItem(100L, 10))
        );

        // when
        stockRestoreService.execute(command);

        // then
        verify(productStockRepository, never()).save(any());
        verify(processedStockEventRepository, never()).save(any());
    }
}
