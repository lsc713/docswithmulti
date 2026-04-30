package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.FailedKafkaPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * failed_kafka_event PENDING 건을 Kafka에 재발행하는 스케줄러.
 * Redis 분산락으로 중복 실행 방지 (30초 주기).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailedKafkaPublisherScheduler {

    private final RedissonClient redissonClient;
    private final FailedKafkaPublisherService failedKafkaPublisherService;

    @Value("${scheduler.lock.failed-kafka-publisher}")
    private String lockKey;

    @Scheduled(fixedDelay = 30_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 25, TimeUnit.SECONDS)) {
                log.debug("[failed-kafka-publisher] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[failed-kafka-publisher] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            failedKafkaPublisherService.publish();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
