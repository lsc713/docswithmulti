package com.example.order.application.service;

import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.usecase.MarkOrderPaymentCompletedUseCase;
import com.example.order.domain.entity.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionTemplate;

@RequiredArgsConstructor
public class MarkOrderPaymentCompletedService implements MarkOrderPaymentCompletedUseCase {

    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void execute(Command command) {
        transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findByIdForUpdate(command.orderId())
                .orElseThrow(() -> new IllegalStateException(
                    "Order not found: orderId=" + command.orderId()));
            if (order.markPaymentCompleted()) {
                orderRepository.save(order);
            }
        });
    }
}
