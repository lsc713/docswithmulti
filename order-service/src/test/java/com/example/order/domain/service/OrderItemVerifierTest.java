package com.example.order.domain.service;

import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import com.example.order.domain.entity.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("OrderItemVerifier")
class OrderItemVerifierTest {

    private final OrderItemVerifier verifier = new OrderItemVerifier();

    @Test
    @DisplayName("resolveOrderId: 요청된 id 전부 동일 order 소속이면 그 orderId를 반환한다 (OVER-01)")
    void resolveOrderId_returnsOrderId_whenAllItemsBelongToSingleOrder() {
        List<OrderItem> foundItems = List.of(
            OrderItem.of(1L, 100L, 11L, "item1", BigDecimal.TEN, OrderItemStatus.ACTIVE),
            OrderItem.of(2L, 100L, 12L, "item2", BigDecimal.TEN, OrderItemStatus.ACTIVE)
        );

        long orderId = verifier.resolveOrderId(List.of(1L, 2L), foundItems);

        assertThat(orderId).isEqualTo(100L);
    }

    @Test
    @DisplayName("checkOwnership: order.userId == requesterUserId면 예외를 던지지 않는다 (OVER-01)")
    void checkOwnership_doesNotThrow_whenRequesterIsOwner() {
        Order order = Order.of(100L, 42L, OrderStatus.DELIVERY_WAITING);

        assertThatCode(() -> verifier.checkOwnership(order, 42L)).doesNotThrowAnyException();
    }
}
