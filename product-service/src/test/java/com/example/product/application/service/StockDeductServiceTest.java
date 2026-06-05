package com.example.product.application.service;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.usecase.StockDeductUseCase;
import com.example.product.common.exception.application.SkuNotFoundException;
import com.example.product.common.exception.domain.InsufficientStockException;
import com.example.product.domain.entity.ProductStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StockDeductServiceTest {

    @Mock
    private ProductStockRepository productStockRepository;

    private StockDeductService stockDeductService;

    @BeforeEach
    void setUp() {
        stockDeductService = new StockDeductService(productStockRepository);
    }

    @Test
    @DisplayName("정상 재고 차감 - 잔여 재고 반환")
    void deductNormal() {
        // given
        ProductStock stock = ProductStock.reconstruct(1L, 100L, 50, LocalDateTime.now());
        given(productStockRepository.findBySkuIdForUpdate(100L)).willReturn(Optional.of(stock));
        given(productStockRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        StockDeductUseCase.Command command = new StockDeductUseCase.Command(100L, 10);

        // when
        StockDeductUseCase.Result result = stockDeductService.execute(command);

        // then
        assertThat(result.skuId()).isEqualTo(100L);
        assertThat(result.remainingQuantity()).isEqualTo(40);
        verify(productStockRepository).save(any());
    }

    @Test
    @DisplayName("재고 부족 시 InsufficientStockException 발생")
    void deductInsufficientStock() {
        // given
        ProductStock stock = ProductStock.reconstruct(1L, 100L, 5, LocalDateTime.now());
        given(productStockRepository.findBySkuIdForUpdate(100L)).willReturn(Optional.of(stock));

        StockDeductUseCase.Command command = new StockDeductUseCase.Command(100L, 10);

        // when & then
        assertThatThrownBy(() -> stockDeductService.execute(command))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("SKU가 없으면 SkuNotFoundException 발생")
    void deductSkuNotFound() {
        // given
        given(productStockRepository.findBySkuIdForUpdate(999L)).willReturn(Optional.empty());

        StockDeductUseCase.Command command = new StockDeductUseCase.Command(999L, 5);

        // when & then
        assertThatThrownBy(() -> stockDeductService.execute(command))
                .isInstanceOf(SkuNotFoundException.class);
    }
}
