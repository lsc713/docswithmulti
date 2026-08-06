package com.example.settlement.application.service;

import com.example.settlement.application.exception.ReserveNotFoundException;
import com.example.settlement.application.interfaces.ReserveRepository;
import com.example.settlement.domain.entity.Reserve;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유보 상태 조회 (HOLD-04). 정산의 유보금 미존재 시 ReserveNotFoundException(404). PayoutService.getPayout 미러.
 */
@Service
@RequiredArgsConstructor
public class ReserveQueryService {

    private final ReserveRepository reserveRepo;

    @Transactional(readOnly = true)
    public Reserve getReserve(long settlementId) {
        return reserveRepo.findBySettlementId(settlementId)
            .orElseThrow(() -> new ReserveNotFoundException(settlementId));
    }
}
