package com.example.order.application.service;

import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.usecase.MarkOrderPaymentCompletedUseCase;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarkOrderPaymentCompletedServiceTest {

    private OrderRepository orderRepository;
    private TransactionTemplate transactionTemplate;
    private MarkOrderPaymentCompletedService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        service = new MarkOrderPaymentCompletedService(orderRepository, transactionTemplate);
    }

    @Test
    void pending_order_is_saved_as_delivery_waiting() {
        Order order = Order.of(7L, 42L, OrderStatus.PENDING);
        when(orderRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(order));

        service.execute(new MarkOrderPaymentCompletedUseCase.Command(7L));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_WAITING);
        verify(orderRepository).save(order);
    }

    @Test
    void duplicate_completed_event_does_not_save_again() {
        Order order = Order.of(7L, 42L, OrderStatus.DELIVERY_WAITING);
        when(orderRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(order));

        service.execute(new MarkOrderPaymentCompletedUseCase.Command(7L));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_WAITING);
        verify(orderRepository, never()).save(any());
    }
}
