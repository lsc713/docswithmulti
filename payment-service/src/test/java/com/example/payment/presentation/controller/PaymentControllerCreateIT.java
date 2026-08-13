package com.example.payment.presentation.controller;

import com.example.payment.application.usecase.PaymentExistsQuery;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerCreateIT {
    @Test
    void legacy_immediate_completion_endpoint_is_gone() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new PaymentController(mock(PaymentExistsQuery.class))).build();

        mvc.perform(post("/v1/payments")).andExpect(status().isNotFound());
    }
}
