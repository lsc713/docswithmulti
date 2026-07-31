package com.example.order.presentation.controller;

import com.example.order.application.service.CreateOrderCommand;
import com.example.order.application.usecase.CreateOrderUseCase;
import com.example.order.application.usecase.VerifyOrderItemsUseCase;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import com.example.order.domain.entity.OrderStatus;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /v1/orders 컨트롤러 IT — 소유자가 body userId가 아닌 X-User-Id 헤더에서 온다는 것을 증명 (TRUST-02).
 * standalone MockMvc — CancelControllerTest 패턴 미러.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderController - POST /v1/orders")
class OrderControllerIT {

    @Mock CreateOrderUseCase createOrderUseCase;
    @Mock VerifyOrderItemsUseCase verifyOrderItemsUseCase;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Spring Boot 자동설정 ObjectMapper와 동일하게 알 수 없는 필드는 무시(프로덕션 기본값 미러).
        objectMapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        OrderController controller = new OrderController(createOrderUseCase, verifyOrderItemsUseCase);
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    @DisplayName("X-User-Id 헤더값이 소유자로 커맨드에 전달된다 (TRUST-02)")
    void create_ownerComesFromXUserIdHeader() throws Exception {
        Order order = Order.of(1L, 42L, OrderStatus.DELIVERY_WAITING);
        OrderItem item = OrderItem.of(1L, 1L, 11L, "item1", BigDecimal.TEN, OrderItemStatus.ACTIVE);
        when(createOrderUseCase.create(any())).thenReturn(new CreateOrderUseCase.Result(order, List.of(item)));

        mockMvc.perform(post("/v1/orders")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"items":[{"productId":11,"itemName":"item1","price":10}]}
                    """))
            .andExpect(status().isCreated());

        ArgumentCaptor<CreateOrderCommand> captor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(createOrderUseCase).create(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("회귀: body에 userId를 실어 보내도 무시되고 헤더값만 소유자로 쓰인다 (TRUST-02)")
    void create_bodyUserId_ifSent_isIgnored() throws Exception {
        Order order = Order.of(1L, 42L, OrderStatus.DELIVERY_WAITING);
        OrderItem item = OrderItem.of(1L, 1L, 11L, "item1", BigDecimal.TEN, OrderItemStatus.ACTIVE);
        when(createOrderUseCase.create(any())).thenReturn(new CreateOrderUseCase.Result(order, List.of(item)));

        // body에 존재하지도 않는 CreateOrderRequest.userId 필드에 값을 실어 보내는 스푸핑 시도 —
        // 알 수 없는 JSON 필드는 역직렬화 시 무시된다(Jackson 기본 FAIL_ON_UNKNOWN_PROPERTIES=false).
        mockMvc.perform(post("/v1/orders")
                .header("X-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":999,"items":[{"productId":11,"itemName":"item1","price":10}]}
                    """))
            .andExpect(status().isCreated());

        ArgumentCaptor<CreateOrderCommand> captor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(createOrderUseCase).create(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(42L);
    }
}
