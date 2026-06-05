package com.example.product.application.usecase;

import java.util.List;

public interface StockRestoreUseCase {

    record CancelledItem(long skuId, int quantity) {}

    record Command(long cancelRequestId, List<CancelledItem> items) {}

    void execute(Command command);
}
