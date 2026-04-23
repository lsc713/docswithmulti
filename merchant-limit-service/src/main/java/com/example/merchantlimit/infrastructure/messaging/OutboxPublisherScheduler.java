package com.example.merchantlimit.infrastructure.messaging;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final LimitEventOutboxRepository outboxRepository;
    private final LimitEventKafkaProducer kafkaProducer;
    private final StringRedisTemplate redisTemplate;

    @Value("${outbox.scheduler.batch-size:1000}")
    private int batchSize;

    @Value("${outbox.scheduler.lock-key}")
    private String lockKey;

    @Value("${outbox.scheduler.lock-ttl-seconds:9}")
    private long lockTtlSeconds;

    @Scheduled(fixedDelay = 10_000)
    public void publish() {
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "locked", Duration.ofSeconds(lockTtlSeconds));

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Outbox 스케줄러 락 획득 실패 — 다른 인스턴스가 실행 중");
            return;
        }

        try {
            List<LimitEventOutboxRepository.PendingOutbox> pending =
                outboxRepository.findPendingBatch(batchSize);

            for (LimitEventOutboxRepository.PendingOutbox outbox : pending) {
                try {
                    kafkaProducer.publish(outbox.merchantId(), outbox.payload());
                    outboxRepository.markPublished(outbox.id());
                } catch (Exception e) {
                    log.error("Outbox 발행 실패. outboxId={}", outbox.id(), e);
                }
            }

            if (!pending.isEmpty()) {
                log.info("Outbox 발행 완료. count={}", pending.size());
            }
        } finally {
            redisTemplate.delete(lockKey);
        }
    }
}
