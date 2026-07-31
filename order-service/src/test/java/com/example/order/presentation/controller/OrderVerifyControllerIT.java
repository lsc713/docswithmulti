package com.example.order.presentation.controller;

import com.example.order.application.usecase.CreateOrderUseCase;
import com.example.order.application.usecase.VerifyOrderItemsUseCase;
import com.example.order.domain.exception.OrderItemsMultipleOrdersException;
import com.example.order.domain.exception.OrderOwnershipMismatchException;
import com.example.order.domain.exception.VerifyOrderItemNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /v1/orders/items:verify 컨트롤러 IT (standalone MockMvc — CancelControllerTest 패턴 미러).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderController - /v1/orders/items:verify")
class OrderVerifyControllerIT {

    @Mock CreateOrderUseCase createOrderUseCase;
    @Mock VerifyOrderItemsUseCase verifyOrderItemsUseCase;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        OrderController controller = new OrderController(createOrderUseCase, verifyOrderItemsUseCase);
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    @DisplayName("X-User-Id + 소유 단일 order 세트 → 200 {orderId} (OVER-01)")
    void verify_returns200WithOrderId_forOwnedSingleOrderSet() throws Exception {
        when(verifyOrderItemsUseCase.verify(eq(List.of(1L, 2L)), eq(42L)))
            .thenReturn(new VerifyOrderItemsUseCase.Result(100L));

        mockMvc.perform(post("/v1/orders/items:verify")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderItemIds\":[1,2]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(100));
    }

    @Test
    @DisplayName("orderItemId 미존재 → 404 ORDER_ITEM_NOT_FOUND (OVER-01)")
    void verify_returns404_whenOrderItemNotFound() throws Exception {
        when(verifyOrderItemsUseCase.verify(eq(List.of(1L, 999L)), eq(42L)))
            .thenThrow(new VerifyOrderItemNotFoundException(List.of(999L)));

        mockMvc.perform(post("/v1/orders/items:verify")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderItemIds\":[1,999]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_FOUND"));
    }

    @Test
    @DisplayName("요청 항목이 여러 order에 걸침 → 409 ORDER_ITEMS_MULTIPLE_ORDERS (OVER-01)")
    void verify_returns409_whenItemsSpanMultipleOrders() throws Exception {
        when(verifyOrderItemsUseCase.verify(eq(List.of(1L, 2L)), eq(42L)))
            .thenThrow(new OrderItemsMultipleOrdersException());

        mockMvc.perform(post("/v1/orders/items:verify")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderItemIds\":[1,2]}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORDER_ITEMS_MULTIPLE_ORDERS"));
    }

    @Test
    @DisplayName("소유 불일치 → 403 ORDER_OWNERSHIP_MISMATCH (OVER-01)")
    void verify_returns403_whenOwnershipMismatch() throws Exception {
        when(verifyOrderItemsUseCase.verify(eq(List.of(1L, 2L)), eq(42L)))
            .thenThrow(new OrderOwnershipMismatchException());

        mockMvc.perform(post("/v1/orders/items:verify")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderItemIds\":[1,2]}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ORDER_OWNERSHIP_MISMATCH"));
    }
}
