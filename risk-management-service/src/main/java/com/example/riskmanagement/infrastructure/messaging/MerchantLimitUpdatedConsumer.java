package com.example.riskmanagement.infrastructure.messaging;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantLimitUpdatedConsumer {

    private final DailyLimitCache dailyLimitCache;
    private final MerchantCancelUsageRepository usageRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(
        topics = "${kafka.topic.merchant-limit-updated}",
        groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            MerchantLimitUpdatedPayload payload =
                objectMapper.readValue(record.value(), MerchantLimitUpdatedPayload.class);

            // 1. Redis 갱신 (TTL 25h) — 자연 멱등
            dailyLimitCache.set(payload.merchantId(), payload.kstDate(), payload.newLimit());

            // 2. DB 스냅샷 갱신 (행 있을 때만) — 자연 멱등, TX 필수
            transactionTemplate.execute(status ->
                usageRepository.findByMerchantIdAndKstDate(payload.merchantId(), payload.kstDate())
                    .map(usage -> {
                        usage.updateDailyLimit(payload.newLimit());
                        return usageRepository.save(usage);
                    })
                    .orElse(null));

            ack.acknowledge();
            log.debug("merchant.limit.updated 처리 완료. merchantId={}, kstDate={}",
                payload.merchantId(), payload.kstDate());

        } catch (Exception e) {
            log.error("merchant.limit.updated 처리 실패. offset={}, value={}",
                record.offset(), record.value(), e);
            ack.acknowledge(); // idempotent — ack 후 넘어감 (3순위 HTTP fallback 보장)
        }
    }
}
