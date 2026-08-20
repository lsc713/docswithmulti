package com.example.product.integration;

import com.example.product.application.service.ProductQueryService;
import com.example.product.application.service.StockService;
import com.example.product.common.exception.application.StockInsufficientException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@ContextConfiguration(classes = ProductStockSnapshotIntegrationTest.RedisTestConfiguration.class)
@Testcontainers
class ProductStockSnapshotIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("product_db")
            .withUsername("product")
            .withPassword("product")
            .withUrlParam("useAffectedRows", "true");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductQueryService productQueryService;
    @Autowired StockService stockService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired RedissonClient redisson;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RBucket<Map<Long, Integer>> stockBucket = mock(RBucket.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        reset(redisson, stockBucket);
    }

    @Test
    void detail_merges_mysql_stock_when_redis_is_unavailable() throws Exception {
        long skuId = seedSku("SNAP-1", 3);
        long productId = productId(skuId);
        when(redisson.getBucket("product:stock:" + productId)).thenThrow(new RedisException("down"));

        assertThat(availableQty(productId, skuId)).isEqualTo(3);

        stockService.reserve("snapshot-reserve", List.of(new StockService.ReserveItem(productId, skuId, 3)));
        assertThat(availableQty(productId, skuId)).isZero();

        stockService.release("snapshot-reserve", List.of(new StockService.ReserveItem(skuId, 3)));
        assertThat(availableQty(productId, skuId)).isEqualTo(3);

        assertThatThrownBy(() -> stockService.reserve("snapshot-failed",
                List.of(new StockService.ReserveItem(productId, skuId, 4))))
                .isInstanceOf(StockInsufficientException.class);
        assertThat(availableQty(productId, skuId)).isEqualTo(3);
    }

    @Test
    void refreshes_only_after_commit_and_skips_failed_or_noop_mutations() throws Exception {
        long skuId = seedSku("SNAP-2", 3);
        long productId = productId(skuId);
        when(redisson.<Map<Long, Integer>>getBucket("product:stock:" + productId)).thenReturn(stockBucket);

        transactionTemplate.executeWithoutResult(status -> {
            stockService.reserve("snapshot-commit", List.of(new StockService.ReserveItem(productId, skuId, 3)));
            verifyNoInteractions(stockBucket);
        });
        verify(stockBucket).set(eq(Map.of(skuId, 0)), eq(5L), eq(java.util.concurrent.TimeUnit.SECONDS));

        clearInvocations(stockBucket);
        assertThatThrownBy(() -> stockService.reserve("snapshot-failed",
                List.of(new StockService.ReserveItem(productId, skuId, 1))))
                .isInstanceOf(StockInsufficientException.class);
        verifyNoInteractions(stockBucket);

        stockService.release("snapshot-commit", List.of(new StockService.ReserveItem(skuId, 3)));
        verify(stockBucket).set(eq(Map.of(skuId, 3)), eq(5L), eq(java.util.concurrent.TimeUnit.SECONDS));

        clearInvocations(stockBucket);
        stockService.release("snapshot-commit", List.of(new StockService.ReserveItem(skuId, 3)));
        verifyNoInteractions(stockBucket);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisTestConfiguration {
        @Bean
        @Primary
        RedissonClient testRedissonClient() {
            return mock(RedissonClient.class);
        }
    }

    private long seedSku(String code, int stock) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "ADMIN")
                        .content("""
                                {"name":"snapshot product","categoryId":%d,
                                "skus":[{"skuCode":"%s","optionSummary":"opt","initialStock":%d,"price":1000}]}"""
                                .formatted(CategoryFixtures.leafId(jdbc), code, stock)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        Map<?, ?> body = objectMapper.readValue(response.getContentAsString(), Map.class);
        return ((Number) ((Map<?, ?>) ((List<?>) body.get("skus")).get(0)).get("skuId")).longValue();
    }

    private long productId(long skuId) {
        return jdbc.queryForObject("SELECT product_id FROM product_sku WHERE id = ?", Long.class, skuId);
    }

    private int availableQty(long productId, long skuId) {
        return productQueryService.detail(productId).skus().stream()
                .filter(sku -> sku.skuId().equals(skuId))
                .map(ProductQueryService.SkuDetail::availableQty)
                .findFirst()
                .orElseThrow();
    }
}
