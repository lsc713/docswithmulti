package com.example.product.presentation.controller;

import com.example.product.application.model.CancelRestoreLegStatus;
import com.example.product.application.usecase.InspectCancelRestoreUseCase;
import com.example.product.application.usecase.InspectCancelRestoreUseCase.Evidence;
import com.example.product.application.usecase.InspectCancelRestoreUseCase.Result;
import com.example.product.presentation.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalCancelRestoreControllerTest {

    @Mock
    private InspectCancelRestoreUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new InternalCancelRestoreController(useCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
    }

    @Test
    void returnsInspectionStatusAndEvidence() throws Exception {
        var command = new InspectCancelRestoreUseCase.Command(
            "27", "pay_1", List.of(new InspectCancelRestoreUseCase.Item(8L, 2)));
        when(useCase.inspect(command)).thenReturn(new Result(
            CancelRestoreLegStatus.INCONSISTENT,
            List.of(new Evidence(8L, "RESERVED", 2, 2))));

        mockMvc.perform(post("/internal/cancel-restores/27:inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"paymentKey":"pay_1","items":[{"skuId":8,"quantity":2}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INCONSISTENT"))
            .andExpect(jsonPath("$.evidence[0].skuId").value(8))
            .andExpect(jsonPath("$.evidence[0].currentStatus").value("RESERVED"))
            .andExpect(jsonPath("$.evidence[0].actualQuantity").value(2))
            .andExpect(jsonPath("$.evidence[0].expectedQuantity").value(2));
    }

    @Test
    void rejectsEmptyItemsWithoutCallingUseCase() throws Exception {
        mockMvc.perform(post("/internal/cancel-restores/27:inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"pay_1\",\"items\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(useCase);
    }

    @Test
    void rejectsNonPositiveQuantityWithoutCallingUseCase() throws Exception {
        mockMvc.perform(post("/internal/cancel-restores/27:inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"paymentKey":"pay_1","items":[{"skuId":8,"quantity":0}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(useCase);
    }
}
