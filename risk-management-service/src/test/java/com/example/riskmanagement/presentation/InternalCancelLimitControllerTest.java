package com.example.riskmanagement.presentation;

import com.example.riskmanagement.application.exception.ServiceUnavailableException;
import com.example.riskmanagement.application.usecase.CheckChargeUseCase;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.domain.exception.MerchantCancelLimitExceededException;
import com.example.riskmanagement.presentation.controller.InternalCancelLimitController;
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

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCancelLimitController")
class InternalCancelLimitControllerTest {

    @Mock ValidateAndReserveUseCase validateAndReserveUseCase;
    @Mock CompensateUseCase compensateUseCase;
    @Mock CheckChargeUseCase checkChargeUseCase;

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        InternalCancelLimitController controller = new InternalCancelLimitController(
            validateAndReserveUseCase, compensateUseCase, checkChargeUseCase);

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    @DisplayName("validate-and-reserve 성공 — 200")
    void validate_and_reserve_returns_200() throws Exception {
        when(validateAndReserveUseCase.execute(any())).thenReturn(
            new ValidateAndReserveUseCase.Result(1L, BigDecimal.valueOf(5_000_000),
                BigDecimal.valueOf(300_000), BigDecimal.valueOf(4_700_000)));

        mockMvc.perform(post("/internal/cancel-limit/validate-and-reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "merchantId": 1,
                      "cancelRequestId": "cr_001",
                      "cancelAmount": 300000,
                      "kstDate": "2026-04-23"
                    }"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.remainingLimit").value(4700000));
    }

    @Test
    @DisplayName("한도 초과 — 422 + 추가 필드 포함")
    void validate_and_reserve_returns_422_when_limit_exceeded() throws Exception {
        when(validateAndReserveUseCase.execute(any())).thenThrow(
            new MerchantCancelLimitExceededException(
                BigDecimal.valueOf(5_000_000),
                BigDecimal.valueOf(4_800_000),
                BigDecimal.valueOf(300_000)));

        mockMvc.perform(post("/internal/cancel-limit/validate-and-reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "merchantId": 1,
                      "cancelRequestId": "cr_001",
                      "cancelAmount": 300000,
                      "kstDate": "2026-04-23"
                    }"""))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("MERCHANT_CANCEL_LIMIT_EXCEEDED"))
            .andExpect(jsonPath("$.dailyLimit").value(5000000))
            .andExpect(jsonPath("$.requestAmount").value(300000));
    }

    @Test
    @DisplayName("merchantId 누락 — 400")
    void validate_and_reserve_returns_400_when_merchant_id_missing() throws Exception {
        mockMvc.perform(post("/internal/cancel-limit/validate-and-reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelRequestId": "cr_001",
                      "cancelAmount": 300000,
                      "kstDate": "2026-04-23"
                    }"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("check — charged=true 반환")
    void check_returns_charged_true() throws Exception {
        when(checkChargeUseCase.execute("cr_001")).thenReturn(
            new CheckChargeUseCase.Result("cr_001", true, 1L, BigDecimal.valueOf(300_000)));

        mockMvc.perform(get("/internal/cancel-limit/check")
                .param("cancelRequestId", "cr_001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.charged").value(true))
            .andExpect(jsonPath("$.cancelAmount").value(300000));
    }

    @Test
    @DisplayName("SERVICE_UNAVAILABLE — 503")
    void validate_and_reserve_returns_503_when_service_unavailable() throws Exception {
        when(validateAndReserveUseCase.execute(any()))
            .thenThrow(ServiceUnavailableException.riskServiceUnavailable());

        mockMvc.perform(post("/internal/cancel-limit/validate-and-reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "merchantId": 1,
                      "cancelRequestId": "cr_001",
                      "cancelAmount": 300000,
                      "kstDate": "2026-04-23"
                    }"""))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("RISK_SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("compensate 성공 — 200")
    void compensate_returns_200() throws Exception {
        when(compensateUseCase.execute(any())).thenReturn(
            new CompensateUseCase.Result("cr_001", true, null));

        mockMvc.perform(post("/internal/cancel-limit/compensate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cancelRequestId": "cr_001",
                      "merchantId": 1,
                      "restoreAmount": 300000
                    }"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.restored").value(true));
    }
}
