package com.example.product.application.service;

import com.example.product.application.usecase.ProcessCancelledStockUseCase;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 취소 이벤트 → SKU 재고 복원 (RST-02).
 *
 * <p>Phase 1 의 원자 상태전이 {@link StockService#release}를 재사용해 위임한다.
 * release 는 releaseIfReserved 조건부 전이(affected=1 일 때만 복원)로 over-release 불가·멱등.
 */
@Service
public class ProcessCancelledStockService implements ProcessCancelledStockUseCase {

    private final StockService stockService;

    public ProcessCancelledStockService(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public void execute(Command command) {
        List<StockService.ReserveItem> items = command.items().stream()
            .map(i -> new StockService.ReserveItem(i.skuId(), i.qty()))
            .toList();
        stockService.release(command.paymentKey(), items);
    }
}
