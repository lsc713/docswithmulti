package com.example.order.application.service;

import com.example.order.common.exception.application.OrderItemNotFoundException;
import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.example.order.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProcessCancelledItemsServiceTest {

    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private ProcessedCancelEventRepository processedCancelEventRepository;
    private ProcessCancelledItemsService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        processedCancelEventRepository = mock(ProcessedCancelEventRepository.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        service = new ProcessCancelledItemsService(
            orderRepository, orderItemRepository, processedCancelEventRepository,
            new TransactionTemplate(txManager));
    }

    @Test
    void should_do_nothing_when_cancel_request_already_processed() {
        when(processedCancelEventRepository.existsByCancelRequestId("cr_1")).thenReturn(true);

        service.execute(new ProcessCancelledItemsUseCase.Command("cr_1", List.of(10L)));

        verify(orderItemRepository, never()).findAllByIdIn(any());
        verify(processedCancelEventRepository, never()).save(any());
    }

    @Test
    void should_throw_when_order_item_not_found() {
        when(processedCancelEventRepository.existsByCancelRequestId("cr_2")).thenReturn(false);
        when(orderItemRepository.findAllByIdIn(List.of(99L))).thenReturn(List.of());

        assertThrows(OrderItemNotFoundException.class,
            () -> service.execute(new ProcessCancelledItemsUseCase.Command("cr_2", List.of(99L))));
    }

    @Test
    void should_cancel_order_when_all_items_cancelled() {
        when(processedCancelEventRepository.existsByCancelRequestId("cr_3")).thenReturn(false);

        OrderItem item1 = OrderItem.of(10L, 1L, 100L, "상품A", java.math.BigDecimal.valueOf(10000), OrderItemStatus.ACTIVE);
        OrderItem item2 = OrderItem.of(11L, 1L, 101L, "상품B", java.math.BigDecimal.valueOf(20000), OrderItemStatus.ACTIVE);
        when(orderItemRepository.findAllByIdIn(List.of(10L, 11L))).thenReturn(List.of(item1, item2));

        Order order = Order.of(1L, 100L, OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        // item1, item2 가 cancel() 호출 후 CANCELLED 상태가 됨
        when(orderItemRepository.findAllByOrderId(1L)).thenReturn(List.of(item1, item2));

        service.execute(new ProcessCancelledItemsUseCase.Command("cr_3", List.of(10L, 11L)));

        assertThat(item1.isCancelled()).isTrue();
        assertThat(item2.isCancelled()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(processedCancelEventRepository).save("cr_3");
    }

    @Test
    void should_partial_cancel_order_when_some_items_remain_active() {
        when(processedCancelEventRepository.existsByCancelRequestId("cr_4")).thenReturn(false);

        OrderItem item1 = OrderItem.of(10L, 1L, 100L, "상품A", java.math.BigDecimal.valueOf(10000), OrderItemStatus.ACTIVE);
        when(orderItemRepository.findAllByIdIn(List.of(10L))).thenReturn(List.of(item1));

        Order order = Order.of(1L, 100L, OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        // item1 은 cancel() 후 CANCELLED, item2 는 여전히 ACTIVE
        OrderItem item2 = OrderItem.of(11L, 1L, 101L, "상품B", java.math.BigDecimal.valueOf(20000), OrderItemStatus.ACTIVE);
        when(orderItemRepository.findAllByOrderId(1L)).thenReturn(List.of(item1, item2));

        service.execute(new ProcessCancelledItemsUseCase.Command("cr_4", List.of(10L)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIAL_CANCELLED);
        verify(processedCancelEventRepository).save("cr_4");
    }
}
