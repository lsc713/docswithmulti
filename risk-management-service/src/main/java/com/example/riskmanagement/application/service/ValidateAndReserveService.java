package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.exception.DataInconsistencyException;
import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.exception.MerchantCancelLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 취소 한도 검증 + 예약(차감).
 *
 * <p>동시성 처리: merchant별 Redis 분산락(fail-fast) 대신 <b>DB 원자 조건부 UPDATE</b>로 전환.
 * 락을 트랜잭션 통째로 쥐던 임계구역이 단일 문장(µs)으로 축소 — 핫 merchant 취소가
 * 대량 거부(RISK_SERVICE_UNAVAILABLE)되던 문제 해소. 정합성은 {@code tryDeduct}의
 * WHERE 절(도메인 규칙 미러) + history UK(멱등)로 보장. 서버 대수와 무관하게 안전.
 *
 * <p>참고: {@code resolveDailyLimit}의 HTTP 호출은 의도적으로 TX 안에 둔다(비용 실측 후 분리 예정).
 * onset(그날 첫 취소)에만 타며, 커넥션 점유 비용을 관측하기 위함.
 */
@Service
public class ValidateAndReserveService implements ValidateAndReserveUseCase {

    private final MerchantCancelUsageRepository usageRepository;
    private final CancelUsageHistoryRepository historyRepository;
    private final MerchantLimitClient merchantLimitClient;
    private final DailyLimitCache dailyLimitCache;
    private final TransactionTemplate transactionTemplate;

    // 실험 플래그 — daily_limit 3단 해석(Redis 캐시 → DB 스냅샷 → merchant-limit HTTP)에서
    // 앞 단계를 꺼서 각 층 비용을 실측하기 위함. 기본 true(현행).
    //   cache=false            → 캐시 우회, DB 스냅샷/HTTP 로 부하가 흐름
    //   cache=false+snapshot=false → 매 요청 HTTP (HTTP-in-TX 커넥션 점유 비용 측정)
    private final boolean cacheEnabled;
    private final boolean snapshotEnabled;

    public ValidateAndReserveService(
            MerchantCancelUsageRepository usageRepository,
            CancelUsageHistoryRepository historyRepository,
            MerchantLimitClient merchantLimitClient,
            DailyLimitCache dailyLimitCache,
            TransactionTemplate transactionTemplate,
            @Value("${risk.limit.cache.enabled:true}") boolean cacheEnabled,
            @Value("${risk.limit.snapshot.enabled:true}") boolean snapshotEnabled) {
        this.usageRepository = usageRepository;
        this.historyRepository = historyRepository;
        this.merchantLimitClient = merchantLimitClient;
        this.dailyLimitCache = dailyLimitCache;
        this.transactionTemplate = transactionTemplate;
        this.cacheEnabled = cacheEnabled;
        this.snapshotEnabled = snapshotEnabled;
    }

    @Override
    public Result execute(Command cmd) {
        return transactionTemplate.execute(status -> {
            // 1) 멱등 — 이미 차감된 요청이면 재차감 없이 기존 결과 재생
            if (historyRepository.findByCancelRequestId(cmd.cancelRequestId()).isPresent()) {
                return toResult(readUsage(cmd.merchantId(), cmd.kstDate()));
            }

            // 2) daily_limit 해석: Redis → DB 스냅샷 → merchant-limit HTTP (락/FOR UPDATE 없음)
            BigDecimal dailyLimit = resolveDailyLimit(cmd.merchantId(), cmd.kstDate());

            // 3) 행 보장 + 원자 조건부 차감 (임계구역 = 단일 문장)
            usageRepository.ensureRow(cmd.merchantId(), cmd.kstDate(), dailyLimit);
            int deducted = usageRepository.tryDeduct(cmd.merchantId(), cmd.kstDate(), cmd.cancelAmount());
            if (deducted == 0) {
                MerchantCancelUsage cur = readUsage(cmd.merchantId(), cmd.kstDate());
                throw new MerchantCancelLimitExceededException(
                    cur.getDailyLimit(), cur.getUsedAmount(), cmd.cancelAmount());
            }

            // 4) 멱등 원장 — 차감과 원자적(같은 TX). UK 위반 시 함께 롤백 → 이중차감 방어
            historyRepository.save(CancelUsageHistory.record(
                cmd.cancelRequestId(), cmd.merchantId(), cmd.kstDate(), cmd.cancelAmount()));

            // 5) 결과 — 차감 후 최신값(tryDeduct의 clearAutomatically로 stale 캐시 아님)
            return toResult(readUsage(cmd.merchantId(), cmd.kstDate()));
        });
    }

    /**
     * daily_limit 3단계 조회 (2순위 건너뛰고 3순위 호출 금지):
     * 1. Redis 캐시  2. DB 스냅샷(non-locking read)  3. merchant-limit HTTP(CB)
     */
    private BigDecimal resolveDailyLimit(long merchantId, LocalDate kstDate) {
        if (cacheEnabled) {
            Optional<BigDecimal> cached = dailyLimitCache.get(merchantId, kstDate);
            if (cached.isPresent()) return cached.get();
        }
        if (snapshotEnabled) {
            Optional<MerchantCancelUsage> snapshot =
                usageRepository.findByMerchantIdAndKstDate(merchantId, kstDate);
            if (snapshot.isPresent()) return snapshot.get().getDailyLimit();
        }
        BigDecimal fetched = merchantLimitClient.fetchDailyLimit(merchantId, kstDate);
        if (cacheEnabled) dailyLimitCache.set(merchantId, kstDate, fetched);
        return fetched;
    }

    private MerchantCancelUsage readUsage(long merchantId, LocalDate kstDate) {
        return usageRepository.findByMerchantIdAndKstDate(merchantId, kstDate)
            .orElseThrow(() -> new DataInconsistencyException(
                "MerchantCancelUsage not found: merchantId=" + merchantId + ", kstDate=" + kstDate));
    }

    private Result toResult(MerchantCancelUsage usage) {
        return new Result(
            usage.getMerchantId(), usage.getDailyLimit(),
            usage.getUsedAmount(), usage.remaining());
    }
}
