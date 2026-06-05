package com.example.product.application.usecase;

public interface StockDeductUseCase {

    record Command(long skuId, int quantity) {}

    record Result(long skuId, int remainingQuantity) {}

    Result execute(Command command);
}
