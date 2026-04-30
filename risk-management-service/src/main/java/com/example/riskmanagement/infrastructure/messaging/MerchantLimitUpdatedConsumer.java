package com.example.riskmanagement.infrastructure.messaging;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.example.riskmanagement.application.interfaces.MerchantLimitClient;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantLimitUpdatedConsumer {

    private final DailyLimitCache dailyLimitCache;
    private final MerchantCancelUsageRepository usageRepository;
    private final MerchantLimitClient merchantLimitClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @KafkaListener(
        topics = "${kafka.topic.merchant-limit-updated}",
        groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            MerchantLimitUpdatedPayload payload =
                objectMapper.readValue(record.value(), MerchantLimitUpdatedPayload.class);

            LocalDate kstToday = LocalDate.now(ZoneId.of("Asia/Seoul"));

            // { merchantId }만 수신 → API로 최신 한도 조회
            BigDecimal newLimit = merchantLimitClient.fetchDailyLimit(payload.merchantId(), kstToday);

            // 1. Redis 갱신 (자연 멱등)
            dailyLimitCache.set(payload.merchantId(), kstToday, newLimit);

            // 2. DB 스냅샷 갱신 (행 있을 때만)
            transactionTemplate.execute(status ->
                usageRepository.findByMerchantIdAndKstDate(payload.merchantId(), kstToday)
                    .map(usage -> {
                        usage.updateDailyLimit(newLimit);
                        return usageRepository.save(usage);
                    })
                    .orElse(null));

            ack.acknowledge();
            log.debug("merchant.limit.updated 처리 완료. merchantId={}, kstDate={}",
                payload.merchantId(), kstToday);

        } catch (Exception e) {
            log.error("merchant.limit.updated 처리 실패. offset={}, value={}",
                record.offset(), record.value(), e);
            ack.acknowledge();
        }
    }
}
