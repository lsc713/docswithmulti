package com.example.product.presentation.controller;

import com.example.product.application.interfaces.ObjectStoragePort;
import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.application.service.ProductImageService;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductImage;
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

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductImageController")
class ProductImageControllerTest {

    @Mock ObjectStoragePort port;
    @Mock ProductImageRepository imageRepository;
    @Mock ProductQueryRepository productQueryRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ProductImageService service = new ProductImageService(productQueryRepository, imageRepository, port);
        ProductImageController controller = new ProductImageController(service);

        // 대부분의 테스트는 상품이 존재하는 경로를 검증 — 필요 없는 케이스(가드 선-차단)에서는 unused stub이라 lenient.
        lenient().when(productQueryRepository.findProductById(1L))
                .thenReturn(Optional.of(Product.reconstruct(1L, "상품", 10L, Instant.now(), Instant.now())));

        mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void presign_requires_admin() throws Exception {
        mvc.perform(post("/v1/products/1/images/presign").header("X-User-Role", "MERCHANT")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void presign_requires_admin_when_role_header_missing() throws Exception {
        mvc.perform(post("/v1/products/1/images/presign")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void presign_returns_key_and_url_for_admin() throws Exception {
        when(port.presignUpload(anyString(), eq("image/jpeg")))
                .thenReturn(new ObjectStoragePort.PresignedUpload("http://minio/put"));
        mvc.perform(post("/v1/products/1/images/presign").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("http://minio/put"))
                .andExpect(jsonPath("$.key").exists());
    }

    @Test
    void confirm_rejects_missing_object() throws Exception {
        when(port.exists("k")).thenReturn(false);
        mvc.perform(post("/v1/products/1/images").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"k\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirm_returns_image_id_for_admin() throws Exception {
        when(port.exists("k")).thenReturn(true);
        when(imageRepository.nextSortOrder(1L)).thenReturn(0);
        when(imageRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ProductImage.reconstruct(99L, 1L, "k", 0, Instant.now()));

        mvc.perform(post("/v1/products/1/images").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"k\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(99));
    }

    @Test
    void delete_removes_row_and_object_for_admin() throws Exception {
        when(imageRepository.findByIdAndProductId(9L, 1L))
                .thenReturn(Optional.of(ProductImage.reconstruct(9L, 1L, "k9", 0, Instant.now())));
        mvc.perform(delete("/v1/products/1/images/9").header("X-User-Role", "ADMIN"))
                .andExpect(status().isNoContent());
        verify(imageRepository).deleteByIdAndProductId(9L, 1L);
        verify(port).delete("k9");
    }

    @Test
    void delete_requires_admin() throws Exception {
        mvc.perform(delete("/v1/products/1/images/9").header("X-User-Role", "MERCHANT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_missing_image_returns_404() throws Exception {
        when(imageRepository.findByIdAndProductId(9L, 1L)).thenReturn(Optional.empty());
        mvc.perform(delete("/v1/products/1/images/9").header("X-User-Role", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reorder_requires_admin() throws Exception {
        mvc.perform(put("/v1/products/1/images/order").header("X-User-Role", "MERCHANT")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[2,1]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reorder_delegates_to_repository_for_admin() throws Exception {
        mvc.perform(put("/v1/products/1/images/order").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageIds\":[2,1]}"))
                .andExpect(status().isOk());
        verify(imageRepository).updateOrder(1L, java.util.List.of(2L, 1L));
    }
}
