package com.example.order.presentation.controller;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.common.exception.BusinessException;
import com.example.order.common.exception.ErrorCode;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/v1/admin/orders")
public class AdminOrderController {

    private final OrderRepository orders;
    private final OrderItemRepository items;

    public AdminOrderController(OrderRepository orders, OrderItemRepository items) {
        this.orders = orders;
        this.items = items;
    }

    @GetMapping
    public List<OrderResponse> list(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        // ponytail: 전체 조회 + N+1은 로컬 운영 화면 규모용. 주문량이 커지면 projection/page query로 교체.
        return orders.findAll().stream().map(this::response).toList();
    }

    @GetMapping("/{id}")
    public OrderResponse detail(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @PathVariable long id) {
        requireAdmin(role);
        return response(orders.findById(id).orElseThrow(OrderNotFoundException::new));
    }

    private OrderResponse response(Order order) {
        List<ItemResponse> orderItems = items.findAllByOrderId(order.getId()).stream()
            .map(ItemResponse::from)
            .toList();
        BigDecimal total = orderItems.stream()
            .map(ItemResponse::price)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus().name(), total, orderItems);
    }

    private static void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) throw new ForbiddenException();
    }

    public record OrderResponse(long id, long userId, String status,
                                BigDecimal totalAmount, List<ItemResponse> items) {}

    public record ItemResponse(long id, long productId, String itemName,
                               BigDecimal price, String status) {
        static ItemResponse from(OrderItem item) {
            return new ItemResponse(item.getId(), item.getProductId(), item.getItemName(),
                item.getPrice(), item.getStatus().name());
        }
    }

    private static final class ForbiddenException extends BusinessException {
        private ForbiddenException() { super(ErrorCode.FORBIDDEN); }
    }

    private static final class OrderNotFoundException extends BusinessException {
        private OrderNotFoundException() { super(ErrorCode.ORDER_NOT_FOUND); }
    }
}
