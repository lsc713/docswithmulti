package com.example.settlement.application.service;

import com.example.settlement.application.exception.InvalidStatusException;
import com.example.settlement.application.exception.SettlementNotFoundException;
import com.example.settlement.application.interfaces.SettlementLineRepository;
import com.example.settlement.application.interfaces.SettlementRepository;
import com.example.settlement.domain.entity.Settlement;
import com.example.settlement.domain.entity.SettlementLine;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 정산 원장 조회(읽기 전용) — QUERY-01. */
@Service
public class SettlementQueryService {

    private static final Set<String> VALID_STATUSES = Set.of("OPEN", "FINALIZED");

    private final SettlementRepository settlementRepo;
    private final SettlementLineRepository lineRepo;

    public SettlementQueryService(SettlementRepository settlementRepo, SettlementLineRepository lineRepo) {
        this.settlementRepo = settlementRepo;
        this.lineRepo = lineRepo;
    }

    /** status가 null/blank이면 전체, 아니면 {OPEN, FINALIZED}로 검증 후 필터(ASVS V5). */
    public List<Settlement> list(long merchantId, String status) {
        String normalized = null;
        if (status != null && !status.isBlank()) {
            normalized = status.trim().toUpperCase();
            if (!VALID_STATUSES.contains(normalized)) {
                throw new InvalidStatusException(status);
            }
        }
        return settlementRepo.findByMerchant(merchantId, normalized);
    }

    public SettlementWithLines detail(long id) {
        Settlement header = settlementRepo.findById(id)
            .orElseThrow(() -> new SettlementNotFoundException(id));
        List<SettlementLine> lines = lineRepo.findBySettlementId(id);
        return new SettlementWithLines(header, lines);
    }

    public record SettlementWithLines(Settlement header, List<SettlementLine> lines) {}
}
