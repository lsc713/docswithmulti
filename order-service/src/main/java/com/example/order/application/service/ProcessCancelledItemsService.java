package com.example.order.application.service;

import com.example.order.common.exception.application.OrderItemNotFoundException;
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
            List<OrderItem> items = validateAndCancelItems(command.cancelledOrderItemIds());
            syncOrderStatus(items.get(0).getOrderId());
            processedCancelEventRepository.save(command.cancelRequestId());
            return null;
        });
    }

    private List<OrderItem> validateAndCancelItems(List<Long> ids) {
        List<OrderItem> items = orderItemRepository.findAllByIdIn(ids);
        if (items.size() != ids.size()) {
            throw new OrderItemNotFoundException(ids);
        }
        List<Long> orderIds = items.stream().map(OrderItem::getOrderId).distinct().toList();
        if (orderIds.size() != 1) {
            throw new IllegalStateException("단일 취소 이벤트에 복수 주문 아이템 포함: orderIds=" + orderIds);
        }
        items.forEach(OrderItem::cancel);
        orderItemRepository.saveAll(items);
        return items;
    }

    private void syncOrderStatus(long orderId) {
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
    }
}
