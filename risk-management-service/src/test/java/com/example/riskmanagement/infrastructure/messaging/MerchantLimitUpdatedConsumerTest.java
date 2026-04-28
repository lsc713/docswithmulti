package com.example.riskmanagement.infrastructure.messaging;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
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
    @Mock TransactionTemplate transactionTemplate;
    @Mock Acknowledgment ack;
    @Mock MerchantCancelUsage usage;

    MerchantLimitUpdatedConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final long MERCHANT_ID = 1L;
    private static final LocalDate KST_DATE = LocalDate.of(2026, 4, 28);
    private static final BigDecimal NEW_LIMIT = new BigDecimal("5000000");

    @BeforeEach
    void setUp() {
        consumer = new MerchantLimitUpdatedConsumer(
            dailyLimitCache, usageRepository, objectMapper, transactionTemplate);

        // TransactionTemplate.execute() → 콜백을 직접 실행
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("merchant.limit.updated", 0, 0L, String.valueOf(MERCHANT_ID), value);
    }

    private String payload() {
        return "{\"merchantId\":" + MERCHANT_ID +
            ",\"newLimit\":" + NEW_LIMIT.toPlainString() +
            ",\"kstDate\":\"" + KST_DATE + "\"}";
    }

    @Test
    @DisplayName("정상 처리 — Redis 갱신 + DB usage 존재 시 updateDailyLimit 호출 + ack")
    void consume_success_with_existing_usage() {
        when(usageRepository.findByMerchantIdAndKstDate(MERCHANT_ID, KST_DATE))
            .thenReturn(Optional.of(usage));
        when(usageRepository.save(usage)).thenReturn(usage);

        consumer.consume(record(payload()), ack);

        verify(dailyLimitCache).set(MERCHANT_ID, KST_DATE, NEW_LIMIT);
        verify(usage).updateDailyLimit(NEW_LIMIT);
        verify(usageRepository).save(usage);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("정상 처리 — usage 없음 시 Redis만 갱신 + ack (DB 업데이트 없음)")
    void consume_success_without_existing_usage() {
        when(usageRepository.findByMerchantIdAndKstDate(MERCHANT_ID, KST_DATE))
            .thenReturn(Optional.empty());

        consumer.consume(record(payload()), ack);

        verify(dailyLimitCache).set(MERCHANT_ID, KST_DATE, NEW_LIMIT);
        verify(usageRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("JSON 파싱 실패 — 예외 처리 후 ack (멱등)")
    void consume_invalid_json_acks_without_retry() {
        consumer.consume(record("NOT_VALID_JSON"), ack);

        verify(dailyLimitCache, never()).set(anyLong(), any(), any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Redis 갱신 실패 — 예외 처리 후 ack (멱등)")
    void consume_redis_failure_acks_without_retry() {
        doThrow(new RuntimeException("Redis error"))
            .when(dailyLimitCache).set(anyLong(), any(), any());

        consumer.consume(record(payload()), ack);

        verify(ack).acknowledge();
    }
}
