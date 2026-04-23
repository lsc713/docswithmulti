package com.example.merchantlimit.application.service;

import com.example.merchantlimit.application.interfaces.*;
import com.example.merchantlimit.application.usecase.UpdateCancelLimitUseCase;
import com.example.merchantlimit.domain.entity.LimitHistory;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import com.example.merchantlimit.domain.exception.MerchantNotFoundException;
import com.example.merchantlimit.domain.service.MerchantLimitDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class UpdateCancelLimitService implements UpdateCancelLimitUseCase {

    private final MerchantRepository merchantRepository;
    private final MerchantCancelLimitRepository limitRepository;
    private final LimitHistoryRepository historyRepository;
    private final LimitEventOutboxRepository outboxRepository;
    private final MerchantLimitDomainService domainService;

    @Override
    @Transactional
    public Result execute(long merchantId, BigDecimal newLimit, String reason) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        MerchantCancelLimit limit = limitRepository.findByMerchantId(merchantId)
            .orElse(null);

        BigDecimal oldLimit = limit != null ? limit.getDailyLimit() : null;

        if (limit == null) {
            // 최초 설정 — 도메인 검증 후 신규 생성
            merchant.validateLimitChangeable();
            limit = MerchantCancelLimit.create(merchantId, newLimit);
        } else {
            // 기존 한도 변경
            domainService.updateLimit(merchant, limit, newLimit);
        }

        MerchantCancelLimit saved = limitRepository.save(limit);

        historyRepository.save(LimitHistory.record(merchantId, oldLimit, newLimit, reason));

        String kstDate = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        String payload = buildPayload(merchantId, newLimit, kstDate);
        outboxRepository.insertPending(merchantId, payload);

        return new Result(merchantId, saved.getDailyLimit());
    }

    private String buildPayload(long merchantId, BigDecimal newLimit, String kstDate) {
        return String.format(
            "{\"merchantId\":%d,\"newLimit\":%s,\"kstDate\":\"%s\"}",
            merchantId, newLimit.toPlainString(), kstDate
        );
    }
}
