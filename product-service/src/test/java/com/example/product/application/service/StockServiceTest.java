package com.example.product.application.service;

import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ReservationStatus;
import com.example.product.domain.entity.StockReservation;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockServiceTest {

    @Test
    void reserveLoadsAllSkusInOneBatch() {
        ProductStockRepository stockRepository = mock(ProductStockRepository.class);
        StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
        ProductSkuRepository skuRepository = mock(ProductSkuRepository.class);
        StockService service = new StockService(
            stockRepository,
            reservationRepository,
            skuRepository,
            mock(ApplicationEventPublisher.class)
        );
        ProductSku sku1 = ProductSku.reconstruct(11L, 101L, "sku-1", "", 1_000L, Instant.EPOCH, Instant.EPOCH);
        ProductSku sku2 = ProductSku.reconstruct(12L, 102L, "sku-2", "", 2_000L, Instant.EPOCH, Instant.EPOCH);

        when(reservationRepository.upsertReserved("payment", 12L, 2, 2_000L)).thenReturn(1);
        when(reservationRepository.upsertReserved("payment", 11L, 1, 1_000L)).thenReturn(1);
        when(skuRepository.findAllByIdIn(List.of(12L, 11L))).thenReturn(List.of(sku1, sku2));
        when(stockRepository.tryReserveAll(List.of(
            new ProductStockRepository.Adjustment(11L, 1),
            new ProductStockRepository.Adjustment(12L, 2)
        ))).thenReturn(new int[]{1, 1});

        List<StockService.ReservedItem> result = service.reserve("payment", List.of(
            new StockService.ReserveItem(102L, 12L, 2),
            new StockService.ReserveItem(101L, 11L, 1)
        ));

        assertThat(result).containsExactly(
            new StockService.ReservedItem(12L, 102L, 2_000L, 2),
            new StockService.ReservedItem(11L, 101L, 1_000L, 1)
        );
        verify(skuRepository).findAllByIdIn(List.of(12L, 11L));
        verify(skuRepository, never()).findById(anyLong());
    }

    @Test
    void releaseLocksAndRestoresReservedItemsInBatches() {
        ProductStockRepository stockRepository = mock(ProductStockRepository.class);
        StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
        ProductSkuRepository skuRepository = mock(ProductSkuRepository.class);
        StockService service = new StockService(
            stockRepository,
            reservationRepository,
            skuRepository,
            mock(ApplicationEventPublisher.class)
        );
        ProductSku sku = ProductSku.reconstruct(11L, 101L, "sku-1", "", 1_000L, Instant.EPOCH, Instant.EPOCH);
        StockReservation reserved = StockReservation.reconstruct(
            1L, "payment", 11L, 3, 1_000L, ReservationStatus.RESERVED, Instant.EPOCH, Instant.EPOCH);

        when(reservationRepository.findAllByPaymentKeyAndSkuIdInForUpdate("payment", List.of(11L, 12L)))
            .thenReturn(List.of(reserved));
        when(skuRepository.findAllByIdIn(List.of(11L))).thenReturn(List.of(sku));
        when(reservationRepository.releaseAllReserved("payment", List.of(11L))).thenReturn(1);
        when(stockRepository.restoreAll(List.of(new ProductStockRepository.Adjustment(11L, 3))))
            .thenReturn(new int[]{1});

        service.release("payment", List.of(
            new StockService.ReserveItem(11L, 99),
            new StockService.ReserveItem(12L, 1)
        ));

        verify(reservationRepository)
            .findAllByPaymentKeyAndSkuIdInForUpdate("payment", List.of(11L, 12L));
        verify(reservationRepository).releaseAllReserved("payment", List.of(11L));
        verify(stockRepository).restoreAll(List.of(new ProductStockRepository.Adjustment(11L, 3)));
        verify(skuRepository).findAllByIdIn(List.of(11L));
        verify(skuRepository, never()).findById(anyLong());
    }
}
