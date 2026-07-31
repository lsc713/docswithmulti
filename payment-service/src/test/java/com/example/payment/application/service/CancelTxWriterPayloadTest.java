package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.application.interfaces.RiskManagementPort;
import com.example.payment.domain.entity.*;
import com.example.payment.infrastructure.persistence.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RST-01 무회귀 게이트: CancelTxWriter.buildPayload 가 만든 payment.cancelled payload 의
 * cancelledItems[] 에 skuId·quantity 가 정확히 실리는지 단언한다.
 *
 * <p>취소 코어를 새로 만들지 않고 CancelFlowIntegrationTest 의 조립(INLINE 모드 +
 * @MockitoBean KafkaTemplate)을 복제해, INLINE 발행 시 kafkaTemplate.send(topic, key, payload)
 * 의 3번째 인자(payload)를 ArgumentCaptor 로 캡처한다.
 *
 * <p>skuId 가 null 인 하위호환 아이템은 JSON null 로 직렬화됨을 함께 검증한다.
 */
@Testcontainers
@SpringBootTest(properties = "cancel.publish.mode=INLINE")
@DisplayName("CancelTxWriter payload — cancelledItems 에 skuId/quantity (RST-01)")
class CancelTxWriterPayloadTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @MockitoBean RiskManagementPort riskManagementPort;
    @MockitoBean PgCancelPort pgCancelPort;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean RedissonClient redissonClient;

    @Autowired PaymentJpaRepository paymentJpaRepository;
    @Autowired PaymentItemJpaRepository paymentItemJpaRepository;
    @Autowired CancelRequestJpaRepository cancelRequestJpaRepository;
    @Autowired CancelPaymentService cancelPaymentService;

    private long paymentId;
    private long itemWithSkuId;   // skuId=100, quantity=3
    private long itemNullSkuId;   // skuId=null, quantity=1 (하위호환)

    @BeforeEach
    void insertTestData() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));

        PaymentJpaEntity savedPayment = paymentJpaRepository.save(
            PaymentJpaEntity.from(
                Payment.of("pl_pay_001", 1L, 1L, "TOSS",
                    BigDecimal.valueOf(100_000), "KRW", 90)
            )
        );
        paymentId = savedPayment.getId();

        // skuId·quantity 를 가진 아이템 (8인자 of)
        itemWithSkuId = paymentItemJpaRepository.save(
            PaymentItemJpaEntity.from(
                PaymentItem.of(paymentId, 10L, 1L, 2L, "상품A",
                    BigDecimal.valueOf(30_000), 100L, 3)
            )
        ).getId();

        // skuId=null 하위호환 아이템 (6인자 of → skuId=null, quantity=1)
        itemNullSkuId = paymentItemJpaRepository.save(
            PaymentItemJpaEntity.from(
                PaymentItem.of(paymentId, 11L, 1L, 2L, "상품B", BigDecimal.valueOf(70_000))
            )
        ).getId();
    }

    @AfterEach
    void cleanup() {
        cancelRequestJpaRepository.deleteAll();
        paymentItemJpaRepository.deleteAll();
        paymentJpaRepository.deleteAll();
    }

    @Test
    @DisplayName("payload cancelledItems 에 skuId/quantity 가 실리고 null skuId 는 JSON null")
    void payloadCarriesSkuIdAndQuantity() {
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L,
                BigDecimal.valueOf(10_000_000),
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(9_900_000)));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(PgCancelResult.approved("pg-tx-pl-001"));

        // 두 아이템 전액 취소 → 두 아이템 모두 cancelledItems 에 포함
        cancelPaymentService.cancel(new CancelPaymentCommand(
            "pl_pay_001", "payload 검증", List.of(itemWithSkuId, itemNullSkuId), null));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();

        // skuId·quantity 가 실린 아이템
        assertThat(payload).contains("\"skuId\":100,\"quantity\":3");
        // 하위호환: skuId=null 아이템은 JSON null, quantity=1
        assertThat(payload).contains("\"skuId\":null,\"quantity\":1");
        // 기존 필드도 보존
        assertThat(payload).contains("\"paymentItemId\":" + itemWithSkuId);
        assertThat(payload).contains("\"orderItemId\":10");
    }
}
