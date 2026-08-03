package com.example.product.presentation.controller;

import com.example.product.application.service.CatalogService;
import com.example.product.application.service.CatalogService.SeededSku;
import com.example.product.application.service.CatalogService.SeedResult;
import com.example.product.presentation.GlobalExceptionHandler;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductController")
class ProductControllerTest {

    @Mock CatalogService catalogService;

    MockMvc mvc;

    private static final String BODY = "{\"name\":\"티셔츠\",\"categoryId\":1,"
            + "\"skus\":[{\"skuCode\":\"SKU-1\",\"initialStock\":10,\"price\":1000}]}";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ProductController controller = new ProductController(catalogService);

        mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void seed_returns200_forAdmin() throws Exception {
        when(catalogService.seed(any(), any(), anyList(), anyList(), anyList()))
                .thenReturn(new SeedResult(1L, List.of(new SeededSku(10L, "SKU-1"))));

        mvc.perform(post("/v1/products").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.skus[0].skuId").value(10));

        verify(catalogService).seed(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void seed_requiresAdmin_rejectsUserRole() throws Exception {
        mvc.perform(post("/v1/products").header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(catalogService, never()).seed(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void seed_requiresAdmin_rejectsMissingRoleHeader() throws Exception {
        mvc.perform(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(catalogService, never()).seed(any(), any(), anyList(), anyList(), anyList());
    }
}
