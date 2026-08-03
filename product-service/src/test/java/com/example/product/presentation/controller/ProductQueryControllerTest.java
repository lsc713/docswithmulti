package com.example.product.presentation.controller;

import com.example.product.application.interfaces.ObjectStoragePort;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.application.service.ProductQueryService;
import com.example.product.presentation.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 상세 imageUrls + 목록 thumbnailUrl 의 presigned GET 배선(key → URL) 검증. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductQueryController")
class ProductQueryControllerTest {

    @Mock ProductQueryService queryService;
    @Mock ObjectStoragePort port;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ProductQueryController controller = new ProductQueryController(queryService, port);
        mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void detail_maps_image_keys_to_presigned_urls() throws Exception {
        when(queryService.detail(1L)).thenReturn(new ProductQueryService.ProductDetail(
                1L, "상품", List.of(), List.of(), List.of(new ProductQueryService.ImageRef(9L, "k1")), List.of(), List.of()));
        when(port.presignDownload("k1")).thenReturn("http://minio/get/k1");

        mvc.perform(get("/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].id").value(9))
                .andExpect(jsonPath("$.images[0].url").value("http://minio/get/k1"));
    }

    @Test
    @DisplayName("GET /v1/products/{id} — skus[].skuId(numeric) 노출")
    void detail_exposes_numeric_skuId() throws Exception {
        var detail = new ProductQueryService.ProductDetail(
                7L, "베이직 티셔츠",
                List.of(new ProductQueryService.CategoryPathNode(3, 3L, "티셔츠")),
                List.of(new ProductQueryService.SkuDetail(42L, "TS-BLK-M", "블랙/M", 10, 29000L, java.util.Map.of())),
                List.of(), List.of(), List.of());
        when(queryService.detail(7L)).thenReturn(detail);

        mvc.perform(get("/v1/products/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skus[0].skuId").value(42))
                .andExpect(jsonPath("$.skus[0].skuCode").value("TS-BLK-M"))
                .andExpect(jsonPath("$.skus[0].price").value(29000));
    }

    @Test
    void detail_returns_empty_images_when_no_images() throws Exception {
        when(queryService.detail(2L)).thenReturn(new ProductQueryService.ProductDetail(
                2L, "상품2", List.of(), List.of(), List.of(), List.of(), List.of()));

        mvc.perform(get("/v1/products/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images").isEmpty());
    }

    @Test
    void list_maps_thumbnail_key_to_url() throws Exception {
        Page<ProductQueryRepository.ProductCard> page = new PageImpl<>(
                List.of(new ProductQueryRepository.ProductCard(1L, "상품", 1000L, "t1")),
                PageRequest.of(0, 20), 1);
        when(queryService.listCards(5L, 0, 20)).thenReturn(page);
        when(port.presignDownload("t1")).thenReturn("http://minio/get/t1");

        mvc.perform(get("/v1/categories/5/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].thumbnailUrl").value("http://minio/get/t1"));
    }

    @Test
    void list_returns_null_thumbnailUrl_when_no_image() throws Exception {
        Page<ProductQueryRepository.ProductCard> page = new PageImpl<>(
                List.of(new ProductQueryRepository.ProductCard(2L, "상품2", 2000L, null)),
                PageRequest.of(0, 20), 1);
        when(queryService.listCards(6L, 0, 20)).thenReturn(page);

        mvc.perform(get("/v1/categories/6/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].thumbnailUrl").isEmpty());
    }
}
