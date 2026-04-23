package com.example.merchantlimit.application.interfaces;

import com.example.merchantlimit.domain.entity.LimitHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LimitHistoryRepository {
    void save(LimitHistory history);
    Page<LimitHistory> findByMerchantId(long merchantId, Pageable pageable);
}
