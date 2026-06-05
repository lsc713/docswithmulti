package com.example.order.application.service;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProductStockClient;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import com.example.order.domain.entity.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CreateOrderServiceTest {

    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private ProductStockClient productStockClient;
    private CreateOrderService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        productStockClient = mock(ProductStockClient.class);
        service = new CreateOrderService(orderRepository, orderItemRepository, productStockClient);
    }

    @Test
    void should_deduct_stock_and_create_order() {
        Order savedOrder = Order.of(1L, 10L, OrderStatus.PENDING);
        when(orderRepository.insert(any())).thenReturn(savedOrder);

        OrderItem savedItem = OrderItem.of(1L, 1L, 100L, "상품A", BigDecimal.valueOf(10000), OrderItemStatus.ACTIVE);
        when(orderItemRepository.insertAll(any())).thenReturn(List.of(savedItem));

        doNothing().when(productStockClient).deduct(anyLong(), anyInt());

        CreateOrderCommand command = new CreateOrderCommand(
            10L,
            List.of(new CreateOrderCommand.Item(100L, 5L, 2, "상품A", BigDecimal.valueOf(10000)))
        );

        var result = service.create(command);

        verify(productStockClient).deduct(5L, 2);
        assertThat(result.order().getId()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void should_not_create_order_when_stock_deduction_fails() {
        doThrow(new RuntimeException("재고 차감 실패")).when(productStockClient).deduct(anyLong(), anyInt());

        CreateOrderCommand command = new CreateOrderCommand(
            10L,
            List.of(new CreateOrderCommand.Item(100L, 5L, 2, "상품A", BigDecimal.valueOf(10000)))
        );

        assertThatThrownBy(() -> service.create(command))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("재고 차감 실패");

        verify(orderRepository, never()).insert(any());
        verify(orderItemRepository, never()).insertAll(any());
    }

    @Test
    void should_deduct_stock_for_each_item() {
        Order savedOrder = Order.of(1L, 10L, OrderStatus.PENDING);
        when(orderRepository.insert(any())).thenReturn(savedOrder);

        OrderItem item1 = OrderItem.of(1L, 1L, 100L, "상품A", BigDecimal.valueOf(10000), OrderItemStatus.ACTIVE);
        OrderItem item2 = OrderItem.of(2L, 1L, 101L, "상품B", BigDecimal.valueOf(20000), OrderItemStatus.ACTIVE);
        when(orderItemRepository.insertAll(any())).thenReturn(List.of(item1, item2));

        doNothing().when(productStockClient).deduct(anyLong(), anyInt());

        CreateOrderCommand command = new CreateOrderCommand(
            10L,
            List.of(
                new CreateOrderCommand.Item(100L, 5L, 2, "상품A", BigDecimal.valueOf(10000)),
                new CreateOrderCommand.Item(101L, 6L, 1, "상품B", BigDecimal.valueOf(20000))
            )
        );

        service.create(command);

        verify(productStockClient).deduct(5L, 2);
        verify(productStockClient).deduct(6L, 1);
        verify(productStockClient, times(2)).deduct(anyLong(), anyInt());
    }
}
