package com.example.product.application.service;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.usecase.StockDeductUseCase;
import com.example.product.common.exception.application.SkuNotFoundException;
import com.example.product.domain.entity.ProductStock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockDeductService implements StockDeductUseCase {

    private final ProductStockRepository productStockRepository;

    @Override
    @Transactional
    public Result execute(Command command) {
        ProductStock stock = productStockRepository.findBySkuIdForUpdate(command.skuId())
                .orElseThrow(() -> new SkuNotFoundException(command.skuId()));

        stock.deduct(command.quantity());
        productStockRepository.save(stock);

        return new Result(command.skuId(), stock.getQuantity());
    }
}
