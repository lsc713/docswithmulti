package com.example.riskmanagement.infrastructure.messaging;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.example.riskmanagement.application.interfaces.MerchantLimitClient;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MerchantLimitUpdatedConsumer")
class MerchantLimitUpdatedConsumerTest {

    @Mock DailyLimitCache dailyLimitCache;
    @Mock MerchantCancelUsageRepository usageRepository;
    @Mock MerchantLimitClient merchantLimitClient;
    @Mock TransactionTemplate transactionTemplate;
    @Mock Acknowledgment ack;
    @Mock MerchantCancelUsage usage;

    MerchantLimitUpdatedConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final long MERCHANT_ID = 1L;
    private static final BigDecimal FETCHED_LIMIT = new BigDecimal("5000000");

    @BeforeEach
    void setUp() {
        consumer = new MerchantLimitUpdatedConsumer(
            dailyLimitCache, usageRepository, merchantLimitClient, objectMapper, transactionTemplate);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("merchant.limit.updated", 0, 0L,
            String.valueOf(MERCHANT_ID), value);
    }

    @Test
    @DisplayName("정상 처리 — API 조회 후 Redis 갱신 + DB usage 존재 시 update + ack")
    void consume_success_with_existing_usage() {
        when(merchantLimitClient.fetchDailyLimit(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenReturn(FETCHED_LIMIT);
        when(usageRepository.findByMerchantIdAndKstDate(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenReturn(Optional.of(usage));
        when(usageRepository.save(usage)).thenReturn(usage);

        consumer.consume(record("{\"merchantId\":1}"), ack);

        verify(merchantLimitClient).fetchDailyLimit(eq(MERCHANT_ID), any(LocalDate.class));
        verify(dailyLimitCache).set(eq(MERCHANT_ID), any(LocalDate.class), eq(FETCHED_LIMIT));
        verify(usage).updateDailyLimit(FETCHED_LIMIT);
        verify(usageRepository).save(usage);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("정상 처리 — usage 없으면 Redis만 갱신 + ack")
    void consume_success_without_existing_usage() {
        when(merchantLimitClient.fetchDailyLimit(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenReturn(FETCHED_LIMIT);
        when(usageRepository.findByMerchantIdAndKstDate(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenReturn(Optional.empty());

        consumer.consume(record("{\"merchantId\":1}"), ack);

        verify(dailyLimitCache).set(eq(MERCHANT_ID), any(LocalDate.class), eq(FETCHED_LIMIT));
        verify(usageRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("JSON 파싱 실패 — ack (멱등)")
    void consume_invalid_json_acks() {
        consumer.consume(record("NOT_JSON"), ack);

        verifyNoInteractions(merchantLimitClient, dailyLimitCache);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("API 조회 실패 — ack (3순위 HTTP fallback 보장)")
    void consume_api_failure_acks() {
        when(merchantLimitClient.fetchDailyLimit(eq(MERCHANT_ID), any(LocalDate.class)))
            .thenThrow(new RuntimeException("API error"));

        consumer.consume(record("{\"merchantId\":1}"), ack);

        verifyNoInteractions(dailyLimitCache);
        verify(ack).acknowledge();
    }
}
