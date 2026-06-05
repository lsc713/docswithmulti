package com.example.product.application.service;

import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.ProductVersionRepository;
import com.example.product.application.usecase.CreateProductUseCase;
import com.example.product.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreateProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVersionRepository productVersionRepository;

    @Mock
    private ProductSkuRepository productSkuRepository;

    @Mock
    private ProductStockRepository productStockRepository;

    private CreateProductService createProductService;

    @BeforeEach
    void setUp() {
        createProductService = new CreateProductService(
                productRepository, productVersionRepository,
                productSkuRepository, productStockRepository);
    }

    @Test
    @DisplayName("상품 + 버전 + SKU + 재고를 원자적으로 생성")
    void createProductWithSkuAndStock() {
        // given
        Product savedProduct = Product.reconstruct(10L, 1L, 2L, ProductStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now());
        ProductVersion savedVersion = ProductVersion.reconstruct(
                100L, 10L, "테스트 상품", BigDecimal.valueOf(10000), BigDecimal.valueOf(9000),
                "{}", 1, true, LocalDateTime.now());
        ProductSku savedSku = ProductSku.reconstruct(1000L, 100L, "RED", "M",
                "SKU-10-RED-M", LocalDateTime.now());
        ProductStock savedStock = ProductStock.reconstruct(10000L, 1000L, 50, LocalDateTime.now());

        given(productRepository.save(any())).willReturn(savedProduct);
        given(productVersionRepository.save(any())).willReturn(savedVersion);
        given(productSkuRepository.save(any())).willReturn(savedSku);
        given(productStockRepository.save(any())).willReturn(savedStock);

        CreateProductUseCase.Command command = new CreateProductUseCase.Command(
                1L, 2L, "테스트 상품",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(9000), "{}",
                List.of(new CreateProductUseCase.SkuInput("RED", "M", 50))
        );

        // when
        CreateProductUseCase.Result result = createProductService.execute(command);

        // then
        assertThat(result.product().getId()).isEqualTo(10L);
        assertThat(result.version().getId()).isEqualTo(100L);
        assertThat(result.skus()).hasSize(1);
        assertThat(result.skus().get(0).getId()).isEqualTo(1000L);

        verify(productRepository).save(any());
        verify(productVersionRepository).save(any());
        verify(productSkuRepository, times(1)).save(any());
        verify(productStockRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("SKU 2개 생성 시 SKU와 재고 각각 2번 저장")
    void createProductWithMultipleSkus() {
        // given
        Product savedProduct = Product.reconstruct(10L, 1L, 2L, ProductStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now());
        ProductVersion savedVersion = ProductVersion.reconstruct(
                100L, 10L, "테스트 상품", BigDecimal.valueOf(10000), null,
                null, 1, true, LocalDateTime.now());
        ProductSku sku1 = ProductSku.reconstruct(1001L, 100L, "RED", "M", "SKU-10-RED-M", LocalDateTime.now());
        ProductSku sku2 = ProductSku.reconstruct(1002L, 100L, "BLUE", "L", "SKU-10-BLUE-L", LocalDateTime.now());
        ProductStock stock1 = ProductStock.reconstruct(10001L, 1001L, 30, LocalDateTime.now());
        ProductStock stock2 = ProductStock.reconstruct(10002L, 1002L, 20, LocalDateTime.now());

        given(productRepository.save(any())).willReturn(savedProduct);
        given(productVersionRepository.save(any())).willReturn(savedVersion);
        given(productSkuRepository.save(any()))
                .willReturn(sku1)
                .willReturn(sku2);
        given(productStockRepository.save(any()))
                .willReturn(stock1)
                .willReturn(stock2);

        CreateProductUseCase.Command command = new CreateProductUseCase.Command(
                1L, 2L, "테스트 상품",
                BigDecimal.valueOf(10000), null, null,
                List.of(
                        new CreateProductUseCase.SkuInput("RED", "M", 30),
                        new CreateProductUseCase.SkuInput("BLUE", "L", 20)
                )
        );

        // when
        CreateProductUseCase.Result result = createProductService.execute(command);

        // then
        assertThat(result.skus()).hasSize(2);
        verify(productSkuRepository, times(2)).save(any());
        verify(productStockRepository, times(2)).save(any());
    }
}
