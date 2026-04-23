package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.exception.ServiceUnavailableException;
import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ValidateAndReserveService implements ValidateAndReserveUseCase {

    private final MerchantCancelUsageRepository usageRepository;
    private final CancelUsageHistoryRepository historyRepository;
    private final MerchantLimitClient merchantLimitClient;
    private final DailyLimitCache dailyLimitCache;
    private final CancelLimitDomainService domainService;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${risk.lock.ttl-seconds:5}")
    private long lockTtlSeconds;

    @Override
    public Result execute(Command cmd) {
        String lockKey = "lock:risk:merchant:" + cmd.merchantId();
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "locked", Duration.ofSeconds(lockTtlSeconds));
        if (!Boolean.TRUE.equals(acquired)) {
            throw ServiceUnavailableException.riskServiceUnavailable();
        }

        try {
            // transactionTemplate.execute() 완료(TX 커밋) 후 finally에서 락 해제
            return transactionTemplate.execute(status -> {
                // 이중 차감 방어 — cancelRequestId UK
                Optional<CancelUsageHistory> existing =
                    historyRepository.findByCancelRequestId(cmd.cancelRequestId());
                if (existing.isPresent()) {
                    MerchantCancelUsage usage = usageRepository
                        .findByMerchantIdAndKstDate(cmd.merchantId(), cmd.kstDate())
                        .orElseThrow();
                    return toResult(usage);
                }

                // 1회 조회로 DB 스냅샷과 upsert를 함께 처리
                Optional<MerchantCancelUsage> usageOpt =
                    usageRepository.findByMerchantIdAndKstDate(cmd.merchantId(), cmd.kstDate());

                BigDecimal dailyLimit = resolveDailyLimit(cmd.merchantId(), cmd.kstDate(), usageOpt);

                MerchantCancelUsage usage = usageOpt
                    .orElseGet(() -> MerchantCancelUsage.create(
                        cmd.merchantId(), cmd.kstDate(), dailyLimit));

                domainService.validateAndDeduct(usage, cmd.cancelAmount());
                usageRepository.save(usage);
                historyRepository.save(CancelUsageHistory.record(
                    cmd.cancelRequestId(), cmd.merchantId(), cmd.kstDate(), cmd.cancelAmount()));

                return toResult(usage);
            });
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * daily_limit 3단계 조회:
     * 1. Redis: daily_limit:{merchantId}:{kstDate}
     * 2. DB 스냅샷: usageOpt.dailyLimit (이미 조회한 결과 재사용 — 추가 DB 호출 없음)
     * 3. HTTP: merchantLimitClient (Resilience4j CB 적용)
     */
    private BigDecimal resolveDailyLimit(
            long merchantId, LocalDate kstDate, Optional<MerchantCancelUsage> usageOpt) {
        Optional<BigDecimal> cached = dailyLimitCache.get(merchantId, kstDate);
        if (cached.isPresent()) return cached.get();

        if (usageOpt.isPresent()) return usageOpt.get().getDailyLimit();

        return merchantLimitClient.fetchDailyLimit(merchantId, kstDate);
    }

    private Result toResult(MerchantCancelUsage usage) {
        return new Result(
            usage.getMerchantId(), usage.getDailyLimit(),
            usage.getUsedAmount(), usage.remaining());
    }
}
