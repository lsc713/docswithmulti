package com.example.order.application.service;

import com.example.order.application.exception.OrderItemNotFoundException;
import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@RequiredArgsConstructor
public class ProcessCancelledItemsService implements ProcessCancelledItemsUseCase {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProcessedCancelEventRepository processedCancelEventRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void execute(Command command) {
        transactionTemplate.execute(status -> {
            if (processedCancelEventRepository.existsByCancelRequestId(command.cancelRequestId())) {
                return null;
            }

            List<OrderItem> items = orderItemRepository.findAllByIdIn(command.cancelledOrderItemIds());
            if (items.size() != command.cancelledOrderItemIds().size()) {
                throw new OrderItemNotFoundException(command.cancelledOrderItemIds());
            }

            items.forEach(OrderItem::cancel);
            orderItemRepository.saveAll(items);

            long orderId = items.get(0).getOrderId();
            Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: orderId=" + orderId));

            boolean allCancelled = orderItemRepository.findAllByOrderId(orderId)
                .stream().allMatch(OrderItem::isCancelled);

            if (allCancelled) {
                order.cancel();
            } else {
                order.partialCancel();
            }

            orderRepository.save(order);
            processedCancelEventRepository.save(command.cancelRequestId());
            return null;
        });
    }
}
