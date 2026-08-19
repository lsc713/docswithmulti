package com.example.settlement.presentation.controller;

import com.example.settlement.application.service.PayoutService;
import com.example.settlement.application.service.SettlementQueryService;
import com.example.settlement.domain.entity.Settlement;
import com.example.settlement.presentation.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminSettlementControllerTest {

    @Mock SettlementQueryService queries;
    @Mock PayoutService payouts;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminSettlementController(queries, payouts))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void adminListsSettlementsForMerchant() throws Exception {
        Settlement settlement = Settlement.reconstruct(
            3L, 1L, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-16"),
            new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("3000"),
            new BigDecimal("300"), new BigDecimal("96700"), "FINALIZED",
            Instant.parse("2026-08-17T00:00:00Z"), Instant.EPOCH, Instant.EPOCH);
        when(queries.list(1L, "FINALIZED")).thenReturn(List.of(settlement));

        mvc.perform(get("/v1/admin/settlements?merchantId=1&status=FINALIZED")
                .header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(3))
            .andExpect(jsonPath("$[0].netAmount").value(96700));
    }

    @Test
    void nonAdminCannotReadSettlements() throws Exception {
        mvc.perform(get("/v1/admin/settlements?merchantId=1").header("X-User-Role", "MERCHANT"))
            .andExpect(status().isForbidden());
    }
}
