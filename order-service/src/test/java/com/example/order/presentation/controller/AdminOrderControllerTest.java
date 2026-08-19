package com.example.order.presentation.controller;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import com.example.order.domain.entity.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminOrderControllerTest {

    @Mock OrderRepository orders;
    @Mock OrderItemRepository items;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminOrderController(orders, items))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void adminListsOrdersWithItemsAndTotal() throws Exception {
        when(orders.findAll()).thenReturn(List.of(Order.of(11L, 42L, OrderStatus.DELIVERY_WAITING)));
        when(items.findAllByOrderId(11L)).thenReturn(List.of(
            OrderItem.of(101L, 11L, 7L, "린넨 셔츠", new BigDecimal("29000"), OrderItemStatus.ACTIVE),
            OrderItem.of(102L, 11L, 8L, "코튼 팬츠", new BigDecimal("41000"), OrderItemStatus.ACTIVE)));

        mvc.perform(get("/v1/admin/orders").header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(11))
            .andExpect(jsonPath("$[0].userId").value(42))
            .andExpect(jsonPath("$[0].totalAmount").value(70000))
            .andExpect(jsonPath("$[0].items[1].itemName").value("코튼 팬츠"));
    }

    @Test
    void nonAdminCannotReadOrders() throws Exception {
        mvc.perform(get("/v1/admin/orders").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden());
    }
}
