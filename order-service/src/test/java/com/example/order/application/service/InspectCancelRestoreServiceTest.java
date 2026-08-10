package com.example.order.application.service;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import com.example.order.application.model.CancelRestoreLegStatus;
import com.example.order.application.usecase.InspectCancelRestoreUseCase.Command;
import com.example.order.application.usecase.InspectCancelRestoreUseCase.Evidence;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import com.example.order.domain.entity.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InspectCancelRestoreServiceTest {

    private ProcessedCancelEventRepository processed;
    private OrderItemRepository items;
    private OrderRepository orders;
    private InspectCancelRestoreService service;

    @BeforeEach
    void setUp() {
        processed = mock(ProcessedCancelEventRepository.class);
        items = mock(OrderItemRepository.class);
        orders = mock(OrderRepository.class);
        service = new InspectCancelRestoreService(processed, items, orders);
    }

    @Test
    void processedMarkerAndCancelledTargetsAreApplied() {
        when(processed.existsByCancelRequestId("27")).thenReturn(true);
        when(items.findAllByIdIn(List.of(10L, 11L))).thenReturn(List.of(
            item(10L, 100L, OrderItemStatus.CANCELLED),
            item(11L, 100L, OrderItemStatus.CANCELLED)));
        when(orders.findById(100L)).thenReturn(Optional.of(
            Order.of(100L, 7L, OrderStatus.CANCELLED)));

        var result = service.inspect(new Command("27", List.of(10L, 11L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.APPLIED);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void noMarkerAndActiveTargetsAreNotApplied() {
        when(processed.existsByCancelRequestId("27")).thenReturn(false);
        when(items.findAllByIdIn(List.of(10L))).thenReturn(List.of(
            item(10L, 100L, OrderItemStatus.ACTIVE)));

        var result = service.inspect(new Command("27", List.of(10L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.NOT_APPLIED);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void processedMarkerWithActiveTargetIsInconsistent() {
        when(processed.existsByCancelRequestId("27")).thenReturn(true);
        when(items.findAllByIdIn(List.of(10L))).thenReturn(List.of(
            item(10L, 100L, OrderItemStatus.ACTIVE)));

        var result = service.inspect(new Command("27", List.of(10L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(new Evidence(10L, "ACTIVE"));
    }

    @Test
    void cancelledTargetWithoutProcessedMarkerIsInconsistent() {
        when(processed.existsByCancelRequestId("27")).thenReturn(false);
        when(items.findAllByIdIn(List.of(10L))).thenReturn(List.of(
            item(10L, 100L, OrderItemStatus.CANCELLED)));

        var result = service.inspect(new Command("27", List.of(10L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(new Evidence(10L, "CANCELLED"));
    }

    @Test
    void missingTargetIsInconsistentInsteadOfApplied() {
        when(processed.existsByCancelRequestId("27")).thenReturn(true);
        when(items.findAllByIdIn(List.of(10L, 99L))).thenReturn(List.of(
            item(10L, 100L, OrderItemStatus.CANCELLED)));

        var result = service.inspect(new Command("27", List.of(10L, 99L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(new Evidence(99L, "MISSING"));
    }

    @Test
    void appliedItemsWithNonCancelledAggregateAreInconsistent() {
        when(processed.existsByCancelRequestId("27")).thenReturn(true);
        when(items.findAllByIdIn(List.of(10L))).thenReturn(List.of(
            item(10L, 100L, OrderItemStatus.CANCELLED)));
        when(orders.findById(100L)).thenReturn(Optional.of(
            Order.of(100L, 7L, OrderStatus.DELIVERY_WAITING)));

        var result = service.inspect(new Command("27", List.of(10L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(new Evidence(100L, "DELIVERY_WAITING"));
    }

    private static OrderItem item(long id, long orderId, OrderItemStatus status) {
        return OrderItem.of(id, orderId, 1L, "item", BigDecimal.TEN, status);
    }
}
