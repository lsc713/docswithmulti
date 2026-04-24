package com.example.order.application.usecase;

import java.util.List;

public interface ProcessCancelledItemsUseCase {

    void execute(Command command);

    record Command(String cancelRequestId, List<Long> cancelledOrderItemIds) {}
}
