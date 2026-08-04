package com.example.payment.presentation.controller;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.usecase.PaymentHistoryQuery;
import com.example.payment.presentation.dto.PaymentDetailResponse;
import com.example.payment.presentation.dto.PaymentSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentHistoryController")
class PaymentHistoryControllerTest {

    @Mock PaymentHistoryQuery query;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PaymentHistoryController controller = new PaymentHistoryController(query);
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
    }

    @Test
    @DisplayName("GET /v1/payments: 200 + 본인 결제 배열, X-User-Id/page/size 기본값 전달")
    void list_returns_user_payments_with_default_paging() throws Exception {
        when(query.list(7L, 0, 20)).thenReturn(List.of(
            new PaymentSummaryResponse("pay_x", new BigDecimal("29000"), "COMPLETED", "2026-08-04T00:00", 1L,
                List.of(new PaymentSummaryResponse.Item(3L, "티", new BigDecimal("29000"), "ACTIVE")))));

        mockMvc.perform(get("/v1/payments").header("X-User-Id", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].paymentKey").value("pay_x"))
            .andExpect(jsonPath("$[0].items[0].paymentItemId").value(3));

        verify(query).list(7L, 0, 20);
    }

    @Test
    @DisplayName("GET /v1/payments?page=&size=: 전달된 page/size가 그대로 usecase에 전파")
    void list_propagates_page_and_size_params() throws Exception {
        when(query.list(eq(7L), eq(2), eq(5))).thenReturn(List.of());

        mockMvc.perform(get("/v1/payments").header("X-User-Id", "7").param("page", "2").param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        verify(query).list(7L, 2, 5);
    }

    @Test
    @DisplayName("GET /v1/payments?page=-1&size=0: 음수/과대 입력도 500 대신 200 + 클램프된 값 전파")
    void list_clamps_invalid_page_and_size() throws Exception {
        when(query.list(eq(7L), eq(0), eq(1))).thenReturn(List.of());

        mockMvc.perform(get("/v1/payments").header("X-User-Id", "7").param("page", "-1").param("size", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        verify(query).list(7L, 0, 1);
    }

    @Test
    @DisplayName("GET /v1/payments/{paymentKey}: 본인 소유면 200 + 상세")
    void detail_returns_200_for_owner() throws Exception {
        when(query.detail(7L, "pay_x")).thenReturn(
            new PaymentDetailResponse("pay_x", new BigDecimal("29000"), "COMPLETED", "2026-08-04T00:00",
                1L, "TOSS", List.of(new PaymentSummaryResponse.Item(3L, "티", new BigDecimal("29000"), "ACTIVE"))));

        mockMvc.perform(get("/v1/payments/{paymentKey}", "pay_x").header("X-User-Id", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentKey").value("pay_x"))
            .andExpect(jsonPath("$.pgType").value("TOSS"));

        verify(query).detail(7L, "pay_x");
    }

    @Test
    @DisplayName("GET /v1/payments/{paymentKey}: 타 유저 결제는 404 (존재 은닉)")
    void detail_returns_404_for_non_owner() throws Exception {
        when(query.detail(99L, "pay_x")).thenThrow(new PaymentNotFoundException("pay_x"));

        mockMvc.perform(get("/v1/payments/{paymentKey}", "pay_x").header("X-User-Id", "99"))
            .andExpect(status().isNotFound());
    }
}
