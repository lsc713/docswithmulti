package com.example.payment.presentation.controller;

import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.CancelRequest;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelController")
class CancelControllerTest {

    @Mock CancelPaymentUseCase cancelPaymentUseCase;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        CancelController controller = new CancelController(cancelPaymentUseCase);

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    @DisplayName("Idempotency-Key 헤더 있으면 command.idempotencyKey()에 전달")
    void idempotencyKey_header_propagates_to_command() throws Exception {
        when(cancelPaymentUseCase.cancel(any())).thenReturn(
            CancelRequest.create(1L, "hash1", BigDecimal.valueOf(30_000), "고객 변심", List.of(1L), "k1"));

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isOk());

        ArgumentCaptor<CancelPaymentCommand> captor = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPaymentUseCase).cancel(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("k1");
    }

    @Test
    @DisplayName("Idempotency-Key 헤더 없으면 command.idempotencyKey()는 null")
    void idempotencyKey_header_absent_yields_null() throws Exception {
        when(cancelPaymentUseCase.cancel(any())).thenReturn(
            CancelRequest.create(1L, "hash2", BigDecimal.valueOf(30_000), "고객 변심", List.of(1L), null));

        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", "pay_001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelReason": "고객 변심",
                      "cancelItems": [{"paymentItemId": 1}]
                    }"""))
            .andExpect(status().isOk());

        ArgumentCaptor<CancelPaymentCommand> captor = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPaymentUseCase).cancel(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isNull();
    }
}
