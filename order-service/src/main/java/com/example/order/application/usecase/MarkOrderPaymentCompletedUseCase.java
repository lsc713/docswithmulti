package com.example.order.application.usecase;

public interface MarkOrderPaymentCompletedUseCase {

    record Command(long orderId) {}

    void execute(Command command);
}
