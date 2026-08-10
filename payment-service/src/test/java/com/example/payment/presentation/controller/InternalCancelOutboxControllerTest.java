package com.example.payment.presentation.controller;

import com.example.payment.application.authz.InternalOperatorAccess;
import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalCancelOutboxControllerTest {

    @Mock
    private CancelOutboxInspectionUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new InternalCancelOutboxController(
            useCase, new InternalOperatorAccess());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
    }

    @Test
    void adminOperatorCanInspectWithoutExposingReplayPayload() throws Exception {
        when(useCase.inspect(6L)).thenReturn(new CancelOutboxInspectionUseCase.Result(
            6L,
            27L,
            CancelOutboxDecision.REDRIVE_REQUIRED,
            null,
            leg(CancelRestoreLegStatus.APPLIED, 101L, "CANCELLED", null, null),
            leg(CancelRestoreLegStatus.NOT_APPLIED, 8L, "RESERVED", 1, 2)));

        mockMvc.perform(get("/internal/cancel-outbox/6")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outboxId").value(6))
            .andExpect(jsonPath("$.cancelRequestId").value(27))
            .andExpect(jsonPath("$.decision").value("REDRIVE_REQUIRED"))
            .andExpect(jsonPath("$.reasonCode").doesNotExist())
            .andExpect(jsonPath("$.order.status").value("APPLIED"))
            .andExpect(jsonPath("$.order.evidence[0].targetId").value(101))
            .andExpect(jsonPath("$.stock.status").value("NOT_APPLIED"))
            .andExpect(jsonPath("$.stock.evidence[0].actualQuantity").value(1))
            .andExpect(jsonPath("$.stock.evidence[0].expectedQuantity").value(2))
            .andExpect(jsonPath("$.payload").doesNotExist())
            .andExpect(jsonPath("$.paymentKey").doesNotExist());

        verify(useCase).inspect(6L);
    }

    @Test
    void missingInternalAuthenticationReturns401BeforeInspection() throws Exception {
        mockMvc.perform(get("/internal/cancel-outbox/6")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INTERNAL_AUTHENTICATION_REQUIRED"));

        verify(useCase, never()).inspect(anyLong());
    }

    @Test
    void missingOperatorIdentityReturns403BeforeInspection() throws Exception {
        mockMvc.perform(get("/internal/cancel-outbox/6")
                .header("X-User-Role", "ADMIN"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_REDRIVE_FORBIDDEN"));

        verify(useCase, never()).inspect(anyLong());
    }

    @Test
    void nonAdminOperatorReturns403BeforeInspection() throws Exception {
        mockMvc.perform(get("/internal/cancel-outbox/6")
                .header("X-User-Role", "USER")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_REDRIVE_FORBIDDEN"));

        verify(useCase, never()).inspect(anyLong());
    }

    @Test
    void missingOutboxReturnsStable404() throws Exception {
        when(useCase.inspect(99L)).thenThrow(new CancelOutboxNotFoundException(99L));

        mockMvc.perform(get("/internal/cancel-outbox/99")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CANCEL_OUTBOX_NOT_FOUND"));
    }

    private static CancelRestoreLegSnapshot leg(
        CancelRestoreLegStatus status,
        long targetId,
        String currentStatus,
        Integer actualQuantity,
        Integer expectedQuantity
    ) {
        return new CancelRestoreLegSnapshot(status, List.of(
            new CancelRestoreLegSnapshot.Evidence(
                targetId, currentStatus, actualQuantity, expectedQuantity)));
    }
}
