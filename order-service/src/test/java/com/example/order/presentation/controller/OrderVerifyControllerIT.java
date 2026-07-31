package com.example.order.presentation.controller;

import com.example.order.application.usecase.CreateOrderUseCase;
import com.example.order.application.usecase.VerifyOrderItemsUseCase;
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
 * Task 1: happy path만. 실패 분기(404/409/403)는 Task 2에서 추가.
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
}
