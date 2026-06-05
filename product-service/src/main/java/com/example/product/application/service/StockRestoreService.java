package com.example.product.application.service;

import com.example.product.application.interfaces.ProcessedStockEventRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.usecase.StockRestoreUseCase;
import com.example.product.common.exception.application.SkuNotFoundException;
import com.example.product.domain.entity.ProcessedStockEvent;
import com.example.product.domain.entity.ProductStock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockRestoreService implements StockRestoreUseCase {

    private final ProductStockRepository productStockRepository;
    private final ProcessedStockEventRepository processedStockEventRepository;

    @Override
    @Transactional
    public void execute(Command command) {
        // Idempotency check - skip if already processed
        if (processedStockEventRepository.existsByCancelRequestId(command.cancelRequestId())) {
            return;
        }

        for (CancelledItem item : command.items()) {
            ProductStock stock = productStockRepository.findBySkuId(item.skuId())
                    .orElseThrow(() -> new SkuNotFoundException(item.skuId()));

            stock.restore(item.quantity());
            productStockRepository.save(stock);
        }

        ProcessedStockEvent event = ProcessedStockEvent.of(command.cancelRequestId());
        processedStockEventRepository.save(event);
    }
}
