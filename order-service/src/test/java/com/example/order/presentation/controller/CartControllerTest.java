package com.example.order.presentation.controller;

import com.example.order.application.usecase.CartUseCase;
import com.example.order.domain.entity.CartItem;
import com.example.order.domain.exception.CartItemNotFoundException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standalone MockMvc — OrderControllerIT 패턴 미러.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CartController - /v1/cart")
class CartControllerTest {

    @Mock CartUseCase cartUseCase;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        CartController controller = new CartController(cartUseCase);
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    @DisplayName("GET /v1/cart → 200 + items 매핑")
    void get_returnsCartItems() throws Exception {
        when(cartUseCase.getCart(7L)).thenReturn(
            List.of(CartItem.of(1L, 7L, 42L, 1L, "티", "블랙/M", 29000L, 3)));

        mockMvc.perform(get("/v1/cart").header("X-User-Id", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].skuId").value(42))
            .andExpect(jsonPath("$.items[0].quantity").value(3));

        verify(cartUseCase).getCart(7L);
    }

    @Test
    @DisplayName("POST /v1/cart/items valid → 201")
    void add_valid_returns201() throws Exception {
        when(cartUseCase.getCart(7L)).thenReturn(List.of());

        mockMvc.perform(post("/v1/cart/items")
                .header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skuId":42,"productId":1,"itemName":"티","optionSummary":"블랙/M","unitPrice":29000,"quantity":3}
                    """))
            .andExpect(status().isCreated());

        verify(cartUseCase).addItem(eq(7L), any(CartUseCase.AddCommand.class));
    }

    @Test
    @DisplayName("POST /v1/cart/items quantity<=0 → 400")
    void add_nonPositiveQuantity_returns400() throws Exception {
        mockMvc.perform(post("/v1/cart/items")
                .header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skuId":42,"productId":1,"itemName":"티","optionSummary":"블랙/M","unitPrice":29000,"quantity":0}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /v1/cart/items 필수 필드 누락 → 400")
    void add_missingRequiredField_returns400() throws Exception {
        mockMvc.perform(post("/v1/cart/items")
                .header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productId":1,"itemName":"티","unitPrice":29000,"quantity":3}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /v1/cart/items/{skuId} → 200")
    void update_valid_returns200() throws Exception {
        when(cartUseCase.getCart(7L)).thenReturn(
            List.of(CartItem.of(1L, 7L, 42L, 1L, "티", "블랙/M", 29000L, 9)));

        mockMvc.perform(patch("/v1/cart/items/42")
                .header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"quantity":9}
                    """))
            .andExpect(status().isOk());

        verify(cartUseCase).updateQuantity(7L, 42L, 9);
    }

    @Test
    @DisplayName("PATCH /v1/cart/items/{skuId} 없는 sku → 404")
    void update_absentSku_returns404() throws Exception {
        when(cartUseCase.updateQuantity(7L, 99L, 4)).thenThrow(new CartItemNotFoundException());

        mockMvc.perform(patch("/v1/cart/items/99")
                .header("X-User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"quantity":4}
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /v1/cart/items/{skuId} → 204")
    void remove_returns204() throws Exception {
        mockMvc.perform(delete("/v1/cart/items/42").header("X-User-Id", "7"))
            .andExpect(status().isNoContent());

        verify(cartUseCase).removeItem(7L, 42L);
    }

    @Test
    @DisplayName("DELETE /v1/cart → 204")
    void clear_returns204() throws Exception {
        mockMvc.perform(delete("/v1/cart").header("X-User-Id", "7"))
            .andExpect(status().isNoContent());

        verify(cartUseCase).clear(7L);
    }
}
