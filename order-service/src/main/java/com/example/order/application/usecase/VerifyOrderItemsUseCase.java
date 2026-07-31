package com.example.order.application.usecase;

import java.util.List;

public interface VerifyOrderItemsUseCase {
    record Result(long orderId) {}
    Result verify(List<Long> orderItemIds, long requesterUserId);
}
