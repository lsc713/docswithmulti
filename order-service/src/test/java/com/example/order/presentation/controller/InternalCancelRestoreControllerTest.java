package com.example.order.presentation.controller;

import com.example.order.application.model.CancelRestoreLegStatus;
import com.example.order.application.usecase.InspectCancelRestoreUseCase;
import com.example.order.application.usecase.InspectCancelRestoreUseCase.Evidence;
import com.example.order.application.usecase.InspectCancelRestoreUseCase.Result;
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
        when(useCase.inspect(new InspectCancelRestoreUseCase.Command("27", List.of(10L, 11L))))
            .thenReturn(new Result(
                CancelRestoreLegStatus.INCONSISTENT,
                List.of(new Evidence(11L, "ACTIVE"))));

        mockMvc.perform(post("/internal/cancel-restores/27:inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderItemIds\":[10,11]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INCONSISTENT"))
            .andExpect(jsonPath("$.evidence[0].targetId").value(11))
            .andExpect(jsonPath("$.evidence[0].currentStatus").value("ACTIVE"));
    }

    @Test
    void rejectsEmptyOrderItemIdsWithoutCallingUseCase() throws Exception {
        mockMvc.perform(post("/internal/cancel-restores/27:inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderItemIds\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(useCase);
    }
}
