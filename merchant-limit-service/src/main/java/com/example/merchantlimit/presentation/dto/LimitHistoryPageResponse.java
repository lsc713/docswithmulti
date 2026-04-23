package com.example.merchantlimit.presentation.dto;

import com.example.merchantlimit.domain.entity.LimitHistory;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LimitHistoryPageResponse(
    List<Item> content, long totalElements, int page, int size
) {
    public record Item(BigDecimal oldLimit, BigDecimal newLimit, String reason, Instant changedAt) {
        public static Item from(LimitHistory h) {
            return new Item(h.getOldLimit(), h.getNewLimit(), h.getReason(), h.getCreatedAt());
        }
    }

    public static LimitHistoryPageResponse from(Page<LimitHistory> page) {
        return new LimitHistoryPageResponse(
            page.getContent().stream().map(Item::from).toList(),
            page.getTotalElements(), page.getNumber(), page.getSize()
        );
    }
}
